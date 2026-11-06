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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.trustdeck.client.TrustDeckClient;
import org.trustdeck.connector.sql.config.ConnectorProperties;

/**
 * Creates the shared immutable TrustDeck client and token provider.
 * 
 * @author Armin Müller
 */
@Configuration
@DependsOn("configurationValidation")
public class TrustDeckClientConfiguration {

	/**
	 * Creates client bean configuration.
	 */
	public TrustDeckClientConfiguration() { }

	/**
	 * Creates the shared resource client.
	 *
	 * @param properties connector settings
	 * @param tokenProvider cached OAuth provider
	 * @return TrustDeck client
	 */
	@Bean
	public TrustDeckClient trustDeckClient(ConnectorProperties properties, ClientCredentialsTokenProvider tokenProvider) {
		return new TrustDeckClient(properties.getTrustdeck().getServiceUrl(), tokenProvider,
				properties.getConnectTimeout(), properties.getReadTimeout());
	}
}
