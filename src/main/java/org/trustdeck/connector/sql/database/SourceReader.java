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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.trustdeck.connector.sql.config.ConnectorProperties;

/**
 * Reads the source table page by page in a stable order.
 *
 * Rows are ordered by the progress column and then by the key column. A page
 * after a known position contains only rows that come after that position in
 * this order (keyset pagination), so the queries need no dialect-specific
 * offset handling and stay fast on large tables when both columns are indexed.
 * Rows with a NULL key or progress value cannot be ordered and are never read.
 *
 * @author Armin Müller
 */
@Slf4j
@Component
public class SourceReader {

	/** Driver-specific type code of the SQL Server {@code datetimeoffset} type. */
	private static final int SQLSERVER_DATETIMEOFFSET = -155;

	/** Source database. */
	private final DataSource dataSource;

	/** Resolved source table and columns. */
	private final SourceTable table;

	/** Maximum number of rows read with one query. */
	private final int pageSize;

	/** Maximum execution time of one query, in seconds. */
	private final int queryTimeoutSeconds;

	/** Query for the first page. */
	private final String firstQuery;

	/** Query for the page after a known position. */
	private final String nextQuery;

	/** Query used to probe the database, returning at most one row. */
	private final String probeQuery;

	/** Selected columns in result order. */
	private final List<SourceTable.Column> selected;

	/**
	 * Builds the source queries.
	 *
	 * @param dataSource source database
	 * @param table resolved source table and columns
	 * @param properties connector settings
	 */
	public SourceReader(DataSource dataSource, SourceTable table, ConnectorProperties properties) {
		this.dataSource = dataSource;
		this.table = table;
		this.pageSize = properties.getSource().getPageSize();
		this.queryTimeoutSeconds = (int) Math.max(1, properties.getDatabase().getQueryTimeout().toSeconds());
		this.selected = List.copyOf(table.columns().values());

		SqlDialect dialect = table.dialect();
		String columns = selected.stream().map(SourceTable.Column::quoted).collect(Collectors.joining(", "));
		String key = table.key().quoted();
		String progress = table.progress().quoted();

		// Rows without key or progress value cannot be ordered; the optional filter is kept in parentheses
		String base = progress + " IS NOT NULL AND " + key + " IS NOT NULL" + (table.filter() == null ? "" : " AND (" + table.filter() + ")");
		String order = table.progressIsKey() ? key : progress + ", " + key;
		String keyParameter = dialect.parameter(table.key().typeName());
		String progressParameter = dialect.parameter(table.progress().typeName());

		// Continue strictly after the last position in (progress, key) order
		String after = table.progressIsKey()
				? key + " > " + keyParameter
				: "(" + progress + " > " + progressParameter + " OR (" + progress + " = " + progressParameter + " AND " + key + " > " + keyParameter + "))";

		this.firstQuery = dialect.select(columns, table.qualifiedName(), base, order, pageSize);
		this.nextQuery = dialect.select(columns, table.qualifiedName(), base + " AND " + after, order, pageSize);
		this.probeQuery = dialect.select(columns, table.qualifiedName(), base, order, 1);
	}

	/**
	 * Runs the source query once, so a misconfigured filter, a missing SELECT
	 * permission, or an unreachable database stops the startup.
	 *
	 * @throws SQLException if the query fails
	 */
	@PostConstruct
	public void verify() throws SQLException {
		probe();
		log.debug("Source query: {}", nextQuery);
	}

	/**
	 * Reads the next page of rows.
	 *
	 * @param after position of the last completed row, or {@code null} to start at the beginning
	 * @return up to one page of rows in read order; empty if no further rows exist
	 * @throws SQLException if the query fails
	 */
	public List<SourceRow> read(Position after) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(after == null ? firstQuery : nextQuery)) {
			statement.setQueryTimeout(queryTimeoutSeconds);
			statement.setFetchSize(pageSize);
			statement.setMaxRows(pageSize);

			// Bind the position in the order of the placeholders
			if (after != null) {
				if (table.progressIsKey()) {
					statement.setObject(1, after.key());
				} else {
					statement.setObject(1, after.progress());
					statement.setObject(2, after.progress());
					statement.setObject(3, after.key());
				}
			}

			try (ResultSet result = statement.executeQuery()) {
				List<SourceRow> rows = new ArrayList<>();

				while (result.next()) {
					rows.add(row(result));
				}

				return rows;
			}
		}
	}

	/**
	 * Checks that the database is reachable and the source query can be executed.
	 *
	 * @throws SQLException if the query fails
	 */
	public void probe() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(probeQuery)) {
			statement.setQueryTimeout(queryTimeoutSeconds);
			statement.setMaxRows(1);

			try (ResultSet result = statement.executeQuery()) {
				result.next();
			}
		}
	}

	/**
	 * Converts the current result row.
	 *
	 * @param result result set positioned on a row
	 * @return source row
	 * @throws SQLException if a value cannot be read
	 */
	private SourceRow row(ResultSet result) throws SQLException {
		Map<String, Object> values = new LinkedHashMap<>();

		for (int index = 0; index < selected.size(); index++) {
			SourceTable.Column column = selected.get(index);
			values.put(column.name(), value(result, index + 1, column));
		}

		return new SourceRow(new Position(values.get(table.progress().name()), values.get(table.key().name())), values);
	}

	/**
	 * Reads one value with a Java type suited to its column type.
	 *
	 * Date and time columns are read as {@code java.time} values: time stamps
	 * without a time zone as {@link LocalDateTime}, time stamps with an offset
	 * (SQL Server {@code datetimeoffset}, PostgreSQL {@code timestamptz}) as
	 * {@link OffsetDateTime}. Large text is read as a string and binary data as a
	 * byte array. All other values are returned as the driver's default object.
	 *
	 * @param result result set positioned on a row
	 * @param index column index starting at one
	 * @param column column metadata
	 * @return column value, or {@code null} for SQL NULL
	 * @throws SQLException if the value cannot be read
	 */
	private static Object value(ResultSet result, int index, SourceTable.Column column) throws SQLException {
		String typeName = column.typeName() == null ? "" : column.typeName().toLowerCase(Locale.ROOT);

		if (column.jdbcType() == Types.TIMESTAMP_WITH_TIMEZONE || column.jdbcType() == SQLSERVER_DATETIMEOFFSET
				|| typeName.equals("timestamptz") || typeName.equals("datetimeoffset")) {
			return result.getObject(index, OffsetDateTime.class);
		}

		return switch (column.jdbcType()) {
			case Types.TIMESTAMP -> result.getObject(index, LocalDateTime.class);
			case Types.DATE -> result.getObject(index, LocalDate.class);
			case Types.TIME -> result.getObject(index, LocalTime.class);
			case Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR -> result.getString(index);
			case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> result.getBytes(index);
			default -> result.getObject(index);
		};
	}
}
