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
package org.trustdeck.connector.sql.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.trustdeck.connector.sql.support.ConnectorApplication;
import org.trustdeck.connector.sql.support.FakeTrustDeck;

/**
 * Runs the complete application against a disposable PostgreSQL.
 *
 * The source table is keyed by a UUID and ordered by a {@code timestamptz}
 * progress column, and the connector filters it with a WHERE condition.
 *
 * @author Armin Müller
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresIntegrationTest {

	/** Disposable PostgreSQL. */
	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.4");

	/** Read-only role used by the connector. */
	private static final String READER = "connector_reader";

	/** Password of the read-only role. */
	private static final String READER_PASSWORD = "reader-password";

	/** Temporary directory for state files. */
	@TempDir
	Path state;

	/**
	 * Creates the source table and the read-only role.
	 *
	 * @throws SQLException if the database cannot be prepared
	 */
	@BeforeAll
	static void prepare() throws SQLException {
		try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE TABLE patients (id UUID PRIMARY KEY, case_number VARCHAR(20), id_type VARCHAR(20), "
					+ "site VARCHAR(5), recorded_at TIMESTAMPTZ NOT NULL)");
			statement.execute("CREATE ROLE " + READER + " LOGIN PASSWORD '" + READER_PASSWORD + "'");
			statement.execute("GRANT SELECT ON patients TO " + READER);
			statement.execute("INSERT INTO patients VALUES "
					+ "('00000000-0000-0000-0000-000000000003', '0003', 'case', 'A', '2025-01-01 00:00:00+00'), "
					+ "('00000000-0000-0000-0000-000000000001', '0001', 'case', 'A', '2025-01-01 00:00:00+00'), "
					+ "('00000000-0000-0000-0000-000000000002', '0002', 'case', 'B', '2025-01-01 00:00:00+00'), "
					+ "('00000000-0000-0000-0000-000000000004', NULL, 'case', 'A', '2025-01-02 00:00:00+00'), "
					+ "('00000000-0000-0000-0000-000000000005', '0005', 'case', 'A', '2025-01-01 12:00:00+00')");
		}
	}

	/**
	 * Filtered rows are imported in progress and key order; the invalid row is dead-lettered.
	 *
	 * @throws Exception if the application cannot be started
	 */
	@Test
	void importsFilteredRowsInOrder() throws Exception {
		try (FakeTrustDeck trustDeck = new FakeTrustDeck()) {
			Map<String, Object> settings = ConnectorApplication.settings(POSTGRES.getJdbcUrl(), READER, READER_PASSWORD, state, trustDeck);
			settings.put("SOURCE_PROGRESS_COLUMN", "recorded_at");
			settings.put("SOURCE_WHERE", "site = 'A'");

			try (ConfigurableApplicationContext context = ConnectorApplication.start(settings)) {
				ConnectorApplication.await(Duration.ofSeconds(60),
						() -> "00000000-0000-0000-0000-000000000004".equals(ConnectorApplication.progressKey(state)));
				Thread.sleep(1_000);

				// Site B is filtered out; the row without case number ends in the dead-letter file
				assertEquals(3, trustDeck.bodies().size());
				assertTrue(trustDeck.bodies().get(0).contains("\"0001\""));
				assertTrue(trustDeck.bodies().get(1).contains("\"0003\""));
				assertTrue(trustDeck.bodies().get(2).contains("\"0005\""));
				assertTrue(Files.readString(state.resolve("dlt.jsonl")).contains("00000000-0000-0000-0000-000000000004"));
			}
		}
	}
}
