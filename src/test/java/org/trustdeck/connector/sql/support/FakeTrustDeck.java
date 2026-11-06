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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Minimal TrustDeck and Keycloak stand-in for tests.
 *
 * The token endpoint returns a fixed token, every GET returns an empty object,
 * and every POST echoes its JSON body with HTTP 201, which the client reads as
 * the created pseudonym (or batch of pseudonyms). All requests are recorded.
 *
 * @author Armin Müller
 */
public class FakeTrustDeck implements AutoCloseable {

	/** HTTP server on a free local port. */
	private final HttpServer server;

	/** Recorded requests as {@code METHOD path}. */
	private final List<String> requests = new CopyOnWriteArrayList<>();

	/** Recorded request bodies of POST requests. */
	private final List<String> bodies = new CopyOnWriteArrayList<>();

	/** Status returned for every POST; zero returns 201 with the echoed body. */
	private volatile int postStatus;

	/**
	 * Starts the server on a free local port.
	 *
	 * @throws IOException if the server cannot start
	 */
	public FakeTrustDeck() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::respond);
		server.start();
	}

	/**
	 * Returns the base URL of the TrustDeck service.
	 *
	 * @return service URL
	 */
	public String serviceUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/**
	 * Returns the URL of the token endpoint.
	 *
	 * @return token endpoint URL
	 */
	public String tokenEndpoint() {
		return serviceUrl() + "/token";
	}

	/**
	 * Returns all recorded requests.
	 *
	 * @return requests as {@code METHOD path}
	 */
	public List<String> requests() {
		return requests;
	}

	/**
	 * Returns the bodies of all recorded POST requests.
	 *
	 * @return request bodies in arrival order
	 */
	public List<String> bodies() {
		return bodies;
	}

	/**
	 * Sets the status returned for every POST.
	 *
	 * @param status HTTP status, or zero to echo the body with 201
	 */
	public void postStatus(int status) {
		this.postStatus = status;
	}

	/** Stops the server. */
	@Override
	public void close() {
		server.stop(0);
	}

	/**
	 * Answers one request.
	 *
	 * @param exchange HTTP exchange
	 * @throws IOException if the response cannot be written
	 */
	private void respond(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		requests.add(exchange.getRequestMethod() + " " + path);

		byte[] response;
		int status;

		// Token requests, reads, and writes are answered differently
		if (path.equals("/token")) {
			response = "{\"access_token\":\"test-token\",\"expires_in\":300}".getBytes(StandardCharsets.UTF_8);
			status = 200;
		} else if ("GET".equals(exchange.getRequestMethod())) {
			response = "{}".getBytes(StandardCharsets.UTF_8);
			status = 200;
		} else {
			bodies.add(body);
			status = postStatus == 0 ? 201 : postStatus;
			response = status == 201 ? body.getBytes(StandardCharsets.UTF_8) : new byte[0];
		}

		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);

		if (response.length > 0) {
			exchange.getResponseBody().write(response);
		}

		exchange.close();
	}
}
