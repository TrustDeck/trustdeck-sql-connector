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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.trustdeck.client.AccessTokenProvider;
import org.trustdeck.connector.sql.config.ConnectorProperties;

/**
 * Acquires and caches OAuth access tokens using the client credentials grant.
 *
 * Tokens are reused until shortly before their reported expiry. Refreshes are
 * synchronized so concurrent callers cannot trigger duplicate token requests.
 * Token acquisition uses the configured client credentials and token endpoint.
 *
 * @author Armin Müller
 */
@Component
public class ClientCredentialsTokenProvider implements AccessTokenProvider {

	/** Refresh margin applied before the reported token expiry. */
	private static final Duration EXPIRY_SKEW = Duration.ofSeconds(30);

	/** HTTP client used for OAuth token requests. */
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	/** JSON parser used for OAuth token responses. */
	private final ObjectMapper mapper = new ObjectMapper();

	/** TrustDeck OAuth client credentials and token endpoint settings. */
	private final ConnectorProperties.TrustDeckProperties properties;

	/** Cached bearer token without the authorization scheme prefix. */
	private String token;

	/** Reported expiry time of the currently cached token. */
	private Instant expiry = Instant.MIN;

	/**
	 * Creates the client credentials token provider.
	 *
	 * @param connectorProperties connector configuration containing OAuth settings
	 */
	public ClientCredentialsTokenProvider(ConnectorProperties connectorProperties) {
		this.properties = connectorProperties.getTrustdeck();
	}

	/**
	 * Returns a valid cached access token or obtains a new token from the OAuth endpoint.
	 *
	 * Cached tokens are refreshed before their reported expiry using
	 * {@link #EXPIRY_SKEW}. Token refresh is synchronized so only one caller
	 * performs the HTTP request at a time.
	 *
	 * @return bearer token value without the {@code Bearer} prefix
	 * @throws TokenAcquisitionException if token acquisition fails, is interrupted, or returns an invalid response
	 */
	@Override
	public synchronized String getAccessToken() {
		// Reuse the cached token while it remains safely outside the expiry margin
		if (token != null && Instant.now().isBefore(expiry.minus(EXPIRY_SKEW))) {
			return token;
		}

		try {
			String credentials = properties.getClientId() + ":" + properties.getClientSecret();

			// Request a token using the OAuth 2.0 client credentials grant
			HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getTokenEndpoint()))
					.timeout(Duration.ofSeconds(30))
					.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
					.build();

			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IllegalStateException("OAuth token endpoint returned HTTP " + response.statusCode());
			}

			JsonNode body = mapper.readTree(response.body());
			JsonNode accessToken = body.get("access_token");

			if (accessToken == null || !accessToken.isTextual() || accessToken.textValue().isBlank()) {
				throw new IllegalStateException("OAuth token response did not contain access_token.");
			}

			token = accessToken.textValue();

			// Use the reported lifetime, falling back to five minutes when omitted
			long expiresIn = body.path("expires_in").asLong(300);
			expiry = Instant.now().plusSeconds(Math.max(1, expiresIn));

			return token;
		} catch (InterruptedException exception) {
			// Preserve the interrupted state for callers higher in the stack
			Thread.currentThread().interrupt();
			throw new TokenAcquisitionException("OAuth token acquisition was interrupted.", exception);
		} catch (Exception exception) {
			throw new TokenAcquisitionException("OAuth token acquisition failed.", exception);
		}
	}
}
