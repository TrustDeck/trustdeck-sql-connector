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

package org.trustdeck.connector.sql.trustdeck;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.trustdeck.connector.sql.config.ConnectorProperties;

/** Verifies OAuth refresh before the configured token expiry window. */
class ClientCredentialsTokenProviderTest {

    /** Controlled token endpoint. */
    private HttpServer server;
    /** Number of token requests received. */
    private AtomicInteger requests;

    /**
     * Starts the controlled token endpoint.
     *
     * @throws IOException if the server cannot start
     */
    @BeforeEach
    void start() throws IOException {
        requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/token", this::token);
        server.start();
    }

    /** Stops the controlled token endpoint. */
    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** An almost-expired token is refreshed rather than reused. */
    @Test
    void refreshesExpiringToken() {
        ConnectorProperties properties = new ConnectorProperties();
        properties.getTrustdeck().setTokenEndpoint("http://localhost:" + server.getAddress().getPort() + "/token");
        properties.getTrustdeck().setClientId("client");
        properties.getTrustdeck().setClientSecret("secret");
        ClientCredentialsTokenProvider provider = new ClientCredentialsTokenProvider(properties);

        assertEquals("token-1", provider.getAccessToken());
        assertEquals("token-2", provider.getAccessToken());
        assertEquals(2, requests.get());
    }

    /**
     * Answers a token request.
     *
     * @param exchange HTTP exchange
     * @throws IOException if the response cannot be written
     */
    private void token(HttpExchange exchange) throws IOException {
        int number = requests.incrementAndGet();
        byte[] response = ("{\"access_token\":\"token-" + number + "\",\"expires_in\":1}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
