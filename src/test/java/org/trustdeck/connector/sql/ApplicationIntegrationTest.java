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
package org.trustdeck.connector.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.trustdeck.connector.sql.support.ConnectorApplication;
import org.trustdeck.connector.sql.support.FakeTrustDeck;
import org.trustdeck.connector.sql.support.H2Database;

/**
 * Runs the complete application against an in-memory database and a fake TrustDeck.
 *
 * @author Armin Müller
 */
class ApplicationIntegrationTest {

	/** Temporary directory for state files. */
	@TempDir
	Path state;

	/**
	 * Valid rows are imported, an invalid row is dead-lettered, new rows are picked up,
	 * and the health probes report readiness.
	 *
	 * @throws Exception if the application cannot be started or queried
	 */
	@Test
	void importsRowsAndPollsForNewOnes() throws Exception {
		String url = H2Database.create();
		H2Database.execute(url, "INSERT INTO PATIENTS (ID, CASE_NUMBER, ID_TYPE) VALUES (1, '0001', 'case'), (2, NULL, 'case'), (3, '0003', 'case')");

		try (FakeTrustDeck trustDeck = new FakeTrustDeck()) {
			Map<String, Object> settings = ConnectorApplication.settings(url, "sa", "sa", state, trustDeck);

			try (ConfigurableApplicationContext context = ConnectorApplication.start(settings)) {
				ConnectorApplication.await(Duration.ofSeconds(30), () -> "3".equals(ConnectorApplication.progressKey(state)));

				assertEquals(2, creations(trustDeck));
				assertEquals(1, Files.readAllLines(state.resolve("dlt.jsonl")).size());

				// Rows added later are picked up by the next poll
				H2Database.execute(url, "INSERT INTO PATIENTS (ID, CASE_NUMBER, ID_TYPE) VALUES (4, '0004', 'case')");
				ConnectorApplication.await(Duration.ofSeconds(30), () -> "4".equals(ConnectorApplication.progressKey(state)));
				assertEquals(3, creations(trustDeck));

				int port = ((WebServerApplicationContext) context).getWebServer().getPort();
				HttpResponse<String> readiness = HttpClient.newHttpClient().send(
						HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness")).build(),
						HttpResponse.BodyHandlers.ofString());
				assertEquals(200, readiness.statusCode());
				assertTrue(readiness.body().contains("UP"));
			}
		}
	}

	/**
	 * Counts single pseudonym creations received by the fake TrustDeck.
	 *
	 * @param trustDeck fake TrustDeck
	 * @return number of pseudonym creations
	 */
	static long creations(FakeTrustDeck trustDeck) {
		return trustDeck.requests().stream().filter(request -> request.equals("POST /api/domains/synthetic-domain/pseudonyms")).count();
	}
}
