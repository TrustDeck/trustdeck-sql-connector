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
package org.trustdeck.connector.sql.processing;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.trustdeck.client.exception.RecordLinkageConflictException;
import org.trustdeck.client.exception.TrustDeckResponseException;
import org.trustdeck.connector.sql.config.ConnectorProperties;
import org.trustdeck.connector.sql.database.SourceRow;
import org.trustdeck.connector.sql.database.SourceTable;
import org.trustdeck.connector.sql.mapping.MappedMessage;
import org.trustdeck.connector.sql.mapping.MappingException;
import org.trustdeck.connector.sql.mapping.SqlRowMapper;
import org.trustdeck.connector.sql.operation.OperationService;
import org.trustdeck.connector.sql.operation.PseudonymBatchService;
import org.trustdeck.connector.sql.operation.PseudonymConflictException;
import org.trustdeck.connector.sql.recovery.RecoveryWriter;
import org.trustdeck.connector.sql.state.ProgressStore;
import org.trustdeck.connector.sql.state.StateFileException;
import org.trustdeck.connector.sql.trustdeck.TrustDeckPreflight;

/**
 * Processes pages of source rows with the configured TrustDeck operation.
 *
 * The processor maps rows, verifies dynamic TrustDeck targets, sends safe
 * pseudonym creations as batches, retries transient failures, and records
 * unprocessable rows in the dead-letter or review file. Rows are completed
 * strictly in read order: the progress file is updated only after a row was
 * confirmed by TrustDeck or its recovery entry was written.
 *
 * Systemic failures stop the page at the first row that is not completed and
 * are reported as {@link PauseRequired}, so the caller can pause and read the
 * remaining rows again after recovery.
 *
 * @author Armin Müller
 */
@Slf4j
@Component
public class RowProcessor {

	/** Maps source rows to connector messages. */
	private final SqlRowMapper mapper;

	/** Dispatches mapped messages to the configured TrustDeck operation. */
	private final OperationService operations;

	/** Writes failed or ambiguous rows to the recovery files. */
	private final RecoveryWriter recovery;

	/** Connector configuration controlling processing and recovery behavior. */
	private final ConnectorProperties properties;

	/** Coordinates safe batch execution for pseudonym creation. */
	private final PseudonymBatchService batches;

	/** Performs authenticated preflight checks for dynamic TrustDeck resources. */
	private final TrustDeckPreflight preflight;

	/** Stores the position of the last completed row. */
	private final ProgressStore progress;

	/** Resolved source table, used to build row correlations. */
	private final SourceTable table;

	/** Number of rows TrustDeck confirmed during this run. */
	private final AtomicLong succeeded = new AtomicLong();

	/** Number of rows written to the dead-letter file during this run. */
	private final AtomicLong deadLettered = new AtomicLong();

	/** Number of rows written to the review file during this run. */
	private final AtomicLong reviewed = new AtomicLong();

	/**
	 * Creates the row processor.
	 *
	 * @param mapper source row mapper
	 * @param operations TrustDeck operation dispatcher
	 * @param recovery recovery file writer
	 * @param properties connector configuration
	 * @param batches pseudonym batch coordinator
	 * @param preflight authenticated TrustDeck resource preflight
	 * @param progress progress store
	 * @param table resolved source table
	 */
	public RowProcessor(SqlRowMapper mapper, OperationService operations, RecoveryWriter recovery, ConnectorProperties properties,
			PseudonymBatchService batches, TrustDeckPreflight preflight, ProgressStore progress, SourceTable table) {
		this.mapper = mapper;
		this.operations = operations;
		this.recovery = recovery;
		this.properties = properties;
		this.batches = batches;
		this.preflight = preflight;
		this.progress = progress;
		this.table = table;
	}

	/**
	 * Processes one page of rows in read order.
	 *
	 * Rows are mapped first. Runs of compatible pseudonym creations are sent as
	 * one TrustDeck batch; all other rows are processed individually. Every row
	 * is completed before the next one starts.
	 *
	 * @param rows source rows in read order
	 * @throws PauseRequired if a systemic failure requires processing to pause
	 * @throws InterruptedException if the thread is interrupted, typically during shutdown
	 */
	public void process(List<SourceRow> rows) throws PauseRequired, InterruptedException {
		List<MappedMessage> mapped = new ArrayList<>();
		List<MappingException> invalid = new ArrayList<>();

		// Map all rows first while preserving their positions in the page
		for (SourceRow row : rows) {
			try {
				mapped.add(mapper.map(row));
				invalid.add(null);
			} catch (MappingException exception) {
				mapped.add(null);
				invalid.add(exception);
			}
		}

		boolean batchable = properties.getOperation().equals("CREATE_PSEUDONYM");

		// Process rows in read order: invalid rows go to the dead-letter file, runs of compatible
		// pseudonym creations are sent as one TrustDeck batch, everything else individually
		int index = 0;
		while (index < rows.size()) {
			if (Thread.currentThread().isInterrupted()) {
				throw new InterruptedException("Processing stopped.");
			}

			if (mapped.get(index) == null) {
				writeDlt(rows.get(index), null, "INVALID_MAPPING", 0, invalid.get(index));
				complete(rows.get(index));
				index++;
				continue;
			}

			if (batchable && batchable(mapped.get(index))) {
				int end = chunkEnd(mapped, index);
				processBatch(rows, mapped, index, end);
				index = end;
			} else {
				processOne(rows.get(index), mapped.get(index));
				index++;
			}
		}
	}

	/**
	 * Returns the number of rows TrustDeck confirmed during this run.
	 *
	 * @return number of successful rows
	 */
	public long succeeded() {
		return succeeded.get();
	}

	/**
	 * Returns the number of rows written to the dead-letter file during this run.
	 *
	 * @return number of dead-lettered rows
	 */
	public long deadLettered() {
		return deadLettered.get();
	}

	/**
	 * Returns the number of rows written to the review file during this run.
	 *
	 * @return number of rows awaiting review
	 */
	public long reviewed() {
		return reviewed.get();
	}

	/**
	 * Determines the end of the batchable chunk that starts at a row.
	 *
	 * A chunk contains consecutive mapped rows of the same TrustDeck domain that
	 * carry an imported pseudonym and identical validity settings, up to the
	 * configured batch size.
	 *
	 * @param mapped mapped messages of the page
	 * @param start index of the first row of the chunk, which must be {@link #batchable(MappedMessage) batchable}
	 * @return exclusive end index of the chunk, at least {@code start + 1}
	 */
	private int chunkEnd(List<MappedMessage> mapped, int start) {
		MappedMessage first = mapped.get(start);
		int limit = Math.min(mapped.size(), start + Math.max(1, properties.getBatchSize()));
		int end = start + 1;

		while (end < limit && compatible(first, mapped.get(end))) {
			end++;
		}

		return end;
	}

	/**
	 * Tests whether a mapped row can be created through the batch endpoint.
	 *
	 * Only imported pseudonyms can be correlated safely with the batch response.
	 *
	 * @param message mapped row
	 * @return {@code true} if the row carries a domain and an imported pseudonym
	 */
	private static boolean batchable(MappedMessage message) {
		return message.psn() != null && message.domain() != null;
	}

	/**
	 * Tests whether a row can join the batch started by another row.
	 *
	 * @param first mapped first row of the batch
	 * @param message mapped candidate row, may be {@code null}
	 * @return {@code true} if the candidate can be correlated safely within the same batch
	 */
	private static boolean compatible(MappedMessage first, MappedMessage message) {
		return message != null
				&& first.domain().equals(message.domain())
				&& message.psn() != null
				&& Objects.equals(first.validityTime(), message.validityTime())
				&& Objects.equals(first.validFrom(), message.validFrom())
				&& Objects.equals(first.validTo(), message.validTo());
	}

	/**
	 * Processes a safe pseudonym-create batch as one TrustDeck request.
	 *
	 * TrustDeck reports duplicates and conflicts in a batch only indirectly: a
	 * partial response (206) omits them, and a batch in which nothing was created
	 * is rejected as a whole (422), even if every row merely exists already.
	 * Every row the batch did not confirm is therefore processed individually,
	 * where an exact duplicate succeeds and a real conflict can be told apart from
	 * other rejections. A batch that cannot be submitted at all, for example
	 * because it exceeds the byte limit, is processed individually as well.
	 *
	 * @param rows source rows of the page
	 * @param mapped corresponding mapped messages
	 * @param from index of the first row of the chunk
	 * @param to exclusive end index of the chunk; all rows in between are mapped and compatible
	 * @throws PauseRequired if a systemic failure requires processing to pause
	 * @throws InterruptedException if a retry delay is interrupted
	 */
	private void processBatch(List<SourceRow> rows, List<MappedMessage> mapped, int from, int to) throws PauseRequired, InterruptedException {
		List<MappedMessage> chunk = mapped.subList(from, to);
		Set<Integer> created;

		try {
			verifyTargets(chunk.getFirst());
			created = batches.execute(chunk);
		} catch (Exception failure) {
			if (FailureClassifier.systemic(failure)) {
				throw new PauseRequired(failure, chunk.getFirst().domain(), null);
			}

			if (failure instanceof IllegalStateException) {
				// The response could not be correlated, so whether TrustDeck stored the rows is unknown
				for (int index = from; index < to; index++) {
					writeUnknown(rows.get(index), mapped.get(index), failure, operations.completedUuid(correlation(rows.get(index))));
					complete(rows.get(index));
				}

				return;
			}

			log.debug("Pseudonym batch not accepted, processing {} row(s) individually: {}", to - from, FailureClassifier.describe(failure));
			created = Set.of();
		}

		// A completely confirmed batch is completed with a single progress update
		if (created.size() == to - from) {
			succeeded.addAndGet(created.size());
			complete(rows.get(to - 1));
			return;
		}

		// Complete confirmed rows and process the rest individually, strictly in read order
		for (int index = from; index < to; index++) {
			if (created.contains(index - from)) {
				succeeded.incrementAndGet();
				complete(rows.get(index));
				continue;
			}

			processOne(rows.get(index), mapped.get(index));
		}
	}

	/**
	 * Processes one mapped row.
	 *
	 * Successful rows and rows written to a recovery file are completed. A
	 * systemic failure leaves the row incomplete and pauses processing.
	 *
	 * @param row source row
	 * @param mapped mapped connector message
	 * @throws PauseRequired if a systemic failure requires processing to pause
	 * @throws InterruptedException if a retry delay is interrupted
	 */
	private void processOne(SourceRow row, MappedMessage mapped) throws PauseRequired, InterruptedException {
		try {
			verifyTargets(mapped);
			operations.execute(mapped, correlation(row));
		} catch (RecordLinkageConflictException exception) {
			writeReview(row, mapped, exception);
			complete(row);
			return;
		} catch (PseudonymConflictException exception) {
			writeConflict(row, mapped, exception);
			complete(row);
			return;
		} catch (MappingException | IllegalArgumentException exception) {
			writeDlt(row, mapped, "INVALID_MAPPING", 0, exception);
			complete(row);
			return;
		} catch (Exception exception) {
			retryOrDlt(row, mapped, exception);
			return;
		}

		// The operation succeeded; the row is completed outside the try so a progress failure is not retried as an operation failure
		succeeded.incrementAndGet();
		complete(row);
	}

	/**
	 * Performs authenticated preflight checks for the dynamic TrustDeck targets of a row.
	 *
	 * Pseudonym operations verify the resolved domain, while entity operations
	 * verify the resolved project entity type. No checks are performed when
	 * preflight validation is disabled.
	 *
	 * @param message mapped row whose dynamic targets should be verified
	 */
	private void verifyTargets(MappedMessage message) {
		String operation = properties.getOperation();

		if (operation.equals("CREATE_PSEUDONYM") || operation.equals("UPDATE_PSEUDONYM")
				|| operation.equals("DELETE_PSEUDONYM") || operation.equals("CREATE_ENTITY_AND_PSEUDONYM")) {
			preflight.verifyDomain(message.domain());
		}

		if (operation.equals("CREATE_ENTITY") || operation.equals("UPDATE_ENTITY")
				|| operation.equals("DELETE_ENTITY") || operation.equals("CREATE_ENTITY_AND_PSEUDONYM")) {
			preflight.verifyEntityType(properties.getTrustdeck().getProject(), message.entityType());
		}
	}

	/**
	 * Retries a row-local operation using bounded delays before writing the row
	 * to the dead-letter file.
	 *
	 * Systemic failures stop retry processing immediately and pause processing.
	 * A definitive client error is written to the dead-letter file without retry.
	 *
	 * @param row source row
	 * @param mapped mapped connector message
	 * @param failure initial operation failure
	 * @throws PauseRequired if a systemic failure requires processing to pause
	 * @throws InterruptedException if a retry delay is interrupted
	 */
	private void retryOrDlt(SourceRow row, MappedMessage mapped, Exception failure) throws PauseRequired, InterruptedException {
		if (FailureClassifier.systemic(failure)) {
			throw new PauseRequired(failure, mapped.domain(), mapped.entityType());
		}

		// A definitive client error cannot change on retry
		if (FailureClassifier.rejected(failure)) {
			writeDlt(row, mapped, FailureClassifier.REJECTED_BY_TRUSTDECK, 0, failure);
			complete(row);
			return;
		}

		// Attempt to redo the operation
		int attempts = properties.getRecovery().getMaxAttempts();
		for (int attempt = 1; attempt <= attempts; attempt++) {
			Thread.sleep(retryDelay(failure, attempt));

			try {
				operations.execute(mapped, correlation(row));
			} catch (Exception retryFailure) {
				// Failed again
				failure = retryFailure;

				if (FailureClassifier.systemic(retryFailure)) {
					throw new PauseRequired(retryFailure, mapped.domain(), mapped.entityType());
				}

				if (FailureClassifier.rejected(retryFailure)) {
					writeDlt(row, mapped, FailureClassifier.REJECTED_BY_TRUSTDECK, attempt, retryFailure);
					complete(row);
					return;
				}

				continue;
			}

			succeeded.incrementAndGet();
			complete(row);
			return;
		}

		// After the configured amount of retries, the operation was still unsuccessful --> write the row to the DLT file
		writeDlt(row, mapped, FailureClassifier.category(failure), attempts, failure);
		complete(row);
	}

	/**
	 * Determines the delay before the next retry attempt.
	 *
	 * A valid server-provided {@code Retry-After} value takes precedence. Otherwise
	 * an exponential delay based on the configured initial backoff is used. Both
	 * forms are bounded to a maximum of 30 seconds.
	 *
	 * @param failure failure that triggered the retry
	 * @param attempt current retry attempt starting at one
	 * @return retry delay in milliseconds
	 */
	private long retryDelay(Exception failure, int attempt) {
		if (failure instanceof TrustDeckResponseException response) {
			Long retryAfter = retryAfterMillis(response.getRetryAfter());
			if (retryAfter != null) {
				return retryAfter;
			}
		}

		return Math.min(Duration.ofSeconds(30).toMillis(), properties.getRecovery().getInitialBackoff().toMillis() * (1L << Math.min(attempt - 1, 5)));
	}

	/**
	 * Parses an HTTP {@code Retry-After} value into a bounded retry delay.
	 *
	 * Both delta-seconds and RFC 1123 HTTP dates are accepted. Invalid or negative
	 * values are ignored, and valid delays are limited to 30 seconds.
	 *
	 * @param value Retry-After header value
	 * @return bounded delay in milliseconds, or {@code null} if the value is invalid
	 */
	private Long retryAfterMillis(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}

		String header = value.trim();
		try {
			long seconds = Long.parseLong(header);
			if (seconds < 0) {
				return null;
			}

			return seconds >= 30 ? Duration.ofSeconds(30).toMillis() : seconds * 1000L;
		} catch (NumberFormatException ignored) {
			try {
				long millis = Duration.between(Instant.now(), ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();

				return Math.max(0, Math.min(Duration.ofSeconds(30).toMillis(), millis));
			} catch (RuntimeException invalidHeader) {
				return null;
			}
		}
	}

	/**
	 * Stores a row as completed.
	 *
	 * @param row completed source row
	 * @throws PauseRequired if the progress file cannot be written
	 */
	private void complete(SourceRow row) throws PauseRequired {
		try {
			progress.save(row.position());
		} catch (StateFileException exception) {
			throw new PauseRequired(exception, null, null);
		}
	}

	/**
	 * Writes a failed row to the dead-letter file.
	 *
	 * @param row source row
	 * @param mapped mapped connector message, if available
	 * @param category sanitized recovery category
	 * @param attempts number of completed retry attempts
	 * @param failure failure that caused the entry
	 * @throws PauseRequired if the dead-letter file cannot be written
	 */
	private void writeDlt(SourceRow row, MappedMessage mapped, String category, int attempts, Throwable failure) throws PauseRequired {
		try {
			recovery.writeDlt(row, mapped, category, attempts, failure);
			deadLettered.incrementAndGet();
			log.debug("Row with key {} written to the dead-letter file: {}", row.describeKey(), category);
		} catch (StateFileException exception) {
			throw new PauseRequired(exception, null, null);
		}
	}

	/**
	 * Writes a record-linkage conflict to the review file.
	 *
	 * @param row source row
	 * @param mapped mapped connector message
	 * @param failure linkage conflict that caused the entry
	 * @throws PauseRequired if the review file cannot be written
	 */
	private void writeReview(SourceRow row, MappedMessage mapped, Throwable failure) throws PauseRequired {
		try {
			recovery.writeReview(row, mapped, 1, failure);
			reviewed.incrementAndGet();
		} catch (StateFileException exception) {
			throw new PauseRequired(exception, null, null);
		}
	}

	/**
	 * Writes an imported pseudonym that contradicts the target domain to the review file.
	 *
	 * @param row source row
	 * @param mapped mapped connector message
	 * @param conflict detected conflict
	 * @throws PauseRequired if the review file cannot be written
	 */
	private void writeConflict(SourceRow row, MappedMessage mapped, PseudonymConflictException conflict) throws PauseRequired {
		try {
			recovery.writeConflict(row, mapped, conflict);
			reviewed.incrementAndGet();
		} catch (StateFileException exception) {
			throw new PauseRequired(exception, null, null);
		}
	}

	/**
	 * Writes a row whose TrustDeck operation outcome cannot be determined.
	 *
	 * @param row source row
	 * @param mapped mapped connector message
	 * @param failure failure that left the operation outcome uncertain
	 * @param completedUuid completed TrustDeck entity UUID, if known
	 * @throws PauseRequired if the dead-letter file cannot be written
	 */
	private void writeUnknown(SourceRow row, MappedMessage mapped, Throwable failure, UUID completedUuid) throws PauseRequired {
		try {
			recovery.writeUnknown(row, mapped, failure, completedUuid);
			deadLettered.incrementAndGet();
		} catch (StateFileException exception) {
			throw new PauseRequired(exception, null, null);
		}
	}

	/**
	 * Builds a deterministic correlation identifier for a source row.
	 *
	 * The correlation combines table and key so combined-stage operations can
	 * identify the same row when it is retried.
	 *
	 * @param row source row
	 * @return deterministic source correlation
	 */
	private String correlation(SourceRow row) {
		return table.qualifiedName() + "/" + row.describeKey();
	}
}
