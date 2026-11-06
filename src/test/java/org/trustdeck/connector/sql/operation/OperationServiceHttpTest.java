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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.trustdeck.client.TrustDeckClient;
import org.trustdeck.client.exception.TrustDeckResponseException;
import org.trustdeck.connector.sql.config.ConnectorProperties;
import org.trustdeck.connector.sql.mapping.MappedMessage;

/** Verifies operation dispatch and wire calls against a controlled HTTP server. */
class OperationServiceHttpTest {

    /** Controlled HTTP server. */
    private HttpServer server;

    /** Connector settings. */
    private ConnectorProperties properties;

    /** Captured HTTP method/path pairs. */
    private final List<String> requestLog = new CopyOnWriteArrayList<>();

    /** Captured request bodies. */
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();

    /** Status returned by the synthetic endpoint for the next operation. */
    private int responseStatus;

    /** Makes domain-scoped GET return one pseudonym for selector lookup tests. */
    private boolean lookupResponse;

    /** Status returned for pseudonym creation only; zero uses the default status. */
    private int responseStatusForPseudonymCreate;

    /**
     * Starts the controlled server.
     *
     * @throws IOException when the server cannot start
     */
    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::respond);
        server.start();
        properties = new ConnectorProperties();
        properties.setOperation("CREATE_PSEUDONYM");
        properties.getTrustdeck().setServiceUrl("http://localhost:" + server.getAddress().getPort());
        properties.getTrustdeck().setProject("synthetic-project");
        properties.getTrustdeck().setEntityType("synthetic-type");
        responseStatus = 0;
        lookupResponse = false;
        responseStatusForPseudonymCreate = 0;
    }

    /** Stops the controlled server. */
    @AfterEach
    void stop() {
        server.stop(0);
    }

    /**
     * Verifies create serializes the imported pseudonym and accepts HTTP 201.
     *
     * @throws IOException if the captured body cannot be parsed
     */
    @Test
    void createsPseudonymThroughClient() throws IOException {
        OperationService service = new OperationService(client(), properties);
        Object result = service.execute(message());

        assertEquals("012-000000009-2", ((org.trustdeck.client.model.Pseudonym) result).getPsn());
        assertEquals("POST /api/domains/source%2Fdomain/pseudonyms?omitPrefix=false", requestLog.get(0));
        com.fasterxml.jackson.databind.JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(requestBodies.get(0));
        assertEquals("0123456789", body.at("/identifierItem/identifier").asText());
        assertEquals("case_number", body.at("/identifierItem/idType").asText());
        assertEquals("012-000000009-2", body.get("psn").asText());
        assertTrue(body.get("validFrom").isArray());
        assertEquals(2025, body.get("validFrom").get(0).asInt());
        assertEquals(10, body.get("validFrom").get(1).asInt());
        assertEquals(9, body.get("validFrom").get(2).asInt());
        assertTrue(body.get("validTo") == null);
    }

    /**
     * Verifies an optional validity duration is forwarded without changing the normal create contract.
     *
     * @throws IOException if the captured body cannot be parsed
     */
    @Test
    void forwardsOptionalValidityTime() throws IOException {
        MappedMessage mapped = new MappedMessage("source/domain", "0123456789", "case_number",
                "012-000000009-2", "1d", null, null, null, "synthetic-type", null,
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(), null, null, null,
                null, null, null, null, null);

        new OperationService(client(), properties).execute(mapped);

        com.fasterxml.jackson.databind.JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(requestBodies.get(0));
        assertEquals("1d", body.get("validityTime").asText());
    }

    /** Preserves an ordinary HTTP conflict as the client response exception. */
    @Test
    void preservesOrdinaryConflictResponse() {
        responseStatus = 409;
        TrustDeckResponseException exception = assertThrows(TrustDeckResponseException.class,
                () -> new OperationService(client(), properties).execute(message()));

        assertEquals(409, exception.getStatusCode());
    }

    /** Verifies entity creation uses the arbitrary mapped JSON object. */
    @Test
    void createsEntityThroughClient() {
        properties.setOperation("CREATE_ENTITY");
        OperationService service = new OperationService(client(), properties);

        Object result = service.execute(message());

        assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"),
                ((org.trustdeck.client.model.Entity) result).getTrustdeckID());
        assertEquals("POST /api/projects/synthetic-project/entities/synthetic-type", requestLog.get(0));
        assertEquals("0123456789", body(0).at("/data/caseNumber").asText());
        assertEquals(1, body(0).size());
    }

    /** Verifies entity deletion uses the exact UUID endpoint and HTTP 204. */
    @Test
    void deletesEntityThroughClient() {
        properties.setOperation("DELETE_ENTITY");
        OperationService service = new OperationService(client(), properties);

        assertEquals(Boolean.TRUE, service.execute(message()));
        assertEquals("DELETE /api/projects/synthetic-project/entities/synthetic-type/11111111-1111-1111-1111-111111111111",
                requestLog.get(0));
        assertEquals(0, body(0).size());
    }

    /** Verifies standard pseudonym update dispatch and replacement selectors. */
    @Test
    void updatesPseudonymThroughClient() {
        properties.setOperation("UPDATE_PSEUDONYM");
        assertNotNull(new OperationService(client(), properties).execute(standardUpdateMessage()));
        assertEquals("PUT /api/domains/source%2Fdomain/pseudonyms", requestLog.get(0));
        assertEquals("old", body(0).at("/oldIdentifierItem/identifier").asText());
        assertEquals("old-psn", body(0).at("/oldPsn").asText());
        assertTrue(body(0).get("newIdentifierItem") == null);
        assertTrue(body(0).get("newPsn") == null);
        assertTrue(body(0).get("validTo") == null);
    }

    /** Verifies identifier/value replacement selects the complete-update endpoint without regeneration. */
    @Test
    void updatesPseudonymCompletelyThroughClient() {
        properties.setOperation("UPDATE_PSEUDONYM");
        assertNotNull(new OperationService(client(), properties).execute(message()));
        assertEquals("PUT /api/domains/source%2Fdomain/pseudonyms/complete?regeneratePseudonym=false", requestLog.get(0));
        assertEquals("old", body(0).at("/oldIdentifierItem/identifier").asText());
        assertEquals("new", body(0).at("/newIdentifierItem/identifier").asText());
        assertEquals("new-psn", body(0).at("/newPsn").asText());
        assertEquals("target/domain", body(0).at("/newDomainName").asText());
    }

    /** Verifies single-value pseudonym deletion rather than domain-wide deletion. */
    @Test
    void deletesPseudonymThroughClient() {
        properties.setOperation("DELETE_PSEUDONYM");
        assertEquals(Boolean.TRUE, new OperationService(client(), properties).execute(message()));
        assertEquals("DELETE /api/domains/source%2Fdomain/pseudonyms?psn=012-000000009-2", requestLog.get(0));
        assertEquals(0, body(0).size());
    }

    /** Verifies identifier and id-type lookup precedes one exact pseudonym deletion. */
    @Test
    void looksUpAndDeletesOnePseudonymByIdentifier() {
        lookupResponse = true;
        properties.setOperation("DELETE_PSEUDONYM");
        MappedMessage selected = new MappedMessage("source/domain", "0123456789", "case_number", null, null, null,
                null, null, "synthetic-type", null, new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                null, null, null, null, null, null, null, null);

        assertEquals(Boolean.TRUE, new OperationService(client(), properties).execute(selected));
        assertTrue(requestLog.get(0).equals("GET /api/domains/source%2Fdomain/pseudonyms?id=0123456789&idType=case_number")
                || requestLog.get(0).equals("GET /api/domains/source%2Fdomain/pseudonyms?idType=case_number&id=0123456789"));
        assertEquals("DELETE /api/domains/source%2Fdomain/pseudonyms?psn=012-000000009-2", requestLog.get(1));
        assertEquals(0, body(1).size());
    }

    /** Verifies entity replacement dispatch with the mapped TrustDeck UUID. */
    @Test
    void updatesEntityThroughClient() {
        properties.setOperation("UPDATE_ENTITY");
        assertNotNull(new OperationService(client(), properties).execute(message()));
        assertEquals("PUT /api/projects/synthetic-project/entities/synthetic-type/11111111-1111-1111-1111-111111111111",
                requestLog.get(0));
        assertEquals("0123456789", body(0).at("/data/caseNumber").asText());
        assertEquals(1, body(0).size());
    }

    /** Verifies combined creation reads associated pseudonyms before creating its target value. */
    @Test
    void createsEntityAndPseudonymThroughClient() {
        properties.setOperation("CREATE_ENTITY_AND_PSEUDONYM");
        assertNotNull(new OperationService(client(), properties).execute(message(), "source/0/1"));
        assertEquals("POST /api/projects/synthetic-project/entities/synthetic-type", requestLog.get(0));
        assertEquals("GET /api/projects/synthetic-project/entities/synthetic-type/11111111-1111-1111-1111-111111111111/pseudonyms",
                requestLog.get(1));
        assertEquals("POST /api/domains/source%2Fdomain/pseudonyms?omitPrefix=false", requestLog.get(2));
        assertEquals("11111111-1111-1111-1111-111111111111", body(2).at("/identifierItem/identifier").asText());
        assertEquals("TrustDeckID", body(2).at("/identifierItem/idType").asText());
        assertTrue(body(2).get("validTo") == null);
    }

    /** Verifies a retried combined operation continues with the pseudonym instead of creating another entity. */
    @Test
    void retriedCombinedOperationReusesCompletedEntityStage() {
        properties.setOperation("CREATE_ENTITY_AND_PSEUDONYM");
        OperationService service = new OperationService(client(), properties);

        // The first attempt completes the entity stage, then the pseudonym creation fails
        responseStatusForPseudonymCreate = 503;
        assertThrows(TrustDeckResponseException.class, () -> service.execute(message(), "\"dbo\".\"patients\"/42"));
        assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"), service.completedUuid("\"dbo\".\"patients\"/42"));

        // The retry of the same row starts at the pseudonym stage
        responseStatusForPseudonymCreate = 0;
        requestLog.clear();
        requestBodies.clear();
        assertNotNull(service.execute(message(), "\"dbo\".\"patients\"/42"));

        assertEquals(0, requestLog.stream().filter(path -> path.startsWith("POST /api/projects/")).count());
        assertEquals("POST /api/domains/source%2Fdomain/pseudonyms?omitPrefix=false", requestLog.getLast());
    }

    /**
     * Creates a caller-controlled TrustDeck client.
     *
     * @return client
     */
    private TrustDeckClient client() {
        return new TrustDeckClient(properties.getTrustdeck().getServiceUrl(), () -> "token");
    }

    /**
     * Creates a synthetic mapped message.
     *
     * @return mapped message
     */
    private MappedMessage message() {
        com.fasterxml.jackson.databind.node.ObjectNode data = new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode().put("caseNumber", "0123456789");
        return new MappedMessage("source/domain", "0123456789", "case_number", "012-000000009-2", null,
                LocalDateTime.of(2025, 10, 9, 19, 8, 10, 175_000_000), null, "source-user", "synthetic-type",
                UUID.fromString("11111111-1111-1111-1111-111111111111"), data,
                "old", "type", "old-psn", "new", "type", "new-psn", "source/domain", "target/domain");
    }

    /**
     * Creates a standard update with no replacement selector or value.
     *
     * @return mapped message
     */
    private MappedMessage standardUpdateMessage() {
        return new MappedMessage("source/domain", "0123456789", "case_number", "012-000000009-2", null, null, null,
                "source-user", "synthetic-type", UUID.fromString("11111111-1111-1111-1111-111111111111"),
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("caseNumber", "0123456789"),
                "old", "type", "old-psn", null, null, null, null, null);
    }

    /**
     * Parses a captured JSON request body.
     *
     * @param index index of the captured request
     * @return parsed body
     */
    private com.fasterxml.jackson.databind.JsonNode body(int index) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(requestBodies.get(index));
        } catch (IOException exception) {
            throw new AssertionError("Invalid captured request body", exception);
        }
    }

    /**
     * Returns controlled responses for the client endpoints.
     *
     * @param exchange HTTP exchange
     * @throws IOException when response writing fails
     */
    private void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String query = exchange.getRequestURI().getRawQuery();
        requestLog.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
                + (query == null ? "" : "?" + query));
        if (path.endsWith("/pseudonyms") && "GET".equals(exchange.getRequestMethod())) {
            String responseBody = lookupResponse && path.startsWith("/api/domains/")
                    ? "{\"identifierItem\":{\"identifier\":\"0123456789\",\"idType\":\"case_number\"},\"psn\":\"012-000000009-2\"}"
                    : "[]";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
            return;
        }
        boolean pseudonymCreate = "POST".equals(exchange.getRequestMethod()) && path.startsWith("/api/domains/");
        int status = pseudonymCreate && responseStatusForPseudonymCreate != 0 ? responseStatusForPseudonymCreate
                : responseStatus != 0 ? responseStatus : "DELETE".equals(exchange.getRequestMethod()) ? 204
                : "PUT".equals(exchange.getRequestMethod()) ? 200 : 201;
        byte[] response = status == 204 ? new byte[0]
                : "{\"identifierItem\":{\"identifier\":\"0123456789\",\"idType\":\"case_number\"},\"psn\":\"012-000000009-2\",\"trustdeckID\":\"11111111-1111-1111-1111-111111111111\",\"data\":{}}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, response.length);
        if (response.length > 0) {
            exchange.getResponseBody().write(response);
        }
        exchange.close();
    }
}
