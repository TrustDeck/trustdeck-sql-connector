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

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Exposes the connector readiness state through Spring Boot Actuator.
 *
 * The connector is reported as unhealthy while processing is paused because of
 * a detected outage and as healthy while normal processing is running.
 * 
 * @author Armin Müller
 */
@Component("connector")
public class ConnectorHealthIndicator implements HealthIndicator {

	/** Shared pause state. */
	private final OutageState state;

	/**
	 * Creates a health indicator backed by the shared connector outage state.
	 *
	 * @param state outage state used to determine connector readiness
	 */
	public ConnectorHealthIndicator(OutageState state) {
		this.state = state;
	}

	/**
	 * Returns the current readiness health of the connector.
	 *
	 * A paused connector is reported as {@link Status#DOWN}; otherwise it is
	 * reported as {@link Status#UP}. The current processing state is included
	 * as a health detail.
	 *
	 * @return current connector health status
	 */
	@Override
	public Health health() {
		if (state.isPaused()) {
			return Health.down().withDetail("state", "paused").build();
		}

		return Health.up().withDetail("state", "running").build();
	}
}
