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
package org.trustdeck.connector.sql.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.trustdeck.connector.sql.TrustDeckSqlConnectorApplication;

/**
 * Starts the complete connector application in polling mode for end-to-end tests.
 *
 * Settings are passed with the same names as the environment variables of a
 * deployment, so the tests also cover the binding in {@code application.yaml}.
 *
 * @author Armin Müller
 */
public final class ConnectorApplication {

	/** JSON parser for the progress file. */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Prevents instantiation of this utility class. */
	private ConnectorApplication() { }

	/**
	 * Returns the settings for a polling pseudonym import from the synthetic table.
	 *
	 * @param url JDBC URL of the source database
	 * @param username SQL login name
	 * @param password SQL login password
	 * @param state directory for the state files
	 * @param trustDeck fake TrustDeck server
	 * @return environment-style settings that may be extended by the caller
	 */
	public static Map<String, Object> settings(String url, String username, String password, Path state, FakeTrustDeck trustDeck) {
		Map<String, Object> settings = new HashMap<>();
		settings.put("TRUSTDECK_OPERATION", "CREATE_PSEUDONYM");
		settings.put("CONNECTOR_MODE", "POLLING");
		settings.put("CONNECTOR_POLL_INTERVAL", "200ms");
		settings.put("DB_URL", url);
		settings.put("DB_USERNAME", username);
		settings.put("DB_PASSWORD", password);
		settings.put("SOURCE_TABLE", "patients");
		settings.put("SOURCE_KEY_COLUMN", "id");
		settings.put("SOURCE_PAGE_SIZE", "2");
		settings.put("SOURCE_IDENTIFIER_COLUMN", "case_number");
		settings.put("SOURCE_IDTYPE_COLUMN", "id_type");
		settings.put("TRUSTDECK_DOMAIN", "synthetic-domain");
		settings.put("TRUSTDECK_PROJECT", "synthetic-project");
		settings.put("TRUSTDECK_SERVICE_URL", trustDeck.serviceUrl());
		settings.put("KEYCLOAK_TOKEN_ENDPOINT", trustDeck.tokenEndpoint());
		settings.put("KEYCLOAK_CLIENT_ID", "client");
		settings.put("KEYCLOAK_CLIENT_SECRET", "secret");
		settings.put("CONNECTOR_PROGRESS_FILE", state.resolve("progress.json").toString());
		settings.put("CONNECTOR_DLT_FILE", state.resolve("dlt.jsonl").toString());
		settings.put("CONNECTOR_REVIEW_FILE", state.resolve("review.jsonl").toString());
		settings.put("RECOVERY_INITIAL_BACKOFF", "0s");
		settings.put("HEALTH_PORT", "0");
		settings.put("LOG_COLORS", "never");

		return settings;
	}

	/**
	 * Starts the application.
	 *
	 * @param settings environment-style settings
	 * @return running application context
	 */
	public static ConfigurableApplicationContext start(Map<String, Object> settings) {
		return new SpringApplicationBuilder(TrustDeckSqlConnectorApplication.class).properties(settings).run();
	}

	/**
	 * Returns the key value stored in the progress file.
	 *
	 * @param state directory containing the progress file
	 * @return key value as text, or {@code null} if no progress was stored yet
	 */
	public static String progressKey(Path state) {
		try {
			Path file = state.resolve("progress.json");
			if (!Files.exists(file)) {
				return null;
			}

			JsonNode progress = MAPPER.readTree(file.toFile());
			return progress.at("/key/value").asText();
		} catch (IOException exception) {
			// The file is replaced atomically, so a failed read only happens while it is created
			return null;
		}
	}

	/**
	 * Waits until a condition holds.
	 *
	 * @param timeout maximum waiting time
	 * @param condition condition to wait for
	 * @throws InterruptedException if waiting is interrupted
	 * @throws AssertionError if the condition does not hold within the timeout
	 */
	public static void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();

		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Condition not reached within " + timeout + ".");
			}

			Thread.sleep(100);
		}
	}
}
