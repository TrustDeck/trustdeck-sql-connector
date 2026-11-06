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

package org.trustdeck.connector.sql.mapping;

import java.time.LocalDateTime;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Immutable representation of a source row after connector mapping.
 *
 * The record contains the resolved pseudonym, validity, provenance, entity, and
 * pseudonym-update values required by the configured TrustDeck operation. Fields
 * that are not relevant to the selected operation may be {@code null}.
 *
 * @param domain resolved TrustDeck domain
 * @param identifier resolved pseudonym identifier
 * @param idType resolved pseudonym identifier type
 * @param psn resolved or imported pseudonym value
 * @param validityTime resolved pseudonym validity duration
 * @param validFrom resolved validity start
 * @param validTo resolved validity end
 * @param username resolved source provenance user
 * @param entityType resolved TrustDeck entity type
 * @param entityId resolved TrustDeck entity UUID
 * @param entityData mapped entity payload
 * @param oldIdentifier current pseudonym identifier used for updates
 * @param oldIdType current pseudonym identifier type used for updates
 * @param oldPsn current pseudonym value used for updates
 * @param newIdentifier replacement pseudonym identifier
 * @param newIdType replacement pseudonym identifier type
 * @param newPsn replacement pseudonym value
 * @param oldDomain current pseudonym domain used for updates
 * @param newDomain replacement pseudonym domain
 *
 * @author Armin Müller
 */
public record MappedMessage(String domain, String identifier, String idType, String psn, String validityTime, 
		LocalDateTime validFrom, LocalDateTime validTo, String username, String entityType, UUID entityId, 
		ObjectNode entityData, String oldIdentifier, String oldIdType, String oldPsn, String newIdentifier, 
		String newIdType, String newPsn, String oldDomain, String newDomain) { }
