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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.trustdeck.connector.sql.support.ConnectorApplication;
import org.trustdeck.connector.sql.support.FakeTrustDeck;

/**
 * Runs the complete application against a disposable SQL Server.
 *
 * The source table uses SQL Server specific types: an IDENTITY key, a
 * {@code datetime} progress column with fractions that change when converted to
 * {@code datetime2}, an {@code nvarchar} identifier with leading zeros, and a
 * {@code datetimeoffset} validity start. The connector logs in with a SQL login
 * that may only read the source table.
 *
 * @author Armin Müller
 */
@Testcontainers(disabledWithoutDocker = true)
class SqlServerIntegrationTest {

	/** Disposable SQL Server; its license is accepted for this test only. */
	@Container
	private static final MSSQLServerContainer<?> SQL_SERVER = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-CU21-ubuntu-22.04")
			.acceptLicense();

	/** Read-only SQL login used by the connector. */
	private static final String READER = "connector_reader";

	/** Password of the read-only SQL login. */
	private static final String READER_PASSWORD = "Reader-Passw0rd!";

	/** Temporary directory for state files. */
	@TempDir
	Path state;

	/**
	 * Creates the source database, table, and read-only login.
	 *
	 * @throws SQLException if the database cannot be prepared
	 */
	@BeforeAll
	static void prepare() throws SQLException {
		execute(SQL_SERVER.getJdbcUrl(), "CREATE DATABASE clinic");
		execute(clinicUrl(),
				"CREATE TABLE dbo.Patients (Id INT IDENTITY PRIMARY KEY, Case_Number NVARCHAR(20), Id_Type NVARCHAR(20), "
						+ "Valid_From DATETIMEOFFSET, Recorded_At DATETIME NOT NULL)",
				"CREATE LOGIN " + READER + " WITH PASSWORD = '" + READER_PASSWORD + "'",
				"CREATE USER " + READER + " FOR LOGIN " + READER,
				"GRANT SELECT ON dbo.Patients TO " + READER,
				"INSERT INTO dbo.Patients (Case_Number, Id_Type, Valid_From, Recorded_At) VALUES "
						+ "(N'000123', N'case', '2025-10-09 21:08:10.175 +02:00', '2025-01-01T00:00:00.003'), "
						+ "(N'000124', N'case', NULL, '2025-01-01T00:00:00.003'), "
						+ "(N'000125', N'case', NULL, '2025-01-01T00:00:00.007'), "
						+ "(N'000126', N'case', NULL, '2025-01-01T00:00:00.007'), "
						+ "(N'000127', N'case', NULL, '2025-01-01T00:00:01.000')");
	}

	/**
	 * Rows are imported exactly once even though their datetime progress values
	 * do not survive a conversion to datetime2, and later rows are picked up.
	 *
	 * @throws Exception if the application cannot be started
	 */
	@Test
	void importsEveryRowExactlyOnce() throws Exception {
		try (FakeTrustDeck trustDeck = new FakeTrustDeck()) {
			Map<String, Object> settings = ConnectorApplication.settings(clinicUrl(), READER, READER_PASSWORD, state, trustDeck);
			settings.put("SOURCE_SCHEMA", "dbo");
			settings.put("SOURCE_PROGRESS_COLUMN", "recorded_at");
			settings.put("SOURCE_VALID_FROM_COLUMN", "valid_from");
			settings.put("MAPPING_SOURCE_TIMEZONE", "Europe/Berlin");

			try (ConfigurableApplicationContext context = ConnectorApplication.start(settings)) {
				ConnectorApplication.await(Duration.ofSeconds(60), () -> "5".equals(ConnectorApplication.progressKey(state)));

				// Several further polls must not read any row again
				Thread.sleep(1_500);
				assertEquals(5, creations(trustDeck));

				JsonNode first = new ObjectMapper().readTree(trustDeck.bodies().getFirst());
				assertEquals("000123", first.at("/identifierItem/identifier").asText());
				assertEquals("[2025,10,9,19,8,10,175000000]", first.get("validFrom").toString());

				execute(clinicUrl(),
						"INSERT INTO dbo.Patients (Case_Number, Id_Type, Recorded_At) VALUES (N'000128', N'case', '2025-01-02T00:00:00')");
				ConnectorApplication.await(Duration.ofSeconds(30), () -> "6".equals(ConnectorApplication.progressKey(state)));
				assertEquals(6, creations(trustDeck));
			}
		}
	}

	/**
	 * Documents why datetime parameters are cast: without the cast, a stored
	 * {@code .003} value is greater than the same value sent as a parameter.
	 *
	 * @throws SQLException if the query fails
	 */
	@Test
	void datetimeComparisonNeedsCast() throws SQLException {
		String uncast = "SELECT COUNT(*) FROM dbo.Patients WHERE Recorded_At > ? AND Id = 1";
		String cast = "SELECT COUNT(*) FROM dbo.Patients WHERE Recorded_At > CAST(? AS datetime) AND Id = 1";
		LocalDateTime stored = LocalDateTime.of(2025, 1, 1, 0, 0, 0, 3_000_000);

		assertEquals(1, count(uncast, stored));
		assertEquals(0, count(cast, stored));
	}

	/**
	 * The connector's login can read but not write the source table.
	 *
	 * @throws SQLException if the connection fails
	 */
	@Test
	void readerLoginCannotWrite() throws SQLException {
		try (Connection connection = DriverManager.getConnection(clinicUrl(), READER, READER_PASSWORD);
				Statement statement = connection.createStatement()) {
			assertTrue(statement.executeQuery("SELECT COUNT(*) FROM dbo.Patients").next());
			assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM dbo.Patients"));
		}
	}

	/**
	 * Returns the URL of the clinic database.
	 *
	 * The container uses a self-signed certificate, so certificate validation is
	 * disabled for this test only; production URLs validate the certificate.
	 *
	 * @return JDBC URL
	 */
	private static String clinicUrl() {
		return "jdbc:sqlserver://" + SQL_SERVER.getHost() + ":" + SQL_SERVER.getMappedPort(MSSQLServerContainer.MS_SQL_SERVER_PORT)
				+ ";databaseName=clinic;encrypt=true;trustServerCertificate=true";
	}

	/**
	 * Counts rows with a query that takes one time stamp parameter.
	 *
	 * @param sql query
	 * @param parameter time stamp parameter
	 * @return count
	 * @throws SQLException if the query fails
	 */
	private static int count(String sql, LocalDateTime parameter) throws SQLException {
		try (Connection connection = DriverManager.getConnection(clinicUrl(), READER, READER_PASSWORD);
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, parameter);

			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return result.getInt(1);
			}
		}
	}

	/**
	 * Executes statements as the administrator.
	 *
	 * @param url JDBC URL
	 * @param statements SQL statements
	 * @throws SQLException if a statement fails
	 */
	private static void execute(String url, String... statements) throws SQLException {
		try (Connection connection = DriverManager.getConnection(url, SQL_SERVER.getUsername(), SQL_SERVER.getPassword());
				Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				statement.execute(sql);
			}
		}
	}

	/**
	 * Counts single pseudonym creations received by the fake TrustDeck.
	 *
	 * @param trustDeck fake TrustDeck
	 * @return number of pseudonym creations
	 */
	private static long creations(FakeTrustDeck trustDeck) {
		return trustDeck.requests().stream().filter(request -> request.equals("POST /api/domains/synthetic-domain/pseudonyms")).count();
	}
}
