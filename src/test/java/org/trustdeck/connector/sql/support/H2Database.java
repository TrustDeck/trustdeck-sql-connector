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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Creates isolated in-memory H2 databases with the synthetic {@code PATIENTS} table.
 *
 * @author Armin Müller
 */
public final class H2Database {

	/** Prevents instantiation of this utility class. */
	private H2Database() { }

	/**
	 * Creates a new database with an empty {@code PATIENTS} table.
	 *
	 * The database lives until the JVM ends, so the connector's own connections
	 * see the same data.
	 *
	 * @return JDBC URL of the new database
	 * @throws SQLException if the database cannot be created
	 */
	public static String create() throws SQLException {
		String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

		execute(url, """
				CREATE TABLE PATIENTS (
					ID BIGINT PRIMARY KEY,
					CASE_NUMBER VARCHAR(20),
					ID_TYPE VARCHAR(20),
					PSN VARCHAR(40),
					SITE VARCHAR(10),
					CREATED_AT TIMESTAMP,
					ADMITTED_AT TIMESTAMP WITH TIME ZONE,
					PAYLOAD BLOB)""");

		return url;
	}

	/**
	 * Executes statements as the database owner.
	 *
	 * @param url JDBC URL of the database
	 * @param statements SQL statements
	 * @throws SQLException if a statement fails
	 */
	public static void execute(String url, String... statements) throws SQLException {
		try (Connection connection = DriverManager.getConnection(url, "sa", "sa"); Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				statement.execute(sql);
			}
		}
	}
}
