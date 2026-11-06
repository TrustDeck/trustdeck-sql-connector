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

package org.trustdeck.connector.sql.trustdeck;

/**
 * Signals that no OAuth access token could be obtained from the token endpoint.
 *
 * Token acquisition never depends on an individual source record, so this
 * failure always indicates a systemic problem (identity provider unavailable,
 * rejected client credentials, or an unusable token response). The TrustDeck
 * client wraps it in its own library exception, so callers have to search the
 * cause chain for this type.
 *
 * @author Armin Müller
 */
public class TokenAcquisitionException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates a token acquisition failure.
	 *
	 * @param message sanitized description that must not contain credentials
	 * @param cause underlying failure, or {@code null}
	 */
	public TokenAcquisitionException(String message, Throwable cause) {
		super(message, cause);
	}
}
