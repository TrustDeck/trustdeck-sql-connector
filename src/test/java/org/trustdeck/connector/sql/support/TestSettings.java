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

import java.nio.file.Path;
import java.time.Duration;

import org.trustdeck.connector.sql.config.ConnectorProperties;

/**
 * Builds complete, valid connector settings for tests.
 *
 * @author Armin Müller
 */
public final class TestSettings {

	/** Prevents instantiation of this utility class. */
	private TestSettings() { }

	/**
	 * Creates settings for a pseudonym import from the synthetic {@code PATIENTS} table.
	 *
	 * @param url JDBC URL of the source database
	 * @param username SQL login name
	 * @param password SQL login password
	 * @param state directory for the progress, dead-letter, and review files
	 * @param trustDeckUrl TrustDeck service URL
	 * @return valid settings
	 */
	public static ConnectorProperties pseudonymImport(String url, String username, String password, Path state, String trustDeckUrl) {
		ConnectorProperties properties = new ConnectorProperties();
		properties.setOperation("CREATE_PSEUDONYM");

		properties.getDatabase().setUrl(url);
		properties.getDatabase().setUsername(username);
		properties.getDatabase().setPassword(password);

		properties.getSource().setTable("patients");
		properties.getSource().setKeyColumn("id");
		properties.getSource().setPageSize(2);
		properties.getSource().setProgressFile(state.resolve("progress.json").toString());

		properties.getTrustdeck().setServiceUrl(trustDeckUrl);
		properties.getTrustdeck().setTokenEndpoint(trustDeckUrl + "/token");
		properties.getTrustdeck().setClientId("client");
		properties.getTrustdeck().setClientSecret("secret");
		properties.getTrustdeck().setProject("synthetic-project");

		properties.getMapping().getCommon().setDomainConstant("synthetic-domain");
		properties.getMapping().getCommon().setIdentifierColumn("case_number");
		properties.getMapping().getCommon().setIdTypeColumn("id_type");

		properties.getRecovery().setDltFile(state.resolve("dlt.jsonl").toString());
		properties.getRecovery().setReviewFile(state.resolve("review.jsonl").toString());
		properties.getRecovery().setInitialBackoff(Duration.ZERO);

		return properties;
	}
}
