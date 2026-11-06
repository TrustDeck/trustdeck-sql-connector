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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties controlling the source database, the run mode,
 * TrustDeck access, column mappings, recovery handling, and statistics collection.
 *
 * Instances are populated through Spring property binding and validated before
 * the connector starts reading rows.
 *
 * @author Armin Müller
 */
@Data
@Validated
@ConfigurationProperties(prefix = "connector")
public class ConnectorProperties {

	/** Creates empty settings for Spring property binding. */
	public ConnectorProperties() { }

	/** Selected operation processed by this application instance. */
	@NotBlank
	private String operation;

	/** Whether the source table is imported once or polled continuously. */
	@NotNull
	private RunMode mode = RunMode.ONE_SHOT;

	/** Maximum records sent in one pseudonym create batch. */
	@Min(1)
	private int batchSize = 100;

	/** Maximum bytes sent in one pseudonym create batch. */
	@Min(1)
	private int batchBytes = 262_144;

	/** HTTP connection timeout. */
	@NotNull
	private Duration connectTimeout = Duration.ofSeconds(5);

	/** HTTP response read timeout. */
	@NotNull
	private Duration readTimeout = Duration.ofSeconds(30);

	/** Source database connection settings. */
	@Valid
	private DatabaseProperties database = new DatabaseProperties();

	/** Source table, key, progress, and paging settings. */
	@Valid
	private SourceProperties source = new SourceProperties();

	/** TrustDeck and token endpoint settings. */
	@Valid
	private TrustDeckProperties trustdeck = new TrustDeckProperties();

	/** Column-to-TrustDeck mapping settings. */
	@Valid
	private MappingProperties mapping = new MappingProperties();

	/** Recovery file and retry settings. */
	@Valid
	private RecoveryProperties recovery = new RecoveryProperties();

	/** Optional asynchronous runtime and backend statistics collection. */
	@Valid
	private StatisticsProperties statistics = new StatisticsProperties();

	/**
	 * Determines how the connector reads the source table.
	 */
	public enum RunMode {

		/** Reads all matching rows once, sends an optional notification, and exits. */
		ONE_SHOT,

		/** Reads all matching rows and then keeps polling for new rows. */
		POLLING
	}

	/**
	 * Source database connection settings.
	 *
	 * The connection is either given as a complete JDBC URL or built from a
	 * dialect, host, port, and database name. A complete URL takes precedence.
	 * Only SQL logins (username and password) are supported.
	 */
	@Data
	public static class DatabaseProperties {

		/** Creates empty database settings for property binding. */
		public DatabaseProperties() { }

		/** Complete JDBC URL; takes precedence over host, port, and name. */
		private String url;

		/** SQL dialect; detected from the JDBC URL when absent. */
		private String dialect;

		/** Database host used to build the JDBC URL. */
		private String host;

		/** Database port used to build the JDBC URL; the dialect default applies when absent. */
		private Integer port;

		/** Database name used to build the JDBC URL. */
		private String name;

		/** SQL login name. */
		@NotBlank
		private String username;

		/** SQL login password. */
		@NotBlank
		private String password;

		/** Optional JDBC driver class for drivers that do not register themselves. */
		private String driverClassName;

		/** Additional JDBC connection properties, for example TLS truststore settings. */
		private Map<String, String> connectionProperties = new LinkedHashMap<>();

		/** Maximum number of pooled database connections. */
		@Min(1)
		private int poolSize = 2;

		/** Maximum time to wait for a database connection. */
		@NotNull
		private Duration connectionTimeout = Duration.ofSeconds(30);

		/** Maximum execution time of one source query. */
		@NotNull
		private Duration queryTimeout = Duration.ofSeconds(60);
	}

	/**
	 * Source table settings.
	 *
	 * Rows are read in pages ordered by the progress column and the key column.
	 * Only a table or view name, column names, and an optional filter condition
	 * are configurable; custom SELECT statements are not supported.
	 */
	@Data
	public static class SourceProperties {

		/** Creates empty source settings for property binding. */
		public SourceProperties() { }

		/** Optional schema containing the source table. */
		private String schema;

		/** Source table or view. */
		@NotBlank
		private String table;

		/** Column that uniquely identifies a row. */
		@NotBlank
		private String keyColumn;

		/** Column whose values only increase for new rows; defaults to the key column. */
		private String progressColumn;

		/** Optional filter condition appended to the WHERE clause. */
		private String where;

		/** Maximum number of rows read with one query. */
		@Min(1)
		private int pageSize = 500;

		/** Delay between two polls once all rows were read in polling mode. */
		@NotNull
		private Duration pollInterval = Duration.ofSeconds(60);

		/** File in which the position of the last completed row is stored. */
		@NotBlank
		private String progressFile = "state/progress.json";
	}

	/**
	 * TrustDeck connection settings and optional fixed resource targets.
	 *
	 * Contains the service and token endpoints, OAuth client credentials, and
	 * optional project and entity type values used by operations that do not
	 * resolve these targets from the source rows.
	 */
	@Data
	public static class TrustDeckProperties {

		/** Creates empty TrustDeck settings for property binding. */
		public TrustDeckProperties() { }

		/** TrustDeck service base URL. */
		@NotBlank
		private String serviceUrl;

		/** OAuth token endpoint. */
		@NotBlank
		private String tokenEndpoint;

		/** OAuth confidential client ID. */
		@NotBlank
		private String clientId;

		/** OAuth confidential client secret. */
		@NotBlank
		private String clientSecret;

		/** Fixed project for all operations. */
		private String project;

		/** Fixed entity type fallback. */
		private String entityType;
	}

	/**
	 * Groups all column-to-TrustDeck mapping configurations.
	 *
	 * Common mappings are shared between operations, while update-specific mappings
	 * define how existing pseudonym values are located and replaced.
	 */
	@Data
	public static class MappingProperties {

		/** Creates empty mapping settings for property binding. */
		public MappingProperties() { }

		/** Mappings shared by the selected operation and entity operations. */
		@Valid
		private CommonMappingProperties common = new CommonMappingProperties();

		/** Selectors and replacement fields used only by pseudonym updates. */
		@Valid
		private UpdatePseudonymMappingProperties updatePseudonym = new UpdatePseudonymMappingProperties();
	}

	/**
	 * Mapping settings shared by pseudonym and entity operations.
	 *
	 * Defines source columns, optional constant values, entity field mappings,
	 * timestamp conversion settings, and optional row-specific TrustDeck targets.
	 */
	@Data
	public static class CommonMappingProperties {

		/** Creates common mappings with UTC as the default source timezone. */
		public CommonMappingProperties() { }

		/** Constant domain override. */
		private String domainConstant;

		/** Column containing a domain. */
		private String domainColumn;

		/** Column containing the pseudonym identifier. */
		private String identifierColumn;

		/** Column containing the identifier type. */
		private String idTypeColumn;

		/** Column containing an imported pseudonym value. */
		private String psnColumn;

		/** Optional column containing a pseudonym validity duration. */
		private String validityTimeColumn;

		/** Column containing the validity start. */
		private String validFromColumn;

		/** Column containing the validity end. */
		private String validToColumn;

		/** Optional column containing source provenance. */
		private String usernameColumn;

		/** Optional column containing a project entity type. */
		private String entityTypeColumn;

		/** Optional column containing a TrustDeck entity UUID. */
		private String entityIdColumn;

		/** Entity data mappings shared by entity create, update, and combined operations. */
		private List<FieldMapping> entityFields = new ArrayList<>();

		/** IANA zone for timestamps without an offset. */
		@NotBlank
		private String sourceTimezone = "UTC";

		/** Strict format for timestamps stored as text without an offset. */
		private String datetimeFormat;
	}

	/**
	 * Mapping settings specific to pseudonym update operations.
	 *
	 * Defines how the existing pseudonym is identified and which source values
	 * should replace its identifier, type, value, or domain.
	 */
	@Data
	public static class UpdatePseudonymMappingProperties {

		/** Creates empty pseudonym update mappings for property binding. */
		public UpdatePseudonymMappingProperties() { }

		/** Column containing the old pseudonym identifier. */
		private String oldIdentifierColumn;

		/** Column containing the old pseudonym type. */
		private String oldIdTypeColumn;

		/** Column containing the old pseudonym value. */
		private String oldPsnColumn;

		/** Column containing a replacement pseudonym identifier. */
		private String newIdentifierColumn;

		/** Column containing a replacement pseudonym type. */
		private String newIdTypeColumn;

		/** Column containing a replacement pseudonym value. */
		private String newPsnColumn;

		/** Column containing the old pseudonym domain. */
		private String oldDomainColumn;

		/** Column containing a replacement pseudonym domain. */
		private String newDomainColumn;
	}

	/**
	 * Defines one column-to-target mapping for entity data.
	 *
	 * A mapping reads a value from the source row or uses a configured constant
	 * and writes the resulting value to the configured target JSON Pointer.
	 */
	@Data
	public static class FieldMapping {

		/** Creates an empty field mapping for property binding. */
		public FieldMapping() { }

		/** Source column; may be omitted when a constant is configured. */
		private String column;

		/** Target JSON Pointer within entity data. */
		@NotBlank
		private String targetPath;

		/** Whether the mapped column must be non-null. */
		private boolean required;

		/** Optional constant value taking precedence over the column. */
		private String constant;
	}

	/**
	 * Configures the recovery files, retries, and availability handling.
	 *
	 * Rows that cannot be processed are recorded in a dead-letter file; rows that
	 * need a manual decision are recorded in a review file.
	 */
	@Data
	public static class RecoveryProperties {

		/** Creates default recovery settings for property binding. */
		public RecoveryProperties() { }

		/** JSON Lines file collecting rows that failed definitively. */
		@NotBlank
		private String dltFile = "state/dlt.jsonl";

		/** JSON Lines file collecting rows that need a manual review. */
		@NotBlank
		private String reviewFile = "state/review.jsonl";

		/** Maximum transient attempts. */
		@Min(0)
		private int maxAttempts = 3;

		/** Initial retry delay. */
		@NotNull
		private Duration initialBackoff = Duration.ofSeconds(1);

		/** Availability probe interval while processing is paused. */
		@NotNull
		private Duration probeInterval = Duration.ofSeconds(15);
	}

	/**
	 * Configures optional runtime and TrustDeck backend statistics collection.
	 *
	 * Controls whether statistics are collected, how frequently backend statistics
	 * are requested, and how active statistics files are rotated.
	 */
	@Data
	public static class StatisticsProperties {

		/** Creates disabled statistics settings for property binding. */
		public StatisticsProperties() { }

		/** Whether statistics should be collected during a connector-run. */
		private boolean enabled;

		/** Delay between backend statistics requests. */
		@NotNull
		private Duration interval = Duration.ofSeconds(10);

		/** Directory in which completed statistics segments are written. */
		@NotBlank
		private String outputDirectory = "statistics";

		/** Maximum elapsed age of one statistics segment. */
		@NotNull
		private Duration maxFileAge = Duration.ofDays(1);

		/** Maximum configured size of one statistics segment. */
		@NotNull
		private DataSize maxFileSize = DataSize.ofMegabytes(256);
	}
}
