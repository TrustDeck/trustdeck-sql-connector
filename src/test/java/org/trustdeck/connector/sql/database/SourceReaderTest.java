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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.trustdeck.connector.sql.config.ConnectorProperties;
import org.trustdeck.connector.sql.support.H2Database;
import org.trustdeck.connector.sql.support.TestSettings;

/**
 * Verifies table resolution and keyset paging against an in-memory database.
 *
 * @author Armin Müller
 */
class SourceReaderTest {

	/** Temporary directory for state files. */
	@TempDir
	Path state;

	/** JDBC URL of the test database. */
	private String url;

	/** Connector settings pointing at the test database. */
	private ConnectorProperties properties;

	/** Pool opened by the test, closed afterwards. */
	private HikariDataSource dataSource;

	/**
	 * Creates the test database with rows in an order that differs from insertion order.
	 *
	 * @throws SQLException if the database cannot be prepared
	 */
	@BeforeEach
	void prepare() throws SQLException {
		url = H2Database.create();
		H2Database.execute(url,
				"INSERT INTO PATIENTS (ID, CASE_NUMBER, ID_TYPE, SITE, CREATED_AT, ADMITTED_AT) VALUES "
						+ "(30, '0030', 'case', 'A', TIMESTAMP '2025-01-02 00:00:00', TIMESTAMP WITH TIME ZONE '2025-01-02 10:00:00+02:00'), "
						+ "(10, '0010', 'case', 'A', TIMESTAMP '2025-01-01 00:00:00', NULL), "
						+ "(20, '0020', 'case', 'B', TIMESTAMP '2025-01-01 00:00:00', NULL), "
						+ "(40, '0040', 'case', 'A', NULL, NULL), "
						+ "(50, '0050', 'case', 'A', TIMESTAMP '2025-01-01 00:00:00', NULL)");
		properties = TestSettings.pseudonymImport(url, "sa", "sa", state, "http://localhost");
	}

	/** Closes the pool. */
	@AfterEach
	void close() {
		if (dataSource != null) {
			dataSource.close();
		}
	}

	/** Names are matched case-insensitively and quoted as the database stores them. */
	@Test
	void resolvesNamesCaseInsensitively() throws SQLException {
		SourceTable table = table();

		assertEquals("\"PUBLIC\".\"PATIENTS\"", table.qualifiedName());
		assertEquals("ID", table.key().name());
		assertTrue(table.progressIsKey());
		assertEquals("CASE_NUMBER", table.columns().get("case_number").name());
	}

	/** Missing tables and columns stop the startup with a clear message. */
	@Test
	void rejectsUnknownNames() {
		properties.getSource().setTable("unknown");
		IllegalStateException table = assertThrows(IllegalStateException.class, this::table);
		assertTrue(table.getMessage().contains("unknown"));

		properties.getSource().setTable("patients");
		properties.getMapping().getCommon().setIdTypeColumn("missing_column");
		IllegalStateException column = assertThrows(IllegalStateException.class, this::table);
		assertTrue(column.getMessage().contains("missing_column"));
	}

	/** Binary columns cannot order rows. */
	@Test
	void rejectsUnorderedProgressColumn() {
		properties.getSource().setProgressColumn("payload");

		assertThrows(IllegalStateException.class, this::table);
	}

	/** With the key as progress column, pages continue strictly after the last key. */
	@Test
	void pagesByKey() throws SQLException {
		List<Object> keys = readAll(reader(table()));

		assertEquals(List.of(10L, 20L, 30L, 40L, 50L), keys);
	}

	/** A separate progress column orders by progress, then key, and skips NULL progress values. */
	@Test
	void pagesByProgressThenKey() throws SQLException {
		properties.getSource().setProgressColumn("created_at");

		// Rows 10, 20, and 50 share a time stamp; the page size of 2 splits them
		assertEquals(List.of(10L, 20L, 50L, 30L), readAll(reader(table())));
	}

	/** The filter limits the rows. */
	@Test
	void appliesFilter() throws SQLException {
		properties.getSource().setWhere("site = 'A' AND (case_number <> '0050')");

		assertEquals(List.of(10L, 30L, 40L), readAll(reader(table())));
	}

	/** A filter the database cannot execute is reported at startup. */
	@Test
	void verifiesQueryAtStartup() throws SQLException {
		properties.getSource().setWhere("no_such_column = 1");

		assertThrows(SQLException.class, reader(table())::verify);
	}

	/** Time stamps are read as java.time values with and without offset. */
	@Test
	void readsTemporalTypes() throws SQLException {
		properties.getMapping().getCommon().setValidFromColumn("created_at");
		properties.getMapping().getCommon().setValidToColumn("admitted_at");
		SourceReader reader = reader(table());

		SourceRow row = reader.read(new Position(20L, 20L)).getFirst();

		assertEquals(30L, row.position().key());
		assertInstanceOf(LocalDateTime.class, row.value("CREATED_AT"));
		assertEquals(OffsetDateTime.parse("2025-01-02T10:00:00+02:00").toInstant(), ((OffsetDateTime) row.value("admitted_at")).toInstant());
	}

	/**
	 * Resolves the source table with the current settings.
	 *
	 * @return resolved table
	 * @throws SQLException if the metadata cannot be read
	 */
	private SourceTable table() throws SQLException {
		if (dataSource == null) {
			DatabaseConfiguration configuration = new DatabaseConfiguration();
			dataSource = configuration.dataSource(properties, configuration.sqlDialect(properties));
		}

		return new SourceTable(dataSource, SqlDialect.resolve(null, url), properties);
	}

	/**
	 * Creates a reader for a resolved table.
	 *
	 * @param table resolved table
	 * @return source reader
	 */
	private SourceReader reader(SourceTable table) {
		return new SourceReader(dataSource, table, properties);
	}

	/**
	 * Reads all pages the way the runner does and collects the keys.
	 *
	 * @param reader source reader
	 * @return keys in read order
	 * @throws SQLException if a query fails
	 */
	private static List<Object> readAll(SourceReader reader) throws SQLException {
		List<Object> keys = new ArrayList<>();
		Position position = null;

		for (List<SourceRow> page = reader.read(null); !page.isEmpty(); page = reader.read(position)) {
			assertTrue(page.size() <= 2);
			page.forEach(row -> keys.add(row.position().key()));
			position = page.getLast().position();
		}

		return keys;
	}
}
