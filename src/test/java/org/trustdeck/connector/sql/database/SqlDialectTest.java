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

import org.junit.jupiter.api.Test;

/**
 * Verifies dialect detection, URL building, row limits, and parameter casts.
 *
 * @author Armin Müller
 */
class SqlDialectTest {

	/** A configured name wins; otherwise the JDBC subprotocol decides. */
	@Test
	void resolvesConfiguredOrDetectedDialect() {
		assertEquals(SqlDialect.POSTGRESQL, SqlDialect.resolve("postgresql", "jdbc:sqlserver://db:1433"));
		assertEquals(SqlDialect.SQLSERVER, SqlDialect.resolve(null, "jdbc:sqlserver://db:1433;databaseName=x"));
		assertEquals(SqlDialect.POSTGRESQL, SqlDialect.resolve(" ", "jdbc:postgresql://db/x"));
		assertEquals(SqlDialect.OTHER, SqlDialect.resolve(null, "jdbc:mariadb://db/x"));
		assertThrows(IllegalArgumentException.class, () -> SqlDialect.resolve("oracle", null));
		assertThrows(IllegalArgumentException.class, () -> SqlDialect.resolve(null, null));
	}

	/** Built URLs require TLS with certificate validation and use the default port. */
	@Test
	void buildsSecureUrls() {
		assertEquals("jdbc:sqlserver://db.example.org:1433;databaseName=clinic;encrypt=true;trustServerCertificate=false",
				SqlDialect.SQLSERVER.url("db.example.org", null, "clinic"));
		assertEquals("jdbc:postgresql://10.0.0.5:6543/clinic?sslmode=verify-full", SqlDialect.POSTGRESQL.url("10.0.0.5", 6543, "clinic"));
	}

	/** Host and database names cannot inject further URL settings. */
	@Test
	void rejectsUnsafeUrlParts() {
		assertThrows(IllegalArgumentException.class, () -> SqlDialect.SQLSERVER.url("db;trustServerCertificate=true", null, "clinic"));
		assertThrows(IllegalArgumentException.class, () -> SqlDialect.POSTGRESQL.url("db", null, "clinic?sslmode=disable"));
		assertThrows(IllegalArgumentException.class, () -> SqlDialect.POSTGRESQL.url("db", 70_000, "clinic"));
		assertThrows(IllegalArgumentException.class, () -> SqlDialect.OTHER.url("db", 3306, "clinic"));
	}

	/** Each dialect limits the rows with its own syntax. */
	@Test
	void limitsRowsPerDialect() {
		assertEquals("SELECT TOP (5) a FROM t WHERE x ORDER BY a", SqlDialect.SQLSERVER.select("a", "t", "x", "a", 5));
		assertEquals("SELECT a FROM t WHERE x ORDER BY a LIMIT 5", SqlDialect.POSTGRESQL.select("a", "t", "x", "a", 5));
		assertEquals("SELECT a FROM t WHERE x ORDER BY a LIMIT 5", SqlDialect.OTHER_LIMIT.select("a", "t", "x", "a", 5));
		assertEquals("SELECT a FROM t WHERE x ORDER BY a FETCH FIRST 5 ROWS ONLY", SqlDialect.OTHER.select("a", "t", "x", "a", 5));
	}

	/** Only SQL Server datetime columns need a cast for exact comparisons. */
	@Test
	void castsSqlServerDatetimeParameters() {
		assertEquals("CAST(? AS datetime)", SqlDialect.SQLSERVER.parameter("DATETIME"));
		assertEquals("CAST(? AS smalldatetime)", SqlDialect.SQLSERVER.parameter("smalldatetime"));
		assertEquals("?", SqlDialect.SQLSERVER.parameter("datetime2"));
		assertEquals("?", SqlDialect.POSTGRESQL.parameter("datetime"));
	}
}
