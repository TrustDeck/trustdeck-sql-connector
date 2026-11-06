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

package org.trustdeck.connector.sql.operation;

/**
 * Signals that an imported pseudonym contradicts a mapping already stored in
 * the target domain.
 *
 * TrustDeck answers such a create request with HTTP 422 and no body. The
 * connector resolves the reason by looking up the existing mapping, so the
 * row can be routed to the review file instead of the dead-letter file.
 * The message never contains identifiers or pseudonym values.
 *
 * @author Armin Müller
 */
public class PseudonymConflictException extends RuntimeException {

	private static final long serialVersionUID = 4120394866512398201L;

	/** Kind of contradiction found in the target domain. */
	public enum Reason {
		/** The identifier and idType are already mapped to a different pseudonym. */
		IDENTIFIER_HAS_OTHER_PSEUDONYM,

		/** The pseudonym is already assigned to a different identifier or idType. */
		PSEUDONYM_ASSIGNED_ELSEWHERE
	}

	/** Kind of contradiction. */
	private final Reason reason;

	/**
	 * Creates the exception.
	 *
	 * @param reason kind of contradiction
	 * @param cause TrustDeck rejection that triggered the lookup
	 */
	public PseudonymConflictException(Reason reason, Throwable cause) {
		super("Imported pseudonym conflicts with the target domain: " + reason + ".", cause);
		this.reason = reason;
	}

	/**
	 * Returns the kind of contradiction.
	 *
	 * @return conflict reason
	 */
	public Reason getReason() {
		return reason;
	}
}
