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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.file.AccessDeniedException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import com.fasterxml.jackson.core.JsonParseException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.trustdeck.client.exception.TrustDeckClientLibraryException;
import org.trustdeck.client.exception.TrustDeckResponseException;
import org.trustdeck.connector.sql.state.StateFileException;
import org.trustdeck.connector.sql.trustdeck.TokenAcquisitionException;

/**
 * Verifies classification of nested TrustDeck, token, database, and state file failures.
 *
 * @author Armin Müller
 */
class FailureClassifierTest {

    /** Read timeouts arrive as TrustDeckClientLibraryException -> ResourceAccessException -> SocketTimeoutException. */
    @Test
    void wrappedReadTimeoutIsSystemicBackendFailure() {
        Exception failure = library(new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out")));

        assertTrue(FailureClassifier.systemic(failure));
        assertEquals("BACKEND_UNAVAILABLE", FailureClassifier.category(failure));
    }

    /** Refused connections are systemic even without Spring's wrapper. */
    @Test
    void wrappedConnectionRefusalIsSystemic() {
        Exception failure = library(new ConnectException("Connection refused"));

        assertTrue(FailureClassifier.systemic(failure));
        assertEquals("BACKEND_UNAVAILABLE", FailureClassifier.category(failure));
    }

    /** An unreachable identity provider is a backend availability problem. */
    @Test
    void tokenTimeoutIsBackendUnavailable() {
        Exception failure = new TrustDeckClientLibraryException("Acquiring an access token failed.",
                new TokenAcquisitionException("OAuth token acquisition failed.", new HttpTimeoutException("timed out")));

        assertTrue(FailureClassifier.systemic(failure));
        assertEquals("BACKEND_UNAVAILABLE", FailureClassifier.category(failure));
    }

    /** A rejected client secret is systemic and reported as an authorization problem. */
    @Test
    void rejectedTokenRequestIsAuthorizationFailure() {
        Exception failure = new TrustDeckClientLibraryException("Acquiring an access token failed.",
                new TokenAcquisitionException("OAuth token acquisition failed.",
                        new IllegalStateException("OAuth token endpoint returned HTTP 401")));

        assertTrue(FailureClassifier.systemic(failure));
        assertEquals("AUTHORIZATION_FAILURE", FailureClassifier.category(failure));
    }

    /** HTTP status semantics are preserved for direct TrustDeck responses. */
    @Test
    void httpStatusesKeepTheirMeaning() {
        assertTrue(FailureClassifier.systemic(response(503)));
        assertEquals("BACKEND_UNAVAILABLE", FailureClassifier.category(response(503)));
        assertTrue(FailureClassifier.systemic(response(403)));
        assertEquals("AUTHORIZATION_FAILURE", FailureClassifier.category(response(403)));
        assertFalse(FailureClassifier.systemic(response(422)));
        assertEquals("REJECTED_BY_TRUSTDECK", FailureClassifier.category(response(422)));
    }

    /** Definitive client errors are not retried; timeouts and rate limits are. */
    @Test
    void onlyDefinitiveClientErrorsAreRejections() {
        assertTrue(FailureClassifier.rejected(response(422)));
        assertTrue(FailureClassifier.rejected(response(400)));
        assertTrue(FailureClassifier.rejected(new RuntimeException(response(404))));
        assertFalse(FailureClassifier.rejected(response(408)));
        assertFalse(FailureClassifier.rejected(response(429)));
        assertEquals("TRANSIENT_OPERATION_FAILURE", FailureClassifier.category(response(429)));
        assertFalse(FailureClassifier.rejected(response(403)));
        assertFalse(FailureClassifier.rejected(response(503)));
        assertFalse(FailureClassifier.rejected(new IllegalStateException("record-local")));
    }

    /** Spring HTTP errors nested in the library exception are classified by status. */
    @Test
    void nestedSpringServerErrorIsSystemic() {
        Exception failure = library(HttpServerErrorException.create(HttpStatusCode.valueOf(502), "Bad Gateway", null, null, null));

        assertTrue(FailureClassifier.systemic(failure));
        assertEquals("BACKEND_UNAVAILABLE", FailureClassifier.category(failure));
    }

    /** Unreadable response bodies are row-local although Jackson models them as IOExceptions. */
    @Test
    void jsonFailuresAreNotSystemic() {
        Exception failure = library(new HttpMessageNotReadableException("unreadable",
                new JsonParseException(null, "unexpected token"), null));

        assertFalse(FailureClassifier.systemic(failure));
        assertEquals("TRANSIENT_OPERATION_FAILURE", FailureClassifier.category(failure));
    }

    /** Database failures pause with the database category, whether raw or wrapped by Spring. */
    @Test
    void databaseFailuresAreSystemic() {
        SQLException connection = new SQLTransientConnectionException("pool exhausted", "08001");

        assertTrue(FailureClassifier.systemic(connection));
        assertEquals("DATABASE_UNAVAILABLE", FailureClassifier.category(connection));
        assertTrue(FailureClassifier.systemic(new DataAccessResourceFailureException("down")));
        assertEquals("DATABASE_UNAVAILABLE", FailureClassifier.category(new DataAccessResourceFailureException("down")));
        assertEquals("SQLTransientConnectionException(08001)", FailureClassifier.describe(connection));
    }

    /** Unwritable state files pause with their own category, although they wrap I/O failures. */
    @Test
    void stateFileFailuresAreSystemic() {
        StateFileException failure = new StateFileException("Progress file cannot be written.", new AccessDeniedException("state"));

        assertTrue(FailureClassifier.systemic(failure));
        assertEquals("STATE_FILE_UNAVAILABLE", FailureClassifier.category(failure));
    }

    /** Unrelated failures remain row-local. */
    @Test
    void plainFailuresAreNotSystemic() {
        assertFalse(FailureClassifier.systemic(new IllegalStateException("record-local")));
        assertFalse(FailureClassifier.systemic(library(new IllegalArgumentException("bad request body"))));
    }

    /** Descriptions name the cause chain without exception messages. */
    @Test
    void describesCauseChainWithoutMessages() {
        Exception failure = library(new ResourceAccessException("secret-host", new SocketTimeoutException("Read timed out")));

        assertEquals("TrustDeckClientLibraryException <- ResourceAccessException <- SocketTimeoutException",
                FailureClassifier.describe(failure));
    }

    /** HTTP failures name their status so 401 and 403 can be told apart in the log. */
    @Test
    void describesHttpStatus() {
        assertEquals("RuntimeException <- TrustDeckResponseException(403)",
                FailureClassifier.describe(new RuntimeException(response(403))));
    }

    /**
     * Wraps a cause like the TrustDeck client does.
     *
     * @param cause wrapped failure
     * @return client library exception
     */
    private static Exception library(Throwable cause) {
        return new TrustDeckClientLibraryException("TrustDeck request failed.", cause);
    }

    /**
     * Creates a TrustDeck response failure.
     *
     * @param status HTTP status
     * @return response exception
     */
    private static TrustDeckResponseException response(int status) {
        return new TrustDeckResponseException("status " + status, HttpStatusCode.valueOf(status));
    }
}
