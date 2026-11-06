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
package org.trustdeck.connector.sql.recovery;

import java.time.Instant;

/**
 * One line of the dead-letter or review file.
 *
 * An entry identifies the source row by its table and key, together with the
 * connector operation, the resolved TrustDeck target, the sanitized failure
 * outcome, and optional completed-stage metadata. Column values other than the
 * key are not copied; the row itself stays in the source database.
 *
 * @param schemaVersion recovery entry schema version
 * @param status terminal recovery status
 * @param recordedAt time the entry was written
 * @param sourceTable quoted, schema-qualified source table
 * @param keyColumn key column of the source table
 * @param sourceKey key value of the source row as text
 * @param operation connector operation that processed the row
 * @param resolvedTarget resolved TrustDeck target, typically the domain
 * @param failureCategory sanitized failure category
 * @param httpStatus related HTTP status, if available
 * @param attempts number of completed processing attempts
 * @param completedStage confirmed completed stage of a multi-stage operation, if any
 * @param completedUuid UUID associated with the confirmed completed stage, if any
 * @param username mapped source provenance user, if available
 * @param detail sanitized mapping problem naming the affected column, if available
 * @param manualGuidance operator guidance for handling the entry
 * @param targetProject resolved TrustDeck project
 * @param targetEntityType resolved TrustDeck entity type, if relevant
 *
 * @author Armin Müller
 */
public record RecoveryEntry(String schemaVersion, String status, Instant recordedAt, String sourceTable, String keyColumn,
		String sourceKey, String operation, String resolvedTarget, String failureCategory, Integer httpStatus, int attempts,
		String completedStage, String completedUuid, String username, String detail, String manualGuidance,
		String targetProject, String targetEntityType) { }
