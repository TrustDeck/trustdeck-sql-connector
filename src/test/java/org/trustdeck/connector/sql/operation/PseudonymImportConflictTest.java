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

package org.trustdeck.connector.sql.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.trustdeck.client.TrustDeckClient;
import org.trustdeck.client.exception.TrustDeckResponseException;
import org.trustdeck.client.model.Pseudonym;
import org.trustdeck.connector.sql.config.ConnectorProperties;
import org.trustdeck.connector.sql.mapping.MappedMessage;

/**
 * Verifies how a body-less 422 on an imported pseudonym is explained by
 * looking up the existing mapping, mirroring TrustDeck's create semantics.
 *
 * @author Armin Müller
 */
class PseudonymImportConflictTest {

	/** Stored pseudonym identical to the imported one. */
	private static final String IMPORTED = "{\"identifierItem\":{\"identifier\":\"id-1\",\"idType\":\"patient_number\"},\"psn\":\"psn-1\"}";
	/** Stored pseudonym assigned to a different identifier. */
	private static final String OTHER_IDENTIFIER = "{\"identifierItem\":{\"identifier\":\"id-2\",\"idType\":\"patient_number\"},\"psn\":\"psn-1\"}";
	/** Stored pseudonym with a different value for the same identifier. */
	private static final String OTHER_PSN = "{\"identifierItem\":{\"identifier\":\"id-1\",\"idType\":\"patient_number\"},\"psn\":\"psn-9\"}";

	/** Controlled HTTP server standing in for TrustDeck. */
	private HttpServer server;
	/** Captured HTTP method and path pairs. */
	private final List<String> requests = new CopyOnWriteArrayList<>();

	/** Status returned for the pseudonym creation. */
	private int createStatus = 422;
	/** Status returned for the lookup by pseudonym value. */
	private int byPsnStatus = 404;
	/** Body returned for the lookup by pseudonym value. */
	private String byPsnBody;
	/** Status returned for the lookup by identifier. */
	private int byIdentifierStatus = 404;
	/** Body returned for the lookup by identifier. */
	private String byIdentifierBody;

	/**
	 * Starts the controlled server.
	 *
	 * @throws IOException if the server cannot start
	 */
	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", this::respond);
		server.start();
	}

	/** Stops the controlled server. */
	@AfterEach
	void stop() {
		server.stop(0);
	}

	/** The same identifier already holds the imported pseudonym: the import counts as done. */
	@Test
	void identicalExistingMappingIsSuccess() {
		byPsnStatus = 200;
		byPsnBody = IMPORTED;

		Object result = service().execute(message());

		assertEquals("psn-1", ((Pseudonym) result).getPsn());
		assertEquals(2, requests.size());
	}

	/** The pseudonym belongs to a different identifier. */
	@Test
	void pseudonymAssignedElsewhereIsConflict() {
		byPsnStatus = 200;
		byPsnBody = OTHER_IDENTIFIER;

		PseudonymConflictException conflict = assertThrows(PseudonymConflictException.class, () -> service().execute(message()));

		assertEquals(PseudonymConflictException.Reason.PSEUDONYM_ASSIGNED_ELSEWHERE, conflict.getReason());
		assertEquals(422, ((TrustDeckResponseException) conflict.getCause()).getStatusCode());
	}

	/** The identifier already holds a different pseudonym and the domain allows only one. */
	@Test
	void identifierWithOtherPseudonymIsConflict() {
		byIdentifierStatus = 200;
		byIdentifierBody = OTHER_PSN;

		PseudonymConflictException conflict = assertThrows(PseudonymConflictException.class, () -> service().execute(message()));

		assertEquals(PseudonymConflictException.Reason.IDENTIFIER_HAS_OTHER_PSEUDONYM, conflict.getReason());
	}

	/** Nothing in the domain explains the rejection, e.g. the pair exists in another domain: the 422 is kept. */
	@Test
	void unexplainedRejectionIsRethrown() {
		TrustDeckResponseException failure = assertThrows(TrustDeckResponseException.class, () -> service().execute(message()));

		assertEquals(422, failure.getStatusCode());
		assertEquals(3, requests.size());
	}

	/** Without read permission the reason stays unknown; the original 422 is kept rather than pausing. */
	@Test
	void missingReadPermissionKeepsOriginalRejection() {
		byPsnStatus = 403;

		TrustDeckResponseException failure = assertThrows(TrustDeckResponseException.class, () -> service().execute(message()));

		assertEquals(422, failure.getStatusCode());
	}

	/** Other statuses are not explained by lookups. */
	@Test
	void otherStatusesAreNotLookedUp() {
		createStatus = 400;

		TrustDeckResponseException failure = assertThrows(TrustDeckResponseException.class, () -> service().execute(message()));

		assertEquals(400, failure.getStatusCode());
		assertEquals(1, requests.size());
	}

	/** A lookup failing for infrastructure reasons is not hidden behind the 422. */
	@Test
	void lookupOutagePropagates() {
		byPsnStatus = 503;

		TrustDeckResponseException failure = assertThrows(TrustDeckResponseException.class, () -> service().execute(message()));

		assertEquals(503, failure.getStatusCode());
	}

	/** An exact duplicate answered with 200 needs no lookup. */
	@Test
	void duplicateAnsweredWithOkNeedsNoLookup() {
		createStatus = 200;

		Object result = service().execute(message());

		assertSame(Pseudonym.class, result.getClass());
		assertEquals(1, requests.size());
	}

	/**
	 * Creates the operation service against the controlled server.
	 *
	 * @return operation service
	 */
	private OperationService service() {
		ConnectorProperties properties = new ConnectorProperties();
		properties.setOperation("CREATE_PSEUDONYM");
		properties.getTrustdeck().setProject("synthetic-project");
		TrustDeckClient client = new TrustDeckClient("http://localhost:" + server.getAddress().getPort(), () -> "token");
		return new OperationService(client, properties);
	}

	/**
	 * Creates the imported pseudonym message.
	 *
	 * @return mapped message
	 */
	private MappedMessage message() {
		return new MappedMessage("datalake", "id-1", "patient_number", "psn-1", null, null, null,
				null, null, null, null, null, null, null, null, null, null, null, null);
	}

	/**
	 * Answers the creation and the two lookups.
	 *
	 * @param exchange HTTP exchange
	 * @throws IOException if the response cannot be written
	 */
	private void respond(HttpExchange exchange) throws IOException {
		exchange.getRequestBody().readAllBytes();
		String query = exchange.getRequestURI().getRawQuery();
		requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath() + (query == null ? "" : "?" + query));

		int status;
		String body;
		if ("POST".equals(exchange.getRequestMethod())) {
			status = createStatus;
			body = createStatus < 300 ? IMPORTED : null;
		} else if (query != null && query.contains("psn=")) {
			status = byPsnStatus;
			body = byPsnBody;
		} else {
			status = byIdentifierStatus;
			body = byIdentifierBody;
		}

		if (body == null || status >= 300) {
			exchange.sendResponseHeaders(status, -1);
		} else {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
		}
		exchange.close();
	}
}
