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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.trustdeck.connector.sql.config.ConnectorProperties;

/**
 * Creates the SQL dialect and the pooled, read-only source database connection.
 *
 * @author Armin Müller
 */
@Configuration
@DependsOn("configurationValidation")
public class DatabaseConfiguration {

	/** Creates the database bean configuration. */
	public DatabaseConfiguration() { }

	/**
	 * Resolves the configured or URL-derived SQL dialect.
	 *
	 * @param properties connector settings
	 * @return SQL dialect of the source database
	 */
	@Bean
	public SqlDialect sqlDialect(ConnectorProperties properties) {
		ConnectorProperties.DatabaseProperties database = properties.getDatabase();

		return SqlDialect.resolve(database.getDialect(), database.getUrl());
	}

	/**
	 * Creates the connection pool for the source database.
	 *
	 * Connections are marked read-only, because the connector never writes to
	 * the source. Whether a driver enforces this differs, so the SQL login
	 * should only be granted SELECT permission on the source table. The pool
	 * fails on startup if no connection can be opened.
	 *
	 * @param properties connector settings
	 * @param dialect SQL dialect of the source database
	 * @return source data source
	 */
	@Bean(destroyMethod = "close")
	public HikariDataSource dataSource(ConnectorProperties properties, SqlDialect dialect) {
		ConnectorProperties.DatabaseProperties database = properties.getDatabase();
		HikariConfig config = new HikariConfig();

		// A complete URL wins over the URL built from its parts
		String url = database.getUrl() == null || database.getUrl().isBlank()
				? dialect.url(database.getHost(), database.getPort(), database.getName())
				: database.getUrl().trim();

		config.setPoolName("trustdeck-sql-source");
		config.setJdbcUrl(url);
		config.setUsername(database.getUsername());
		config.setPassword(database.getPassword());
		config.setReadOnly(true);
		config.setAutoCommit(true);
		config.setMaximumPoolSize(database.getPoolSize());
		config.setMinimumIdle(1);
		config.setConnectionTimeout(database.getConnectionTimeout().toMillis());

		if (database.getDriverClassName() != null && !database.getDriverClassName().isBlank()) {
			config.setDriverClassName(database.getDriverClassName().trim());
		}

		// Driver-specific settings such as truststores are passed through unchanged
		database.getConnectionProperties().forEach(config::addDataSourceProperty);

		return new HikariDataSource(config);
	}
}
