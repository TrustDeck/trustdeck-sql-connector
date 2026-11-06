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

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The few database-specific parts of the statements and URLs the connector builds.
 *
 * Everything else the connector sends is standard SQL. A dialect knows how to
 * build a JDBC URL with secure TLS defaults from host, port, and database name,
 * how to limit the number of rows returned by one query, and how to compare a
 * parameter with a column of a type the database cannot compare exactly with
 * the parameter's JDBC type.
 *
 * @author Armin Müller
 */
public enum SqlDialect {

	/** Microsoft SQL Server 2012 or newer. */
	SQLSERVER(1433),

	/** PostgreSQL 12 or newer. */
	POSTGRESQL(5432),

	/** Any other database supporting the SQL standard {@code FETCH FIRST n ROWS ONLY} clause. */
	OTHER(null),

	/** Any other database supporting {@code LIMIT n}, for example MySQL. */
	OTHER_LIMIT(null);

	/** Characters allowed in a host name or IPv4/IPv6 address used to build a URL. */
	private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+\\]");

	/** Characters allowed in a database name used to build a URL. */
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.$-]+");

	/** Default port of the database, or {@code null} if URLs cannot be built. */
	private final Integer defaultPort;

	/**
	 * Creates a dialect.
	 *
	 * @param defaultPort default port of the database, or {@code null} if URLs cannot be built
	 */
	SqlDialect(Integer defaultPort) {
		this.defaultPort = defaultPort;
	}

	/**
	 * Determines the dialect from a configured name or, if absent, from a JDBC URL.
	 *
	 * @param name configured dialect name, may be {@code null} or blank
	 * @param url JDBC URL, may be {@code null} or blank
	 * @return the configured or detected dialect; {@link #OTHER} for unknown URLs
	 * @throws IllegalArgumentException if neither a known name nor a URL is given
	 */
	public static SqlDialect resolve(String name, String url) {
		if (name != null && !name.isBlank()) {
			try {
				return valueOf(name.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException exception) {
				throw new IllegalArgumentException("database.dialect must be one of SQLSERVER, POSTGRESQL, OTHER, or OTHER_LIMIT.", exception);
			}
		}

		if (url == null || url.isBlank()) {
			throw new IllegalArgumentException("database.dialect is required when no database.url is given.");
		}

		// Detect the dialect from the JDBC subprotocol
		String lower = url.trim().toLowerCase(Locale.ROOT);
		if (lower.startsWith("jdbc:sqlserver:")) {
			return SQLSERVER;
		}

		if (lower.startsWith("jdbc:postgresql:")) {
			return POSTGRESQL;
		}

		return OTHER;
	}

	/**
	 * Builds a JDBC URL with secure TLS defaults.
	 *
	 * SQL Server URLs require encryption and certificate validation, PostgreSQL
	 * URLs require TLS with certificate and host name verification. Additional
	 * settings, such as a truststore, are passed as connection properties.
	 *
	 * @param host database host name or address
	 * @param port database port, or {@code null} for the default port
	 * @param database database name
	 * @return JDBC URL
	 * @throws IllegalArgumentException if this dialect cannot build URLs or a part is invalid
	 */
	public String url(String host, Integer port, String database) {
		if (defaultPort == null) {
			throw new IllegalArgumentException("database.url is required for dialect " + this + ".");
		}

		if (host == null || !HOST.matcher(host).matches()) {
			throw new IllegalArgumentException("database.host is missing or contains unsupported characters; use database.url instead.");
		}

		if (database == null || !NAME.matcher(database).matches()) {
			throw new IllegalArgumentException("database.name is missing or contains unsupported characters; use database.url instead.");
		}

		int effectivePort = port == null ? defaultPort : port;
		if (effectivePort < 1 || effectivePort > 65_535) {
			throw new IllegalArgumentException("database.port must be between 1 and 65535.");
		}

		return switch (this) {
			case SQLSERVER -> "jdbc:sqlserver://" + host + ":" + effectivePort + ";databaseName=" + database
					+ ";encrypt=true;trustServerCertificate=false";
			case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + effectivePort + "/" + database + "?sslmode=verify-full";
			default -> throw new IllegalArgumentException("database.url is required for dialect " + this + ".");
		};
	}

	/**
	 * Builds a SELECT statement that returns at most a given number of rows.
	 *
	 * All parts except the limit are passed in unchanged and must already be
	 * validated and quoted by the caller.
	 *
	 * @param columns quoted, comma-separated column list
	 * @param table quoted table name
	 * @param where condition without the {@code WHERE} keyword
	 * @param orderBy order without the {@code ORDER BY} keywords
	 * @param limit maximum number of rows
	 * @return SELECT statement
	 */
	public String select(String columns, String table, String where, String orderBy, int limit) {
		String tail = " FROM " + table + " WHERE " + where + " ORDER BY " + orderBy;

		return switch (this) {
			case SQLSERVER -> "SELECT TOP (" + limit + ") " + columns + tail;
			case POSTGRESQL, OTHER_LIMIT -> "SELECT " + columns + tail + " LIMIT " + limit;
			case OTHER -> "SELECT " + columns + tail + " FETCH FIRST " + limit + " ROWS ONLY";
		};
	}

	/**
	 * Returns the parameter placeholder used to compare a value with a column.
	 *
	 * SQL Server converts a {@code datetime} column to {@code datetime2} when it
	 * is compared with a {@code datetime2} parameter, which is what the driver
	 * sends for Java date-times. The conversion changes the stored fractions of a
	 * second (for example {@code .003} becomes {@code .0033333}), so a row would
	 * appear to be newer than its own stored position. Casting the parameter to
	 * the column type keeps the comparison exact.
	 *
	 * @param databaseTypeName type name of the compared column as reported by the driver
	 * @return parameter placeholder, possibly wrapped in a cast
	 */
	public String parameter(String databaseTypeName) {
		if (this == SQLSERVER && databaseTypeName != null) {
			String type = databaseTypeName.toLowerCase(Locale.ROOT);

			if (type.equals("datetime") || type.equals("smalldatetime")) {
				return "CAST(? AS " + type + ")";
			}
		}

		return "?";
	}
}
