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

package org.trustdeck.connector.sql.mapping;

/**
 * Indicates that a source row cannot satisfy the configured mapping contract.
 * 
 * @author Armin Müller
 */
public class MappingException extends RuntimeException {

	/** This exception's UID. */
	private static final long serialVersionUID = 8687269169769642307L;

	/**
	 * Creates a mapping failure.
	 *
	 * @param message safe diagnostic message naming columns but never values
	 */
	public MappingException(String message) {
		super(message);
	}

	/**
	 * Creates a mapping failure with its conversion cause.
	 *
	 * @param message safe diagnostic message naming columns but never values
	 * @param cause conversion cause
	 */
	public MappingException(String message, Throwable cause) {
		super(message, cause);
	}
}
