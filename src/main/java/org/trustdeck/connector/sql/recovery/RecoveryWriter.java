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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.trustdeck.client.exception.TrustDeckResponseException;
import org.trustdeck.connector.sql.config.ConnectorProperties;
import org.trustdeck.connector.sql.database.SourceRow;
import org.trustdeck.connector.sql.database.SourceTable;
import org.trustdeck.connector.sql.mapping.MappedMessage;
import org.trustdeck.connector.sql.mapping.MappingException;
import org.trustdeck.connector.sql.operation.PseudonymConflictException;
import org.trustdeck.connector.sql.state.StateFileException;
import org.trustdeck.connector.sql.state.StateFiles;

/**
 * Writes entries for failed, ambiguous, or review-required rows to local files.
 *
 * Failed rows are appended to the dead-letter file, rows that need a manual
 * decision to the review file. Both are JSON Lines files: one JSON object per
 * line. Every entry is forced to the storage device before the method returns,
 * so the row's progress is only stored after its entry is durable.
 *
 * @author Armin Müller
 */
@Service
public class RecoveryWriter {

	/** Version of the recovery entry format. */
	private static final String SCHEMA_VERSION = "1";

	/** JSON serializer used for recovery entries. */
	private final ObjectMapper mapper;

	/** Connector configuration containing the file locations and operation metadata. */
	private final ConnectorProperties properties;

	/** Resolved source table, used to identify rows. */
	private final SourceTable table;

	/** Dead-letter file. */
	private final Path dltFile;

	/** Review file. */
	private final Path reviewFile;

	/**
	 * Creates the recovery writer.
	 *
	 * @param properties connector configuration
	 * @param table resolved source table
	 * @param mapper JSON mapper used to serialize recovery entries
	 */
	public RecoveryWriter(ConnectorProperties properties, SourceTable table, ObjectMapper mapper) {
		this.properties = properties;
		this.table = table;
		this.mapper = mapper;
		this.dltFile = Path.of(properties.getRecovery().getDltFile());
		this.reviewFile = Path.of(properties.getRecovery().getReviewFile());
	}

	/**
	 * Writes a failed row to the dead-letter file.
	 *
	 * @param row source row
	 * @param mapped mapped connector message, if available
	 * @param category sanitized failure category
	 * @param attempts number of completed processing attempts
	 * @param failure failure that caused the entry
	 * @throws StateFileException if the entry cannot be written durably
	 */
	public void writeDlt(SourceRow row, MappedMessage mapped, String category, int attempts, Throwable failure) {
		write(dltFile, row, mapped, category, "FAILED_DLT", attempts, failure, null, null);
	}

	/**
	 * Writes a record-linkage conflict to the review file.
	 *
	 * @param row source row
	 * @param mapped mapped connector message
	 * @param attempts number of completed processing attempts
	 * @param failure linkage failure requiring review
	 * @throws StateFileException if the entry cannot be written durably
	 */
	public void writeReview(SourceRow row, MappedMessage mapped, int attempts, Throwable failure) {
		write(reviewFile, row, mapped, "RECORD_LINKAGE", "PENDING_LINKAGE_REVIEW", attempts, failure, null, null);
	}

	/**
	 * Writes an imported pseudonym that contradicts the target domain to the review file.
	 *
	 * The failure category names the kind of contradiction, for example
	 * {@code PSEUDONYM_CONFLICT_IDENTIFIER_HAS_OTHER_PSEUDONYM}.
	 *
	 * @param row source row
	 * @param mapped mapped connector message
	 * @param conflict detected conflict
	 * @throws StateFileException if the entry cannot be written durably
	 */
	public void writeConflict(SourceRow row, MappedMessage mapped, PseudonymConflictException conflict) {
		write(reviewFile, row, mapped, "PSEUDONYM_CONFLICT_" + conflict.getReason(), "PENDING_CONFLICT_REVIEW", 1, conflict, null, null);
	}

	/**
	 * Writes a row whose TrustDeck operation outcome cannot be determined to the dead-letter file.
	 *
	 * When the entity stage of a combined operation completed successfully, its
	 * UUID is included so an operator can reconcile the outcome before processing
	 * the remaining stage.
	 *
	 * @param row source row
	 * @param mapped mapped connector message
	 * @param failure failure that left the operation outcome uncertain
	 * @param completedUuid confirmed entity UUID from a completed combined-operation stage, or {@code null}
	 * @throws StateFileException if the entry cannot be written durably
	 */
	public void writeUnknown(SourceRow row, MappedMessage mapped, Throwable failure, UUID completedUuid) {
		write(dltFile, row, mapped, "UNKNOWN_OUTCOME", "UNKNOWN_OUTCOME", 1, failure,
				completedUuid == null ? "Check in TrustDeck whether the operation took effect before processing the row again."
						: "Entity stage completed; reconcile the preserved UUID before creating the pseudonym.",
				completedUuid);
	}

	/**
	 * Checks that both recovery files can be written.
	 *
	 * @throws StateFileException if a file cannot be created or written
	 */
	public void probe() {
		for (Path file : new Path[] { dltFile, reviewFile }) {
			try {
				StateFiles.checkWritable(file);
			} catch (IOException exception) {
				throw new StateFileException("Recovery file " + file + " cannot be written.", exception);
			}
		}
	}

	/**
	 * Serializes and durably appends one recovery entry.
	 *
	 * @param file destination file
	 * @param row source row
	 * @param mapped mapped connector message, if available
	 * @param category sanitized failure category
	 * @param status recovery entry status
	 * @param attempts number of completed processing attempts
	 * @param failure failure associated with the entry
	 * @param manualGuidance optional operator guidance
	 * @param completedUuid confirmed entity UUID from a completed combined-operation stage, or {@code null}
	 * @throws StateFileException if the entry cannot be written durably
	 */
	private void write(Path file, SourceRow row, MappedMessage mapped, String category, String status, int attempts,
			Throwable failure, String manualGuidance, UUID completedUuid) {
		// Identify the row by table and key; mapping problems name only the affected column
		RecoveryEntry entry = new RecoveryEntry(
				SCHEMA_VERSION,
				status,
				Instant.now(),
				table.qualifiedName(),
				table.key().name(),
				row.describeKey(),
				properties.getOperation(),
				mapped == null ? null : mapped.domain(),
				category,
				httpStatus(failure),
				attempts,
				completedUuid == null ? null : "ENTITY_CREATED",
				completedUuid == null ? null : completedUuid.toString(),
				mapped == null ? null : mapped.username(),
				failure instanceof MappingException ? failure.getMessage() : null,
				manualGuidance,
				properties.getTrustdeck().getProject(),
				mapped == null ? null : mapped.entityType());

		try {
			StateFiles.appendLine(file, mapper.writeValueAsString(entry));
		} catch (IOException exception) {
			throw new StateFileException("Recovery file " + file + " cannot be written.", exception);
		}
	}

	/**
	 * Extracts the numeric HTTP status from a TrustDeck response failure.
	 *
	 * @param failure failure associated with the entry
	 * @return HTTP status code, or {@code null} for non-HTTP failures
	 */
	private Integer httpStatus(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (cause instanceof TrustDeckResponseException exception) {
				return exception.getStatusCode();
			}
		}

		return null;
	}
}
