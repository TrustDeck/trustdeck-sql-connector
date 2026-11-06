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

package org.trustdeck.connector.sql.health;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/**
 * Stores the shared outage state of the connector.
 *
 * The state tracks whether source consumption is currently paused and retains
 * the sanitized reason for the most recent pause operation.
 *
 * @author Armin Müller
 */
@Component
public class OutageState {

	/** Indicates whether connector consumption is currently paused. */
	private final AtomicBoolean paused = new AtomicBoolean();

	/** Sanitized reason associated with the current or most recent pause state. */
	private volatile String reason = "";

	/**
	 * Creates an initially healthy connector state.
	 */
	public OutageState() { }

	/**
	 * Updates the connector pause state and associated reason.
	 *
	 * A {@code null} reason is normalized to an empty string before the new
	 * state becomes visible to other components.
	 *
	 * @param value whether connector consumption is paused or not
	 * @param newReason sanitized reason associated with the state change
	 */
	public void setPaused(boolean value, String newReason) {
		reason = newReason == null ? "" : newReason;
		paused.set(value);
	}

	/**
	 * Returns whether connector consumption is currently paused.
	 *
	 * @return {@code true} when consumption is paused, otherwise {@code false}
	 */
	public boolean isPaused() {
		return paused.get();
	}

	/**
	 * Returns the sanitized reason associated with the current outage state.
	 *
	 * @return sanitized pause reason, or an empty string if none is set
	 */
	public String getReason() {
		return reason;
	}
}
