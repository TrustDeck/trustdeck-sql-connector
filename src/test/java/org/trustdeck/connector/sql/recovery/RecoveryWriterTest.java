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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatusCode;
import org.trustdeck.client.exception.TrustDeckResponseException;
import org.trustdeck.connector.sql.config.ConnectorProperties;
import org.trustdeck.connector.sql.database.Position;
import org.trustdeck.connector.sql.database.SourceRow;
import org.trustdeck.connector.sql.mapping.MappingException;
import org.trustdeck.connector.sql.operation.PseudonymConflictException;
import org.trustdeck.connector.sql.state.StateFileException;
import org.trustdeck.connector.sql.support.TestSettings;
import org.trustdeck.connector.sql.support.TestTables;

/**
 * Verifies the content and durability of the dead-letter and review files.
 *
 * @author Armin Müller
 */
class RecoveryWriterTest {

	/** JSON mapper configured like Spring Boot's. */
	private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build()
			.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	/** Temporary directory for state files. */
	@TempDir
	Path state;

	/**
	 * Entries identify the row by key only and never copy other column values.
	 *
	 * @throws IOException if the file cannot be read
	 */
	@Test
	void writesKeyButNoRowContent() throws IOException {
		RecoveryWriter writer = writer(settings());

		writer.writeDlt(row(), null, "INVALID_MAPPING", 0, new MappingException("Column case_number must contain text."));
		writer.writeDlt(row(), null, "REJECTED_BY_TRUSTDECK", 1, new RuntimeException(new TrustDeckResponseException("x", HttpStatusCode.valueOf(422))));

		List<JsonNode> entries = read(state.resolve("dlt.jsonl"));
		assertEquals(2, entries.size());
		assertEquals("FAILED_DLT", entries.getFirst().get("status").asText());
		assertEquals("42", entries.getFirst().get("sourceKey").asText());
		assertEquals("ID", entries.getFirst().get("keyColumn").asText());
		assertEquals("Column case_number must contain text.", entries.getFirst().get("detail").asText());
		assertEquals(422, entries.get(1).get("httpStatus").asInt());
		assertFalse(entries.get(1).has("detail"));
		assertFalse(Files.readString(state.resolve("dlt.jsonl")).contains("secret-identifier"));
	}

	/**
	 * Conflicts and linkage problems go to the review file; unknown outcomes keep the completed stage.
	 *
	 * @throws IOException if a file cannot be read
	 */
	@Test
	void separatesReviewAndUnknownOutcomes() throws IOException {
		RecoveryWriter writer = writer(settings());

		writer.writeConflict(row(), null, new PseudonymConflictException(PseudonymConflictException.Reason.PSEUDONYM_ASSIGNED_ELSEWHERE, null));
		writer.writeUnknown(row(), null, new IllegalStateException("ambiguous"), UUID.fromString("11111111-1111-1111-1111-111111111111"));

		JsonNode review = read(state.resolve("review.jsonl")).getFirst();
		assertEquals("PENDING_CONFLICT_REVIEW", review.get("status").asText());
		assertEquals("PSEUDONYM_CONFLICT_PSEUDONYM_ASSIGNED_ELSEWHERE", review.get("failureCategory").asText());

		JsonNode unknown = read(state.resolve("dlt.jsonl")).getFirst();
		assertEquals("UNKNOWN_OUTCOME", unknown.get("status").asText());
		assertEquals("ENTITY_CREATED", unknown.get("completedStage").asText());
		assertTrue(unknown.get("manualGuidance").asText().contains("reconcile"));
	}

	/**
	 * A file that cannot be written is reported as a state file failure.
	 *
	 * @throws IOException if the blocking directory cannot be created
	 */
	@Test
	void reportsUnwritableFile() throws IOException {
		ConnectorProperties properties = settings();
		Files.createDirectories(state.resolve("dlt.jsonl"));

		assertThrows(StateFileException.class, () -> writer(properties).writeDlt(row(), null, "INVALID_MAPPING", 0, null));
		assertThrows(StateFileException.class, writer(properties)::probe);
	}

	/**
	 * Creates a writer for the given settings.
	 *
	 * @param properties connector settings
	 * @return recovery writer
	 */
	private RecoveryWriter writer(ConnectorProperties properties) {
		return new RecoveryWriter(properties, TestTables.patients(), MAPPER);
	}

	/**
	 * Creates settings whose state files live in the temporary directory.
	 *
	 * @return connector settings
	 */
	private ConnectorProperties settings() {
		return TestSettings.pseudonymImport("jdbc:h2:mem:x", "sa", "sa", state, "http://localhost");
	}

	/**
	 * Creates a row with key 42 and a sensitive identifier.
	 *
	 * @return source row
	 */
	private static SourceRow row() {
		return new SourceRow(new Position(42L, 42L), Map.of("ID", 42L, "CASE_NUMBER", "secret-identifier"));
	}

	/**
	 * Reads a JSON Lines file.
	 *
	 * @param file file to read
	 * @return one JSON object per line
	 * @throws IOException if the file cannot be read
	 */
	private static List<JsonNode> read(Path file) throws IOException {
		return Files.readAllLines(file).stream().map(line -> {
			try {
				return MAPPER.readTree(line);
			} catch (IOException exception) {
				throw new AssertionError("Invalid JSON line", exception);
			}
		}).toList();
	}
}
