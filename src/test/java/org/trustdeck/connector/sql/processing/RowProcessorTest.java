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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.trustdeck.client.exception.RecordLinkageConflictException;
import org.trustdeck.client.exception.TrustDeckResponseException;
import org.trustdeck.connector.sql.config.ConnectorProperties;
import org.trustdeck.connector.sql.database.Position;
import org.trustdeck.connector.sql.database.SourceRow;
import org.trustdeck.connector.sql.database.SourceTable;
import org.trustdeck.connector.sql.mapping.MappedMessage;
import org.trustdeck.connector.sql.mapping.SqlRowMapper;
import org.trustdeck.connector.sql.operation.OperationService;
import org.trustdeck.connector.sql.operation.PseudonymBatchService;
import org.trustdeck.connector.sql.recovery.RecoveryWriter;
import org.trustdeck.connector.sql.state.ProgressStore;
import org.trustdeck.connector.sql.support.TestSettings;
import org.trustdeck.connector.sql.support.TestTables;
import org.trustdeck.connector.sql.trustdeck.TrustDeckPreflight;

/**
 * Verifies row completion, recovery routing, retries, batching, and pausing.
 *
 * @author Armin Müller
 */
class RowProcessorTest {

	/** JSON mapper configured like Spring Boot's. */
	private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

	/** Temporary directory for state files. */
	@TempDir
	Path state;

	/** Connector settings for a pseudonym import. */
	private ConnectorProperties properties;

	/** Resolved stand-in of the source table. */
	private SourceTable table;

	/** Mocked TrustDeck operation dispatcher. */
	private OperationService operations;

	/** Mocked TrustDeck batch service. */
	private PseudonymBatchService batches;

	/** Progress store writing to the temporary directory. */
	private ProgressStore progress;

	/** Prepares the processor collaborators. */
	@BeforeEach
	void prepare() {
		properties = TestSettings.pseudonymImport("jdbc:h2:mem:x", "sa", "sa", state, "http://localhost");
		properties.getRecovery().setMaxAttempts(2);
		table = TestTables.patients();
		operations = mock(OperationService.class);
		batches = mock(PseudonymBatchService.class);
		progress = new ProgressStore(properties, table, MAPPER);
	}

	/**
	 * Successful rows are completed in order; invalid rows go to the dead-letter file and are completed too.
	 *
	 * @throws Exception if processing fails unexpectedly
	 */
	@Test
	void completesSuccessfulAndInvalidRows() throws Exception {
		RowProcessor processor = processor();

		processor.process(List.of(row(1, "0001"), row(2, null), row(3, "0003")));

		assertEquals(3L, progress.position().key());
		assertEquals(2, processor.succeeded());
		assertEquals(1, processor.deadLettered());
		assertTrue(Files.readString(state.resolve("dlt.jsonl")).contains("INVALID_MAPPING"));
		verify(operations, times(2)).execute(any(MappedMessage.class), anyString());
	}

	/**
	 * A systemic failure stops at the failed row; its predecessors stay completed.
	 *
	 * @throws Exception if processing fails unexpectedly
	 */
	@Test
	void pausesAtSystemicFailure() throws Exception {
		when(operations.execute(identifier("0002"), anyString()))
				.thenThrow(new ResourceAccessException("down"));

		PauseRequired pause = assertThrows(PauseRequired.class, () -> processor().process(List.of(row(1, "0001"), row(2, "0002"), row(3, "0003"))));

		assertEquals("synthetic-domain", pause.getDomain());
		assertEquals(1L, progress.position().key());
		verify(operations, never()).execute(identifier("0003"), anyString());
	}

	/**
	 * Definitive rejections are dead-lettered without retry; transient failures are retried.
	 *
	 * @throws Exception if processing fails unexpectedly
	 */
	@Test
	void retriesOnlyTransientFailures() throws Exception {
		when(operations.execute(identifier("0001"), anyString()))
				.thenThrow(new TrustDeckResponseException("rejected", HttpStatusCode.valueOf(422)));
		when(operations.execute(identifier("0002"), anyString()))
				.thenThrow(new TrustDeckResponseException("busy", HttpStatusCode.valueOf(429)))
				.thenReturn(new Object());
		RowProcessor processor = processor();

		processor.process(List.of(row(1, "0001"), row(2, "0002")));

		verify(operations, times(1)).execute(identifier("0001"), anyString());
		verify(operations, times(2)).execute(identifier("0002"), anyString());
		assertEquals(1, processor.succeeded());
		assertTrue(Files.readString(state.resolve("dlt.jsonl")).contains("REJECTED_BY_TRUSTDECK"));
		assertEquals(2L, progress.position().key());
	}

	/**
	 * Record-linkage conflicts are written to the review file.
	 *
	 * @throws Exception if processing fails unexpectedly
	 */
	@Test
	void routesLinkageConflictsToReview() throws Exception {
		when(operations.execute(any(MappedMessage.class), anyString()))
				.thenThrow(new RecordLinkageConflictException(HttpStatusCode.valueOf(409), null, null, null, List.of()));
		RowProcessor processor = processor();

		processor.process(List.of(row(1, "0001")));

		assertEquals(1, processor.reviewed());
		assertTrue(Files.readString(state.resolve("review.jsonl")).contains("PENDING_LINKAGE_REVIEW"));
	}

	/**
	 * Imported pseudonyms are batched; rows the batch did not confirm are processed individually.
	 *
	 * @throws Exception if processing fails unexpectedly
	 */
	@Test
	void batchesImportedPseudonyms() throws Exception {
		properties.getMapping().getCommon().setPsnColumn("psn");
		when(batches.execute(anyList())).thenReturn(Set.of(0, 2));
		RowProcessor processor = processor();

		processor.process(List.of(row(1, "0001", "p1"), row(2, "0002", "p2"), row(3, "0003", "p3")));

		verify(batches).execute(argThat(chunk -> chunk.size() == 3));
		verify(operations).execute(identifier("0002"), anyString());
		assertEquals(3, processor.succeeded());
		assertEquals(3L, progress.position().key());
	}

	/**
	 * An unwritable dead-letter file pauses processing before the row is completed.
	 *
	 * @throws IOException if the blocking directory cannot be created
	 */
	@Test
	void pausesWhenRecoveryFileFails() throws IOException {
		Files.createDirectories(state.resolve("dlt.jsonl"));

		PauseRequired pause = assertThrows(PauseRequired.class, () -> processor().process(List.of(row(1, null))));

		assertEquals("STATE_FILE_UNAVAILABLE", FailureClassifier.category(pause.getCause()));
		assertNull(progress.position());
	}

	/**
	 * Matches a mapped message by identifier; stubbing calls the matcher with {@code null}.
	 *
	 * @param identifier expected identifier
	 * @return null-safe argument matcher
	 */
	private static MappedMessage identifier(String identifier) {
		return argThat(message -> message != null && identifier.equals(message.identifier()));
	}

	/**
	 * Creates the processor under test.
	 *
	 * @return row processor
	 */
	private RowProcessor processor() {
		return new RowProcessor(new SqlRowMapper(properties), operations, new RecoveryWriter(properties, table, MAPPER), properties,
				batches, mock(TrustDeckPreflight.class), progress, table);
	}

	/**
	 * Creates a row without an imported pseudonym.
	 *
	 * @param id key value
	 * @param caseNumber identifier value, may be {@code null}
	 * @return source row
	 */
	private static SourceRow row(long id, String caseNumber) {
		return row(id, caseNumber, null);
	}

	/**
	 * Creates a row.
	 *
	 * @param id key value
	 * @param caseNumber identifier value, may be {@code null}
	 * @param psn imported pseudonym, may be {@code null}
	 * @return source row
	 */
	private static SourceRow row(long id, String caseNumber, String psn) {
		Map<String, Object> values = new HashMap<>();
		values.put("ID", id);
		values.put("CASE_NUMBER", caseNumber);
		values.put("ID_TYPE", "case");
		values.put("PSN", psn);

		return new SourceRow(new Position(id, id), values);
	}
}
