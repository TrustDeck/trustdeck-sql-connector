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

/**
 * Signals that processing must pause at the first row that is not completed yet.
 *
 * Thrown for systemic failures of TrustDeck, the source database, or a local
 * state file. All rows before the failed one are completed and their progress
 * is stored; the failed row and all later rows are read again after recovery.
 *
 * @author Armin Müller
 */
public class PauseRequired extends Exception {

	/** This exception's UID. */
	private static final long serialVersionUID = -3150286519471309612L;

	/** Dynamic TrustDeck domain involved in the failure, or {@code null}. */
	private final String domain;

	/** Dynamic TrustDeck entity type involved in the failure, or {@code null}. */
	private final String entityType;

	/**
	 * Creates a pause signal.
	 *
	 * @param cause systemic failure
	 * @param domain dynamic TrustDeck domain involved in the failure, or {@code null}
	 * @param entityType dynamic TrustDeck entity type involved in the failure, or {@code null}
	 */
	public PauseRequired(Throwable cause, String domain, String entityType) {
		super(FailureClassifier.describe(cause), cause, false, false);
		this.domain = domain;
		this.entityType = entityType;
	}

	/**
	 * Returns the dynamic TrustDeck domain involved in the failure.
	 *
	 * @return domain, or {@code null}
	 */
	public String getDomain() {
		return domain;
	}

	/**
	 * Returns the dynamic TrustDeck entity type involved in the failure.
	 *
	 * @return entity type, or {@code null}
	 */
	public String getEntityType() {
		return entityType;
	}
}
