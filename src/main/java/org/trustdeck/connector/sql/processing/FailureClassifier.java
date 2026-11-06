/*
 * TrustDeck SQL Connector
 * Copyright 2026 Armin Müller
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.trustdeck.connector.sql.processing;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.core.JacksonException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.trustdeck.client.exception.TrustDeckResponseException;
import org.trustdeck.connector.sql.state.StateFileException;
import org.trustdeck.connector.sql.trustdeck.TokenAcquisitionException;

/**
 * Classifies processing failures as systemic or row-local and maps them to
 * sanitized recovery categories.
 *
 * The TrustDeck client wraps transport failures several levels deep, for example
 * {@code TrustDeckClientLibraryException -> ResourceAccessException -> SocketTimeoutException}
 * or {@code TrustDeckClientLibraryException -> TokenAcquisitionException -> HttpTimeoutException}.
 * Classification therefore inspects the complete cause chain rather than only
 * the outermost exception.
 *
 * @author Armin Müller
 */
public final class FailureClassifier {

	/** Category for failures of the TrustDeck backend or the network path to it. */
	public static final String BACKEND_UNAVAILABLE = "BACKEND_UNAVAILABLE";

	/** Category for rejected credentials or missing permissions. */
	public static final String AUTHORIZATION_FAILURE = "AUTHORIZATION_FAILURE";

	/** Category for failures of the source database or the network path to it. */
	public static final String DATABASE_UNAVAILABLE = "DATABASE_UNAVAILABLE";

	/** Category for progress, dead-letter, or review files that cannot be written. */
	public static final String STATE_FILE_UNAVAILABLE = "STATE_FILE_UNAVAILABLE";

	/** Category for row-local failures that persisted after the configured retries. */
	public static final String TRANSIENT_OPERATION_FAILURE = "TRANSIENT_OPERATION_FAILURE";

	/** Category for requests TrustDeck answered with a definitive client error, which a retry cannot change. */
	public static final String REJECTED_BY_TRUSTDECK = "REJECTED_BY_TRUSTDECK";

	/** Upper bound for walked causes, protecting against pathological chains. */
	private static final int MAX_DEPTH = 32;

	/** Prevents instantiation of this utility class. */
	private FailureClassifier() { }

	/**
	 * Determines whether a failure represents a systemic infrastructure,
	 * authentication, database, or backend problem that must pause processing
	 * rather than drain the affected row to the dead-letter file.
	 *
	 * The first HTTP status found in the cause chain decides for HTTP failures
	 * (401, 403, and 5xx are systemic). Otherwise any I/O failure, Spring
	 * {@link ResourceAccessException}, database failure, state file failure,
	 * timeout, or token acquisition failure anywhere in the chain is systemic.
	 * JSON processing failures are treated as row-local even though Jackson
	 * models them as {@link IOException}s, because they are caused by row content.
	 *
	 * @param failure failure to classify
	 * @return {@code true} if processing should be paused, otherwise {@code false}
	 */
	public static boolean systemic(Throwable failure) {
		for (Throwable cause : chain(failure)) {
			Integer status = httpStatus(cause);
			if (status != null) {
				return systemicStatus(status);
			}

			if (cause instanceof JacksonException) {
				return false;
			}

			if (cause instanceof TokenAcquisitionException
					|| cause instanceof StateFileException
					|| cause instanceof SQLException
					|| cause instanceof DataAccessException
					|| cause instanceof IOException
					|| cause instanceof ResourceAccessException
					|| cause instanceof TimeoutException) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Determines whether TrustDeck definitively rejected the request.
	 *
	 * The first HTTP status found in the cause chain decides: a 4xx status other
	 * than 401 and 403 (systemic), 408 and 429 (transient) means the request
	 * itself cannot succeed, so retrying it only wastes time.
	 *
	 * @param failure failure to classify
	 * @return {@code true} if the request must not be retried
	 */
	public static boolean rejected(Throwable failure) {
		for (Throwable cause : chain(failure)) {
			Integer status = httpStatus(cause);
			if (status != null) {
				return rejectedStatus(status);
			}
		}

		return false;
	}

	/**
	 * Converts a failure into a sanitized recovery or outage category.
	 *
	 * The returned category deliberately avoids exposing exception messages or
	 * other potentially sensitive diagnostic information.
	 *
	 * @param failure failure to categorize
	 * @return recovery category for the failure
	 */
	public static String category(Throwable failure) {
		List<Throwable> chain = chain(failure);

		// Local failures are checked first, because they may wrap I/O exceptions
		if (chain.stream().anyMatch(StateFileException.class::isInstance)) {
			return STATE_FILE_UNAVAILABLE;
		}

		if (chain.stream().anyMatch(cause -> cause instanceof SQLException || cause instanceof DataAccessException)) {
			return DATABASE_UNAVAILABLE;
		}

		for (Throwable cause : chain) {
			Integer status = httpStatus(cause);
			if (status != null) {
				return status == 401 || status == 403 ? AUTHORIZATION_FAILURE
						: status >= 500 ? BACKEND_UNAVAILABLE
						: rejectedStatus(status) ? REJECTED_BY_TRUSTDECK : TRANSIENT_OPERATION_FAILURE;
			}
		}

		if (chain.stream().anyMatch(TokenAcquisitionException.class::isInstance)) {
			// An unreachable identity provider is an availability problem; anything else is credential-related
			return transport(chain) ? BACKEND_UNAVAILABLE : AUTHORIZATION_FAILURE;
		}

		return systemic(failure) ? BACKEND_UNAVAILABLE : TRANSIENT_OPERATION_FAILURE;
	}

	/**
	 * Describes a failure by the simple class names of its cause chain, for
	 * example {@code TrustDeckClientLibraryException <- ResourceAccessException <- SocketTimeoutException}.
	 * HTTP failures carry their status, for example {@code TrustDeckResponseException(403)},
	 * and database failures their SQL state, for example {@code SQLException(08S01)}.
	 *
	 * Exception messages are omitted because they may contain row content or
	 * credentials.
	 *
	 * @param failure failure to describe
	 * @return sanitized cause chain description
	 */
	public static String describe(Throwable failure) {
		List<String> names = new ArrayList<>();
		for (Throwable cause : chain(failure)) {
			Integer status = httpStatus(cause);
			String detail = status != null ? String.valueOf(status)
					: cause instanceof SQLException sql && sql.getSQLState() != null ? sql.getSQLState() : null;

			names.add(cause.getClass().getSimpleName() + (detail == null ? "" : "(" + detail + ")"));
		}

		return String.join(" <- ", names);
	}

	/**
	 * Returns the failure followed by its causes, outermost first, stopping at
	 * cycles or after {@link #MAX_DEPTH} entries.
	 *
	 * @param failure outermost failure, may be {@code null}
	 * @return cause chain
	 */
	private static List<Throwable> chain(Throwable failure) {
		List<Throwable> chain = new ArrayList<>();
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());

		for (Throwable cause = failure; cause != null && chain.size() < MAX_DEPTH && seen.add(cause); cause = cause.getCause()) {
			chain.add(cause);
		}

		return chain;
	}

	/**
	 * Returns the HTTP status carried by a TrustDeck or Spring HTTP exception.
	 *
	 * @param cause exception to inspect
	 * @return HTTP status, or {@code null} if the exception carries none
	 */
	private static Integer httpStatus(Throwable cause) {
		if (cause instanceof TrustDeckResponseException response) {
			return response.getStatusCode();
		}

		if (cause instanceof RestClientResponseException response) {
			return response.getStatusCode().value();
		}

		return null;
	}

	/**
	 * Tests whether an HTTP status indicates a systemic problem.
	 *
	 * @param status HTTP status
	 * @return {@code true} for 401, 403, and 5xx
	 */
	private static boolean systemicStatus(int status) {
		return status == 401 || status == 403 || status >= 500;
	}

	/**
	 * Tests whether an HTTP status is a definitive client error.
	 *
	 * @param status HTTP status
	 * @return {@code true} for 4xx except 401, 403, 408, and 429
	 */
	private static boolean rejectedStatus(int status) {
		return status >= 400 && status < 500 && status != 401 && status != 403 && status != 408 && status != 429;
	}

	/**
	 * Tests whether a cause chain contains a transport-level failure.
	 *
	 * @param chain cause chain
	 * @return {@code true} if a non-JSON I/O failure or resource access failure is present
	 */
	private static boolean transport(List<Throwable> chain) {
		return chain.stream().anyMatch(cause -> cause instanceof ResourceAccessException
				|| (cause instanceof IOException && !(cause instanceof JacksonException)));
	}
}
