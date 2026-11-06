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
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.sql.DataSource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.trustdeck.connector.sql.config.ConnectorProperties;

/**
 * The source table and its configured columns, resolved against the database catalog.
 *
 * Configured names are never inserted into SQL directly. The table and every
 * configured column must exist in the database metadata; the names reported
 * there are quoted with the identifier quote string of the driver. Names are
 * matched case-insensitively, so unquoted names work the same way they do in
 * hand-written SQL. Startup fails if a name does not exist or is ambiguous.
 *
 * @author Armin Müller
 */
@Slf4j
@Component
public class SourceTable {

	/** JDBC types that cannot be used as key or progress columns. */
	private static final Set<Integer> UNORDERED_TYPES = Set.of(Types.BLOB, Types.CLOB, Types.NCLOB, Types.LONGVARBINARY,
			Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.SQLXML, Types.ARRAY, Types.STRUCT, Types.JAVA_OBJECT, Types.BOOLEAN,
			Types.BIT);

	/** SQL dialect of the source database. */
	private final SqlDialect dialect;

	/** Quoted, schema-qualified table name. */
	private final String qualifiedName;

	/** Resolved key column. */
	private final Column key;

	/** Resolved progress column; the key column if none is configured. */
	private final Column progress;

	/** Validated filter condition, or {@code null} if none is configured. */
	private final String filter;

	/** Resolved columns selected from the table, by configured name, case-insensitive. */
	private final Map<String, Column> columns;

	/**
	 * Resolves the configured table and columns.
	 *
	 * @param dataSource source database
	 * @param dialect SQL dialect of the source database
	 * @param properties connector settings
	 * @throws SQLException if the database metadata cannot be read
	 * @throws IllegalStateException if the table or a column does not exist or is ambiguous
	 */
	public SourceTable(DataSource dataSource, SqlDialect dialect, ConnectorProperties properties) throws SQLException {
		this.dialect = dialect;
		ConnectorProperties.SourceProperties source = properties.getSource();
		this.filter = blank(source.getWhere()) ? null : validateFilter(source.getWhere());

		try (Connection connection = dataSource.getConnection()) {
			DatabaseMetaData metadata = connection.getMetaData();
			String quote = metadata.getIdentifierQuoteString();
			quote = quote == null || quote.isBlank() ? "" : quote.trim();

			// Find exactly one table or view with the configured name
			TableName table = findTable(metadata, connection.getCatalog(), source.getSchema(), source.getTable());
			Map<String, Column> available = columns(metadata, table, quote);

			this.qualifiedName = (table.schema() == null ? "" : quote(table.schema(), quote) + ".") + quote(table.name(), quote);

			// Resolve every configured column; the key and progress columns are always selected
			Map<String, Column> resolved = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			for (String name : configuredColumns(properties)) {
				Column column = available.get(name);

				if (column == null) {
					throw new IllegalStateException("Column " + name + " does not exist in source table " + table + ".");
				}

				resolved.put(name, column);
			}

			this.columns = Collections.unmodifiableMap(resolved);
			this.key = resolved.get(source.getKeyColumn());
			this.progress = resolved.get(blank(source.getProgressColumn()) ? source.getKeyColumn() : source.getProgressColumn());

			// Key and progress values must be comparable and storable in the progress file
			for (Column ordered : List.of(key, progress)) {
				if (UNORDERED_TYPES.contains(ordered.jdbcType())) {
					throw new IllegalStateException("Column " + ordered.name() + " of type " + ordered.typeName()
							+ " cannot be used as key or progress column.");
				}
			}

			warnIfKeyNotUnique(metadata, table);
			log.info("Source table {} resolved with {} column(s); key {}, progress {}.", table, columns.size(), key.name(), progress.name());
		}
	}

	/**
	 * Validates a configured filter condition.
	 *
	 * The condition is inserted into the WHERE clause in parentheses. It must not
	 * contain statement separators or comments, and its parentheses must be
	 * balanced outside of string literals and quoted identifiers, so it cannot
	 * close the surrounding parentheses and weaken the progress condition.
	 *
	 * @param filter configured filter condition
	 * @return trimmed filter condition
	 * @throws IllegalArgumentException if the condition is not allowed
	 */
	public static String validateFilter(String filter) {
		String condition = filter.trim();

		if (condition.contains(";") || condition.contains("--") || condition.contains("/*") || condition.contains("*/")) {
			throw new IllegalArgumentException("source.where must not contain ';', '--', '/*', or '*/'.");
		}

		int depth = 0;
		Character quoted = null;

		// Count parentheses outside of 'literals', "identifiers", and [identifiers]
		for (int index = 0; index < condition.length(); index++) {
			char current = condition.charAt(index);

			if (quoted != null) {
				if (current == quoted) {
					quoted = null;
				}

				continue;
			}

			switch (current) {
				case '\'' -> quoted = '\'';
				case '"' -> quoted = '"';
				case '[' -> quoted = ']';
				case '(' -> depth++;
				case ')' -> depth--;
				default -> { }
			}

			if (depth < 0) {
				break;
			}
		}

		if (depth != 0 || quoted != null) {
			throw new IllegalArgumentException("source.where has unbalanced parentheses or quotes.");
		}

		return condition;
	}

	/**
	 * Returns the dialect of the source database.
	 *
	 * @return SQL dialect
	 */
	public SqlDialect dialect() {
		return dialect;
	}

	/**
	 * Returns the quoted, schema-qualified table name.
	 *
	 * @return table name usable in a FROM clause
	 */
	public String qualifiedName() {
		return qualifiedName;
	}

	/**
	 * Returns the resolved key column.
	 *
	 * @return key column
	 */
	public Column key() {
		return key;
	}

	/**
	 * Returns the resolved progress column.
	 *
	 * @return progress column, which is the key column if no separate column is configured
	 */
	public Column progress() {
		return progress;
	}

	/**
	 * Tells whether the progress column is the key column.
	 *
	 * @return {@code true} if rows are ordered by the key column alone
	 */
	public boolean progressIsKey() {
		return progress.name().equals(key.name());
	}

	/**
	 * Returns the validated filter condition.
	 *
	 * @return filter condition, or {@code null} if none is configured
	 */
	public String filter() {
		return filter;
	}

	/**
	 * Returns all selected columns, by configured name.
	 *
	 * @return unmodifiable, case-insensitive map of selected columns
	 */
	public Map<String, Column> columns() {
		return columns;
	}

	/**
	 * Collects the names of all columns the configuration refers to.
	 *
	 * @param properties connector settings
	 * @return configured column names in a stable order, without blanks
	 */
	private static Set<String> configuredColumns(ConnectorProperties properties) {
		ConnectorProperties.CommonMappingProperties common = properties.getMapping().getCommon();
		ConnectorProperties.UpdatePseudonymMappingProperties update = properties.getMapping().getUpdatePseudonym();
		List<String> names = new ArrayList<>();

		names.add(properties.getSource().getKeyColumn());
		names.add(properties.getSource().getProgressColumn());
		names.addAll(List.of(nullSafe(common.getDomainColumn()), nullSafe(common.getIdentifierColumn()), nullSafe(common.getIdTypeColumn()),
				nullSafe(common.getPsnColumn()), nullSafe(common.getValidityTimeColumn()), nullSafe(common.getValidFromColumn()),
				nullSafe(common.getValidToColumn()), nullSafe(common.getUsernameColumn()), nullSafe(common.getEntityTypeColumn()),
				nullSafe(common.getEntityIdColumn())));
		names.addAll(List.of(nullSafe(update.getOldIdentifierColumn()), nullSafe(update.getOldIdTypeColumn()), nullSafe(update.getOldPsnColumn()),
				nullSafe(update.getNewIdentifierColumn()), nullSafe(update.getNewIdTypeColumn()), nullSafe(update.getNewPsnColumn()),
				nullSafe(update.getOldDomainColumn()), nullSafe(update.getNewDomainColumn())));

		for (ConnectorProperties.FieldMapping field : common.getEntityFields()) {
			names.add(field == null ? null : field.getColumn());
		}

		// Case-insensitive de-duplication keeps the first spelling of each name
		Set<String> unique = new LinkedHashSet<>();
		Set<String> seen = new LinkedHashSet<>();
		for (String name : names) {
			if (!blank(name) && seen.add(name.trim().toLowerCase(Locale.ROOT))) {
				unique.add(name.trim());
			}
		}

		return unique;
	}

	/**
	 * Finds exactly one table or view with the given schema and name.
	 *
	 * The metadata is searched with the configured spelling and with its upper and
	 * lower case variants, because databases store unquoted names differently.
	 * An exact spelling wins over a case-insensitive match.
	 *
	 * @param metadata database metadata
	 * @param catalog current catalog of the connection
	 * @param schema configured schema, or {@code null}
	 * @param name configured table name
	 * @return the matching table
	 * @throws SQLException if the metadata cannot be read
	 * @throws IllegalStateException if no or more than one table matches
	 */
	private static TableName findTable(DatabaseMetaData metadata, String catalog, String schema, String name) throws SQLException {
		String trimmedSchema = blank(schema) ? null : schema.trim();
		String trimmedName = name.trim();
		String escape = metadata.getSearchStringEscape();
		Set<TableName> exact = new LinkedHashSet<>();
		Set<TableName> similar = new LinkedHashSet<>();

		for (String spelling : new LinkedHashSet<>(List.of(trimmedName, trimmedName.toUpperCase(Locale.ROOT), trimmedName.toLowerCase(Locale.ROOT)))) {
			String schemaPattern = trimmedSchema == null ? null : escapePattern(trimmedSchema, escape);

			try (ResultSet tables = metadata.getTables(catalog, schemaPattern, escapePattern(spelling, escape), null)) {
				while (tables.next()) {
					TableName found = new TableName(tables.getString("TABLE_SCHEM"), tables.getString("TABLE_NAME"));

					// Pattern escapes are not supported by every driver, so check the names again
					if (!found.name().equalsIgnoreCase(trimmedName)
							|| (trimmedSchema != null && (found.schema() == null || !found.schema().equalsIgnoreCase(trimmedSchema)))) {
						continue;
					}

					boolean sameSpelling = found.name().equals(trimmedName) && (trimmedSchema == null || trimmedSchema.equals(found.schema()));
					(sameSpelling ? exact : similar).add(found);
				}
			}
		}

		Set<TableName> candidates = exact.isEmpty() ? similar : exact;

		if (candidates.isEmpty()) {
			throw new IllegalStateException("Source table " + (trimmedSchema == null ? "" : trimmedSchema + ".") + trimmedName
					+ " does not exist or is not visible to the database user.");
		}

		if (candidates.size() > 1) {
			throw new IllegalStateException("Source table name " + trimmedName + " is ambiguous (" + candidates
					+ "); configure source.schema.");
		}

		return candidates.iterator().next();
	}

	/**
	 * Reads all columns of a table.
	 *
	 * @param metadata database metadata
	 * @param table resolved table
	 * @param quote identifier quote string, empty if quoting is not supported
	 * @return columns by name, case-insensitive
	 * @throws SQLException if the metadata cannot be read
	 * @throws IllegalStateException if two column names differ only in case
	 */
	private static Map<String, Column> columns(DatabaseMetaData metadata, TableName table, String quote) throws SQLException {
		String escape = metadata.getSearchStringEscape();
		Map<String, Column> columns = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

		try (ResultSet result = metadata.getColumns(null, table.schema() == null ? null : escapePattern(table.schema(), escape),
				escapePattern(table.name(), escape), "%")) {
			while (result.next()) {
				// Skip columns of other tables whose names only matched the pattern
				if (!table.name().equals(result.getString("TABLE_NAME"))) {
					continue;
				}

				String name = result.getString("COLUMN_NAME");
				Column column = new Column(name, quote(name, quote), result.getInt("DATA_TYPE"), result.getString("TYPE_NAME"));

				if (columns.put(name, column) != null) {
					throw new IllegalStateException("Source table " + table + " has columns whose names differ only in case.");
				}
			}
		}

		return columns;
	}

	/**
	 * Logs a warning if the key column is not covered by a primary key or a
	 * single-column unique index.
	 *
	 * Rows with a duplicate key and the same progress value would be skipped,
	 * because the connector continues after the last processed key.
	 *
	 * @param metadata database metadata
	 * @param table resolved table
	 */
	private void warnIfKeyNotUnique(DatabaseMetaData metadata, TableName table) {
		try {
			Map<String, Integer> indexColumns = new TreeMap<>();

			// Count the columns of every unique index; only single-column indexes prove uniqueness
			try (ResultSet indexes = metadata.getIndexInfo(null, table.schema(), table.name(), true, true)) {
				while (indexes.next()) {
					String index = indexes.getString("INDEX_NAME");
					if (index != null) {
						indexColumns.merge(index, 1, Integer::sum);
					}
				}
			}

			try (ResultSet indexes = metadata.getIndexInfo(null, table.schema(), table.name(), true, true)) {
				while (indexes.next()) {
					String index = indexes.getString("INDEX_NAME");
					if (index != null && indexColumns.get(index) == 1 && key.name().equalsIgnoreCase(indexes.getString("COLUMN_NAME"))) {
						return;
					}
				}
			}

			log.warn("Key column {} is not declared unique; rows sharing a key and progress value may be skipped.", key.name());
		} catch (SQLException exception) {
			log.warn("Uniqueness of key column {} could not be checked: {}", key.name(), exception.getClass().getSimpleName());
		}
	}

	/**
	 * Escapes the wildcard characters of a metadata search pattern.
	 *
	 * @param value literal name
	 * @param escape escape string of the driver, may be {@code null}
	 * @return search pattern matching only the literal name
	 */
	private static String escapePattern(String value, String escape) {
		if (escape == null || escape.isEmpty()) {
			return value;
		}

		return value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
	}

	/**
	 * Quotes an identifier reported by the database.
	 *
	 * @param name identifier as stored in the database
	 * @param quote quote string, empty if quoting is not supported
	 * @return quoted identifier with embedded quotes doubled
	 */
	private static String quote(String name, String quote) {
		if (quote.isEmpty()) {
			return name;
		}

		return quote + name.replace(quote, quote + quote) + quote;
	}

	/**
	 * Returns an empty string for {@code null}.
	 *
	 * @param value value, may be {@code null}
	 * @return the value or an empty string
	 */
	private static String nullSafe(String value) {
		return value == null ? "" : value;
	}

	/**
	 * Checks whether a value is absent or contains only whitespace.
	 *
	 * @param value value to check
	 * @return {@code true} if the value is {@code null} or blank
	 */
	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	/**
	 * A column of the source table.
	 *
	 * @param name column name as stored in the database
	 * @param quoted quoted column name usable in SQL
	 * @param jdbcType JDBC type code from {@link Types}, or a driver-specific code
	 * @param typeName database type name, for example {@code datetime2}
	 */
	public record Column(String name, String quoted, int jdbcType, String typeName) { }

	/**
	 * A table name as stored in the database.
	 *
	 * @param schema schema, or {@code null} if the database has none
	 * @param name table name
	 */
	private record TableName(String schema, String name) {

		/**
		 * Returns the schema-qualified name for messages.
		 *
		 * @return schema-qualified name
		 */
		@Override
		public String toString() {
			return schema == null ? name : schema + "." + name;
		}
	}
}
