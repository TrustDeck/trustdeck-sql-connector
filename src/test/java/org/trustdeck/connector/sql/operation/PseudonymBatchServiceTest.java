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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.trustdeck.client.TrustDeckClient;
import org.trustdeck.client.exception.TrustDeckResponseException;
import org.trustdeck.connector.sql.config.ConnectorProperties;
import org.trustdeck.connector.sql.mapping.MappedMessage;

/** Verifies correlation of batch responses to the submitted source records. */
class PseudonymBatchServiceTest {

    /** Controlled HTTP server standing in for TrustDeck. */
    private HttpServer server;
    /** Number of single pseudonym creations received. */
    private int singleCreates;
    /** Body of the last batch request. */
    private String batchBody;
    /** Kind of batch response the server returns. */
    private String responseMode = "partial";

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

    /** A partial response reports only the created record and leaves the rest to the caller. */
    @Test
    void reportsCreatedIndexesOfPartialResponse() {
        ConnectorProperties properties = new ConnectorProperties();
        properties.getTrustdeck().setProject("synthetic-project");
        properties.setBatchSize(10);
        properties.setBatchBytes(100_000);
        TrustDeckClient client = new TrustDeckClient("http://localhost:" + server.getAddress().getPort(), () -> "token");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        List<MappedMessage> messages = List.of(message("one", "p1"), message("two", "p2"));

        Set<Integer> created = new PseudonymBatchService(client, properties, mapper).execute(messages);

        assertEquals(Set.of(0), created);
        assertEquals(0, singleCreates);
    }

    /** A batch in which nothing was created is rejected as a whole and surfaces the 422. */
    @Test
    void propagatesWholeBatchRejection() {
        responseMode = "rejected";
        ConnectorProperties properties = new ConnectorProperties();
        properties.getTrustdeck().setProject("synthetic-project");
        properties.setBatchSize(10);
        properties.setBatchBytes(100_000);
        TrustDeckClient client = new TrustDeckClient("http://localhost:" + server.getAddress().getPort(), () -> "token");

        TrustDeckResponseException failure = assertThrows(TrustDeckResponseException.class, () -> new PseudonymBatchService(client,
                properties, new ObjectMapper().registerModule(new JavaTimeModule())).execute(List.of(message("one", "p1"))));
        assertEquals(422, failure.getStatusCode());
        assertEquals(0, singleCreates);
    }

    /** Verifies an optional validity duration is retained in a batch request. */
    @Test
    void forwardsOptionalValidityTimeInBatch() {
        ConnectorProperties properties = new ConnectorProperties();
        properties.getTrustdeck().setProject("synthetic-project");
        properties.setBatchSize(10);
        properties.setBatchBytes(100_000);
        TrustDeckClient client = new TrustDeckClient("http://localhost:" + server.getAddress().getPort(), () -> "token");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        new PseudonymBatchService(client, properties, mapper).execute(List.of(message("one", "p1", "1d")));

        assertEquals(true, batchBody.contains("\"validityTime\":\"1d\""));
    }

    /** Rejects a reduced partial response instead of guessing its source occurrence. */
    @Test
    void rejectsReducedPartialResponse() {
        responseMode = "reduced";
        ConnectorProperties properties = new ConnectorProperties();
        properties.getTrustdeck().setProject("synthetic-project");
        properties.setBatchSize(10);
        properties.setBatchBytes(100_000);
        TrustDeckClient client = new TrustDeckClient("http://localhost:" + server.getAddress().getPort(), () -> "token");

        assertThrows(IllegalStateException.class, () -> new PseudonymBatchService(client, properties,
                new ObjectMapper().registerModule(new JavaTimeModule())).execute(List.of(message("one", "p1"))));
        assertEquals(0, singleCreates);
    }

    /** Correlates a complete response by identifying fields rather than response order. */
    @Test
    void correlatesReorderedCompleteResponse() {
        responseMode = "reordered";
        ConnectorProperties properties = new ConnectorProperties();
        properties.getTrustdeck().setProject("synthetic-project");
        properties.setBatchSize(10);
        properties.setBatchBytes(100_000);
        TrustDeckClient client = new TrustDeckClient("http://localhost:" + server.getAddress().getPort(), () -> "token");

        Set<Integer> created = new PseudonymBatchService(client, properties,
                new ObjectMapper().registerModule(new JavaTimeModule())).execute(
                        List.of(message("one", "p1"), message("two", "p2")));

        assertEquals(Set.of(0, 1), created);
        assertEquals(0, singleCreates);
    }

    /** Matches repeated source occurrences independently instead of treating the second as a duplicate response. */
    @Test
    void correlatesRepeatedOccurrences() {
        responseMode = "duplicate";
        ConnectorProperties properties = new ConnectorProperties();
        properties.getTrustdeck().setProject("synthetic-project");
        properties.setBatchSize(10);
        properties.setBatchBytes(100_000);
        TrustDeckClient client = new TrustDeckClient("http://localhost:" + server.getAddress().getPort(), () -> "token");

        Set<Integer> created = new PseudonymBatchService(client, properties,
                new ObjectMapper().registerModule(new JavaTimeModule())).execute(
                        List.of(message("one", "p1"), message("one", "p1")));

        assertEquals(Set.of(0, 1), created);
        assertEquals(0, singleCreates);
    }

    /** Rejects a complete response containing an identifier that was not requested. */
    @Test
    void rejectsUnmatchedCompleteResponse() {
        responseMode = "unmatched";
        ConnectorProperties properties = new ConnectorProperties();
        properties.getTrustdeck().setProject("synthetic-project");
        properties.setBatchSize(10);
        properties.setBatchBytes(100_000);
        TrustDeckClient client = new TrustDeckClient("http://localhost:" + server.getAddress().getPort(), () -> "token");

        assertThrows(IllegalStateException.class, () -> new PseudonymBatchService(client, properties,
                new ObjectMapper().registerModule(new JavaTimeModule())).execute(List.of(message("one", "p1"))));
        assertEquals(0, singleCreates);
    }

    /** Enforces the serialized UTF-8 batch limit rather than a character count. */
    @Test
    void rejectsUtf8BatchAboveByteLimit() {
        ConnectorProperties properties = new ConnectorProperties();
        properties.getTrustdeck().setProject("synthetic-project");
        properties.setBatchSize(10);
        properties.setBatchBytes(20);
        TrustDeckClient client = new TrustDeckClient("http://localhost:" + server.getAddress().getPort(), () -> "token");

        assertThrows(IllegalArgumentException.class, () -> new PseudonymBatchService(client, properties,
                new ObjectMapper().registerModule(new JavaTimeModule())).execute(List.of(message("ä", "p1"))));
        assertEquals(0, singleCreates);
    }

    /**
     * Creates a mapped message without validity settings.
     *
     * @param identifier pseudonym identifier
     * @param psn imported pseudonym value
     * @return mapped message
     */
    private MappedMessage message(String identifier, String psn) {
        return new MappedMessage("domain", identifier, "type", psn, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Creates a mapped message with a validity duration.
     *
     * @param identifier pseudonym identifier
     * @param psn imported pseudonym value
     * @param validityTime validity duration
     * @return mapped message
     */
    private MappedMessage message(String identifier, String psn, String validityTime) {
        return new MappedMessage("domain", identifier, "type", psn, validityTime, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Answers single and batch pseudonym creations.
     *
     * @param exchange HTTP exchange
     * @throws IOException if the response cannot be written
     */
    private void respond(HttpExchange exchange) throws IOException {
        boolean batch = exchange.getRequestURI().getPath().endsWith("/batch");
        batchBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!batch) {
            singleCreates++;
        }
        String response = switch (responseMode) {
            case "reduced" -> "[{\"psn\":\"p1\"}]";
            case "reordered" -> "[{\"identifierItem\":{\"identifier\":\"two\",\"idType\":\"type\"},\"psn\":\"p2\"},"
                    + "{\"identifierItem\":{\"identifier\":\"one\",\"idType\":\"type\"},\"psn\":\"p1\"}]";
            case "duplicate" -> "[{\"identifierItem\":{\"identifier\":\"one\",\"idType\":\"type\"},\"psn\":\"p1\"},"
                    + "{\"identifierItem\":{\"identifier\":\"one\",\"idType\":\"type\"},\"psn\":\"p1\"}]";
            case "unmatched" -> "[{\"identifierItem\":{\"identifier\":\"other\",\"idType\":\"type\"},\"psn\":\"p9\"}]";
            default -> "[{\"identifierItem\":{\"identifier\":\"one\",\"idType\":\"type\"},\"psn\":\"p1\"}]";
        };
        if (!batch) {
            response = "{\"identifierItem\":{\"identifier\":\"two\",\"idType\":\"type\"},\"psn\":\"p2\"}";
        }
        if (batch && responseMode.equals("rejected")) {
            exchange.sendResponseHeaders(422, -1);
            exchange.close();
            return;
        }
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(batch ? 206 : 201, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
