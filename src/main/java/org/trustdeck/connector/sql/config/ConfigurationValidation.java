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
package org.trustdeck.connector.sql.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.trustdeck.connector.sql.database.SourceTable;
import org.trustdeck.connector.sql.database.SqlDialect;

/**
 * Validates the connector configuration and applies required normalization.
 *
 * Validation covers the run mode, the database connection, the source table
 * settings, TrustDeck connection settings, the local state files, statistics
 * configuration, operation-specific column mappings, entity field mappings,
 * timestamp settings, URLs, and JSON Pointer syntax. Everything that can be
 * checked without the database is checked here, so a misconfigured instance
 * stops before it connects anywhere.
 *
 * @author Armin Müller
 */
@Configuration
@EnableConfigurationProperties(ConnectorProperties.class)
public class ConfigurationValidation {

	/** Connector operations supported by this application. */
	private static final Set<String> OPERATIONS = Set.of(
			"CREATE_PSEUDONYM",
			"UPDATE_PSEUDONYM",
			"DELETE_PSEUDONYM",
			"CREATE_ENTITY",
			"UPDATE_ENTITY",
			"DELETE_ENTITY",
			"CREATE_ENTITY_AND_PSEUDONYM");

	/** Smallest connection timeout the connection pool accepts. */
	private static final Duration MINIMUM_CONNECTION_TIMEOUT = Duration.ofMillis(250);

	/**
	 * Validates the supplied connector configuration and applies normalized
	 * values where required.
	 *
	 * @param properties connector configuration to validate
	 * @throws IllegalArgumentException if a required setting is missing, invalid, or inconsistent with another setting
	 */
	public ConfigurationValidation(ConnectorProperties properties) {
		if (blank(properties.getOperation())) {
			throw new IllegalArgumentException("connector.operation is required.");
		}

		// Normalize the operation before applying operation-specific validation
		String operation = properties.getOperation().trim().toUpperCase(Locale.ROOT);
		if (!OPERATIONS.contains(operation)) {
			throw new IllegalArgumentException("connector.operation must be one of " + OPERATIONS);
		}

		properties.setOperation(operation);

		if (properties.getMode() == null) {
			throw new IllegalArgumentException("connector.mode must be ONE_SHOT or POLLING.");
		}

		validateDatabase(properties.getDatabase());
		validateSource(properties.getSource());

		requireHttpUrl(properties.getTrustdeck().getServiceUrl(), "trustdeck.service-url");
		requireHttpUrl(properties.getTrustdeck().getTokenEndpoint(), "trustdeck.token-endpoint");

		if (blank(properties.getTrustdeck().getProject())) {
			throw new IllegalArgumentException("trustdeck.project is required for the project-scoped client API.");
		}

		requirePositive(properties.getConnectTimeout(), "connect-timeout");
		requirePositive(properties.getReadTimeout(), "read-timeout");

		if (properties.getStatistics().getInterval() == null || properties.getStatistics().getInterval().isZero()
				|| properties.getStatistics().getInterval().isNegative()
				|| blank(properties.getStatistics().getOutputDirectory())
				|| properties.getStatistics().getMaxFileAge() == null
				|| properties.getStatistics().getMaxFileAge().isZero()
				|| properties.getStatistics().getMaxFileAge().isNegative()
				|| properties.getStatistics().getMaxFileSize() == null
				|| properties.getStatistics().getMaxFileSize().toBytes() <= 0) {
			throw new IllegalArgumentException("Statistics interval, maximum file age, and maximum file size must be positive; output directory must be set.");
		}

		// Retry and probe timing must be finite and usable
		if (properties.getRecovery().getInitialBackoff() == null || properties.getRecovery().getInitialBackoff().isNegative()) {
			throw new IllegalArgumentException("recovery.initial-backoff must not be negative.");
		}

		requirePositive(properties.getRecovery().getProbeInterval(), "recovery.probe-interval");

		// Progress, dead-letter, and review data must never share a file
		Set<Path> files = new HashSet<>();
		for (String file : List.of(nullSafe(properties.getSource().getProgressFile()), nullSafe(properties.getRecovery().getDltFile()),
				nullSafe(properties.getRecovery().getReviewFile()))) {
			if (blank(file)) {
				throw new IllegalArgumentException("source.progress-file, recovery.dlt-file, and recovery.review-file are required.");
			}

			if (!files.add(Path.of(file).toAbsolutePath().normalize())) {
				throw new IllegalArgumentException("source.progress-file, recovery.dlt-file, and recovery.review-file must be distinct.");
			}
		}

		validateMapping(operation, properties);
	}

	/**
	 * Validates the database connection settings.
	 *
	 * Either a complete JDBC URL or host and database name must be given. A URL is
	 * built here once to reject unsupported characters before any connection attempt.
	 *
	 * @param database database settings
	 * @throws IllegalArgumentException if the settings are incomplete or invalid
	 */
	private static void validateDatabase(ConnectorProperties.DatabaseProperties database) {
		if (blank(database.getUrl())) {
			if (blank(database.getHost()) || blank(database.getName())) {
				throw new IllegalArgumentException("database.url or database.host and database.name are required.");
			}

			SqlDialect.resolve(database.getDialect(), null).url(database.getHost().trim(), database.getPort(), database.getName().trim());
		} else {
			if (!database.getUrl().trim().startsWith("jdbc:")) {
				throw new IllegalArgumentException("database.url must be a JDBC URL starting with 'jdbc:'.");
			}

			SqlDialect.resolve(database.getDialect(), database.getUrl());
		}

		if (blank(database.getUsername()) || blank(database.getPassword())) {
			throw new IllegalArgumentException("database.username and database.password are required (SQL login).");
		}

		if (database.getPoolSize() < 1) {
			throw new IllegalArgumentException("database.pool-size must be at least 1.");
		}

		if (database.getConnectionTimeout() == null || database.getConnectionTimeout().compareTo(MINIMUM_CONNECTION_TIMEOUT) < 0) {
			throw new IllegalArgumentException("database.connection-timeout must be at least 250ms.");
		}

		requirePositive(database.getQueryTimeout(), "database.query-timeout");
	}

	/**
	 * Validates the source table settings.
	 *
	 * @param source source settings
	 * @throws IllegalArgumentException if the settings are incomplete or invalid
	 */
	private static void validateSource(ConnectorProperties.SourceProperties source) {
		if (blank(source.getTable()) || blank(source.getKeyColumn())) {
			throw new IllegalArgumentException("source.table and source.key-column are required.");
		}

		if (!blank(source.getWhere())) {
			SourceTable.validateFilter(source.getWhere());
		}

		if (source.getPageSize() < 1) {
			throw new IllegalArgumentException("source.page-size must be at least 1.");
		}

		requirePositive(source.getPollInterval(), "source.poll-interval");
	}

	/**
	 * Validates the column mappings required by the selected operation.
	 *
	 * @param operation normalized connector operation
	 * @param properties connector configuration
	 * @throws IllegalArgumentException if a required mapping is missing or a mapping is invalid
	 */
	private static void validateMapping(String operation, ConnectorProperties properties) {
		ConnectorProperties.CommonMappingProperties common = properties.getMapping().getCommon();
		ConnectorProperties.UpdatePseudonymMappingProperties update = properties.getMapping().getUpdatePseudonym();
		boolean pseudonym = operation.equals("CREATE_PSEUDONYM") || operation.equals("UPDATE_PSEUDONYM") || operation.equals("DELETE_PSEUDONYM");
		boolean entity = operation.contains("ENTITY");

		// Creation and update identify a pseudonym by identifier and identifier type
		if ((operation.equals("CREATE_PSEUDONYM") || operation.equals("UPDATE_PSEUDONYM"))
				&& (blank(common.getIdentifierColumn()) || blank(common.getIdTypeColumn()))) {
			throw new IllegalArgumentException("identifier-column and id-type-column are required for pseudonym create/update operations.");
		}

		// Deletion may identify the pseudonym directly or via identifier and ID type
		if (operation.equals("DELETE_PSEUDONYM") && blank(common.getPsnColumn())
				&& (blank(common.getIdentifierColumn()) || blank(common.getIdTypeColumn()))) {
			throw new IllegalArgumentException("delete pseudonym needs psn-column or identifier-column and id-type-column.");
		}

		if (operation.equals("UPDATE_PSEUDONYM") && (blank(update.getOldIdentifierColumn()) || blank(update.getOldIdTypeColumn()))) {
			throw new IllegalArgumentException("update pseudonym requires old-identifier-column and old-id-type-column.");
		}

		if ((pseudonym || operation.equals("CREATE_ENTITY_AND_PSEUDONYM")) && blank(common.getDomainConstant()) && blank(common.getDomainColumn())) {
			throw new IllegalArgumentException("domain-constant or domain-column is required for operations that create, update, or delete pseudonyms.");
		}

		if (entity) {
			// The entity type may be fixed globally or resolved from each row
			if (blank(properties.getTrustdeck().getEntityType()) && blank(common.getEntityTypeColumn())) {
				throw new IllegalArgumentException("trustdeck.entity-type or mapping.common.entity-type-column is required for entity operations.");
			}

			if ((operation.equals("UPDATE_ENTITY") || operation.equals("DELETE_ENTITY")) && blank(common.getEntityIdColumn())) {
				throw new IllegalArgumentException("entity-id-column is required for entity update and delete.");
			}

			for (ConnectorProperties.FieldMapping field : common.getEntityFields()) {
				if (field == null || blank(field.getTargetPath()) || (blank(field.getColumn()) && field.getConstant() == null)) {
					throw new IllegalArgumentException("Entity field mappings require a target-path and a column or constant.");
				}

				validatePointer(field.getTargetPath(), "entity field target-path");
			}
		}

		// Time stamp interpretation settings must be usable before the first row arrives
		try {
			ZoneId.of(common.getSourceTimezone());
		} catch (DateTimeException | NullPointerException exception) {
			throw new IllegalArgumentException("mapping.common.source-timezone must be a valid IANA zone.", exception);
		}

		if (!blank(common.getDatetimeFormat())) {
			try {
				DateTimeFormatter.ofPattern(common.getDatetimeFormat());
			} catch (IllegalArgumentException exception) {
				throw new IllegalArgumentException("mapping.common.datetime-format is not a valid pattern.", exception);
			}
		}
	}

	/**
	 * Tests whether text is absent or blank.
	 *
	 * @param text value to inspect
	 * @return {@code true} if the value is {@code null} or blank, otherwise {@code false}
	 */
	private static boolean blank(String text) {
		return text == null || text.isBlank();
	}

	/**
	 * Returns an empty string for {@code null}.
	 *
	 * @param text value, may be {@code null}
	 * @return the value or an empty string
	 */
	private static String nullSafe(String text) {
		return text == null ? "" : text;
	}

	/**
	 * Requires a duration to be present and positive.
	 *
	 * @param duration duration to check
	 * @param name configuration property name used in validation messages
	 * @throws IllegalArgumentException if the duration is missing, zero, or negative
	 */
	private static void requirePositive(Duration duration, String name) {
		if (duration == null || duration.isZero() || duration.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive.");
		}
	}

	/**
	 * Validates that a configuration value contains a usable HTTP or HTTPS URL.
	 * The URL must define either the {@code http} or {@code https} scheme and contain a host.
	 *
	 * @param value URL value to validate
	 * @param name configuration property name used in validation messages
	 * @throws IllegalArgumentException if the value is missing or is not a valid HTTP(S) URL
	 */
	private static void requireHttpUrl(String value, String name) {
		if (blank(value)) {
			throw new IllegalArgumentException(name + " must be an HTTP(S) URL.");
		}

		try {
			URI uri = URI.create(value);
			if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) {
				throw new IllegalArgumentException(name + " must be an HTTP(S) URL.");
			}
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(name + " must be an HTTP(S) URL.", exception);
		}
	}

	/**
	 * Validates the JSON Pointer syntax used by a target mapping.
	 *
	 * Empty path segments, trailing separators, and invalid tilde escape
	 * sequences are rejected. Valid JSON Pointer escapes are {@code ~0} and {@code ~1}.
	 *
	 * @param value JSON Pointer to validate
	 * @param name mapping name used in validation messages
	 * @throws IllegalArgumentException if the JSON Pointer contains an invalid structure or escape sequence
	 */
	private static void validatePointer(String value, String name) {
		if (value.startsWith("/") && (value.equals("/") || value.contains("//") || value.endsWith("/"))) {
			throw new IllegalArgumentException(name + " is not a valid non-empty JSON Pointer.");
		}

		if (value.contains("~") && !value.matches("(?:[^~]|~0|~1)*")) {
			throw new IllegalArgumentException(name + " contains an invalid JSON Pointer escape.");
		}
	}
}
