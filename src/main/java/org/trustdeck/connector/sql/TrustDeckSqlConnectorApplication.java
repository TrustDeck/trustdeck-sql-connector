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
package org.trustdeck.connector.sql;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Starts the TrustDeck SQL Connector instance.
 *
 * @author Armin Müller
 */
@SpringBootApplication
public class TrustDeckSqlConnectorApplication {

	/**
	 * Creates the application entry point.
	 */
	public TrustDeckSqlConnectorApplication() { }

	/**
	 * Starts the connector with a UTC JVM default for any library code that uses the default zone.
	 *
	 * @param args Spring Boot command-line arguments
	 */
	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(TrustDeckSqlConnectorApplication.class, args);
	}
}
