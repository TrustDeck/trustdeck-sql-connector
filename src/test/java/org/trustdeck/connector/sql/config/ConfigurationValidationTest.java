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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.trustdeck.connector.sql.support.TestSettings;

/**
 * Verifies startup validation of the connector configuration.
 *
 * @author Armin Müller
 */
class ConfigurationValidationTest {

	/** Temporary directory for state files. */
	@TempDir
	Path state;

	/** A complete pseudonym import configuration is accepted and the operation normalized. */
	@Test
	void acceptsCompleteConfiguration() {
		ConnectorProperties properties = valid();
		properties.setOperation("create_pseudonym");

		assertDoesNotThrow(() -> new ConfigurationValidation(properties));
		assertEquals("CREATE_PSEUDONYM", properties.getOperation());
	}

	/** A connection can be given as dialect, host, and name instead of a URL. */
	@Test
	void acceptsConnectionParts() {
		ConnectorProperties properties = valid();
		properties.getDatabase().setUrl(null);
		properties.getDatabase().setDialect("SQLSERVER");
		properties.getDatabase().setHost("db.example.org");
		properties.getDatabase().setName("clinic");

		assertDoesNotThrow(() -> new ConfigurationValidation(properties));
	}

	/** Missing or unsafe connection settings stop the startup. */
	@Test
	void rejectsIncompleteConnection() {
		ConnectorProperties withoutTarget = valid();
		withoutTarget.getDatabase().setUrl(null);
		assertThrows(IllegalArgumentException.class, () -> new ConfigurationValidation(withoutTarget));

		ConnectorProperties unsafeHost = valid();
		unsafeHost.getDatabase().setUrl(null);
		unsafeHost.getDatabase().setDialect("SQLSERVER");
		unsafeHost.getDatabase().setHost("db;trustServerCertificate=true");
		unsafeHost.getDatabase().setName("clinic");
		assertThrows(IllegalArgumentException.class, () -> new ConfigurationValidation(unsafeHost));

		ConnectorProperties noPassword = valid();
		noPassword.getDatabase().setPassword(" ");
		assertThrows(IllegalArgumentException.class, () -> new ConfigurationValidation(noPassword));

		ConnectorProperties notJdbc = valid();
		notJdbc.getDatabase().setUrl("sqlserver://db");
		assertThrows(IllegalArgumentException.class, () -> new ConfigurationValidation(notJdbc));
	}

	/** Filters that could end the statement or escape their parentheses are rejected. */
	@Test
	void rejectsUnsafeFilters() {
		for (String filter : new String[] { "status = 1; DROP TABLE patients", "status = 1 -- comment", "1 = 1) OR (1 = 1",
				"name = 'unterminated", "/* hidden */ 1 = 1" }) {
			ConnectorProperties properties = valid();
			properties.getSource().setWhere(filter);

			assertThrows(IllegalArgumentException.class, () -> new ConfigurationValidation(properties), filter);
		}

		ConnectorProperties quoted = valid();
		quoted.getSource().setWhere("status = 'a)b' AND (kind IN ('x', 'y'))");
		assertDoesNotThrow(() -> new ConfigurationValidation(quoted));
	}

	/** Progress, dead-letter, and review data must use different files. */
	@Test
	void rejectsSharedStateFiles() {
		ConnectorProperties properties = valid();
		properties.getRecovery().setReviewFile(properties.getRecovery().getDltFile());

		assertThrows(IllegalArgumentException.class, () -> new ConfigurationValidation(properties));
	}

	/** Every operation requires its own mappings at startup instead of failing row by row. */
	@Test
	void requiresOperationSpecificMappings() {
		ConnectorProperties noDomain = valid();
		noDomain.getMapping().getCommon().setDomainConstant(null);
		assertThrows(IllegalArgumentException.class, () -> new ConfigurationValidation(noDomain));

		ConnectorProperties noIdType = valid();
		noIdType.getMapping().getCommon().setIdTypeColumn(null);
		assertThrows(IllegalArgumentException.class, () -> new ConfigurationValidation(noIdType));

		ConnectorProperties update = valid();
		update.setOperation("UPDATE_PSEUDONYM");
		assertThrows(IllegalArgumentException.class, () -> new ConfigurationValidation(update));

		ConnectorProperties deleteEntity = valid();
		deleteEntity.setOperation("DELETE_ENTITY");
		deleteEntity.getTrustdeck().setEntityType("patient");
		assertThrows(IllegalArgumentException.class, () -> new ConfigurationValidation(deleteEntity));
		deleteEntity.getMapping().getCommon().setEntityIdColumn("trustdeck_id");
		assertDoesNotThrow(() -> new ConfigurationValidation(deleteEntity));
	}

	/** Entity fields need a target and either a column or a constant. */
	@Test
	void validatesEntityFields() {
		ConnectorProperties properties = valid();
		properties.setOperation("CREATE_ENTITY");
		properties.getTrustdeck().setEntityType("patient");

		ConnectorProperties.FieldMapping field = new ConnectorProperties.FieldMapping();
		field.setTargetPath("/caseNumber");
		properties.getMapping().getCommon().getEntityFields().add(field);
		assertThrows(IllegalArgumentException.class, () -> new ConfigurationValidation(properties));

		field.setColumn("case_number");
		assertDoesNotThrow(() -> new ConfigurationValidation(properties));

		field.setTargetPath("/bad~2escape");
		assertThrows(IllegalArgumentException.class, () -> new ConfigurationValidation(properties));
	}

	/** Time stamp settings are checked before the first row is read. */
	@Test
	void validatesTimestampSettings() {
		ConnectorProperties zone = valid();
		zone.getMapping().getCommon().setSourceTimezone("Mars/Olympus");
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new ConfigurationValidation(zone));
		assertTrue(failure.getMessage().contains("source-timezone"));

		ConnectorProperties format = valid();
		format.getMapping().getCommon().setDatetimeFormat("uuuu-MM-dd {");
		assertThrows(IllegalArgumentException.class, () -> new ConfigurationValidation(format));
	}

	/**
	 * Creates a complete configuration.
	 *
	 * @return valid settings
	 */
	private ConnectorProperties valid() {
		return TestSettings.pseudonymImport("jdbc:sqlserver://db:1433;databaseName=clinic", "reader", "secret", state,
				"https://trustdeck.example.org");
	}
}
