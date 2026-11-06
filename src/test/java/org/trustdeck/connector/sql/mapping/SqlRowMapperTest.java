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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.trustdeck.connector.sql.config.ConnectorProperties;
import org.trustdeck.connector.sql.database.Position;
import org.trustdeck.connector.sql.database.SourceRow;
import org.trustdeck.connector.sql.support.TestSettings;

/**
 * Verifies the conversion of SQL values to TrustDeck values.
 *
 * @author Armin Müller
 */
class SqlRowMapperTest {

	/** Connector settings with a pseudonym import mapping. */
	private ConnectorProperties properties;

	/** Prepares a pseudonym import mapping with Berlin as source timezone. */
	@BeforeEach
	void prepare() {
		properties = TestSettings.pseudonymImport("jdbc:h2:mem:x", "sa", "sa", Path.of("state"), "http://localhost");
		properties.getMapping().getCommon().setPsnColumn("psn");
		properties.getMapping().getCommon().setValidFromColumn("valid_from");
		properties.getMapping().getCommon().setSourceTimezone("Europe/Berlin");
	}

	/** Text keeps leading zeros; integers and decimals become their plain text. */
	@Test
	void convertsTextualValues() {
		MappedMessage text = map(row("case_number", "000123", "psn", 4711L));
		assertEquals("000123", text.identifier());
		assertEquals("4711", text.psn());

		MappedMessage decimal = map(row("case_number", new BigDecimal("1E+3")));
		assertEquals("1000", decimal.identifier());
		assertEquals("synthetic-domain", decimal.domain());
	}

	/** Floating-point and other ambiguous types are rejected with the column name only. */
	@Test
	void rejectsAmbiguousTextualValues() {
		MappingException failure = assertThrows(MappingException.class, () -> map(row("case_number", 12.5d)));

		assertTrue(failure.getMessage().contains("case_number"));
		assertFalse(failure.getMessage().contains("12.5"));
	}

	/** Required values that are NULL or blank are rejected. */
	@Test
	void requiresIdentifier() {
		assertThrows(MappingException.class, () -> map(row("case_number", null)));
		assertThrows(MappingException.class, () -> map(row("case_number", "  ")));
	}

	/** Time stamps without offset are interpreted in the source timezone, with offset converted directly. */
	@Test
	void convertsTimestampsToUtc() {
		assertEquals(LocalDateTime.of(2025, 10, 9, 19, 8, 10),
				map(row("case_number", "1", "valid_from", LocalDateTime.of(2025, 10, 9, 21, 8, 10))).validFrom());
		assertEquals(LocalDateTime.of(2025, 10, 9, 18, 8, 10),
				map(row("case_number", "1", "valid_from", OffsetDateTime.parse("2025-10-09T21:08:10+03:00"))).validFrom());
		assertEquals(LocalDateTime.of(2025, 1, 8, 23, 0),
				map(row("case_number", "1", "valid_from", LocalDate.of(2025, 1, 9))).validFrom());
	}

	/** Text time stamps need an offset or the configured format; ambiguous local times are rejected. */
	@Test
	void parsesTextTimestamps() {
		assertThrows(MappingException.class, () -> map(row("case_number", "1", "valid_from", "2025-10-09 21:08:10.175")));

		properties.getMapping().getCommon().setDatetimeFormat("uuuu-MM-dd HH:mm:ss.SSS");
		assertEquals(LocalDateTime.of(2025, 10, 9, 19, 8, 10, 175_000_000),
				map(row("case_number", "1", "valid_from", "2025-10-09 21:08:10.175")).validFrom());

		// 2:30 on the last Sunday of October exists twice in Berlin
		assertThrows(MappingException.class, () -> map(row("case_number", "1", "valid_from", LocalDateTime.of(2025, 10, 26, 2, 30))));
	}

	/** Entity data keeps SQL types as far as JSON allows. */
	@Test
	void buildsTypedEntityData() {
		properties.setOperation("CREATE_ENTITY");
		properties.getTrustdeck().setEntityType("patient");
		properties.getMapping().getCommon().getEntityFields().add(field("case_number", "/caseNumber", true));
		properties.getMapping().getCommon().getEntityFields().add(field("weight", "/vitals/weight", false));
		properties.getMapping().getCommon().getEntityFields().add(field("deceased", "/deceased", false));
		properties.getMapping().getCommon().getEntityFields().add(field("born", "/born", false));
		properties.getMapping().getCommon().getEntityFields().add(field("missing", "/missing", false));

		MappedMessage message = map(row("case_number", "000123", "weight", new BigDecimal("72.50"), "deceased", false,
				"born", LocalDate.of(1970, 1, 2), "missing", null));

		assertEquals("000123", message.entityData().get("caseNumber").textValue());
		assertEquals(new BigDecimal("72.50"), message.entityData().at("/vitals/weight").decimalValue());
		assertTrue(message.entityData().get("deceased").isBoolean());
		assertEquals("1970-01-02", message.entityData().get("born").textValue());
		assertNull(message.entityData().get("missing"));
	}

	/** A required entity column that is NULL fails the row; binary values cannot be mapped. */
	@Test
	void rejectsInvalidEntityValues() {
		properties.setOperation("CREATE_ENTITY");
		properties.getTrustdeck().setEntityType("patient");
		properties.getMapping().getCommon().getEntityFields().add(field("case_number", "/caseNumber", true));

		assertThrows(MappingException.class, () -> map(row("case_number", null)));
		assertThrows(MappingException.class, () -> map(row("case_number", new byte[] { 1 })));
	}

	/** Entity update and delete need a valid UUID, given as text or as a UUID column. */
	@Test
	void resolvesEntityIds() {
		properties.setOperation("DELETE_ENTITY");
		properties.getTrustdeck().setEntityType("patient");
		properties.getMapping().getCommon().setEntityIdColumn("trustdeck_id");
		UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");

		assertEquals(id, map(row("case_number", "1", "trustdeck_id", id)).entityId());
		assertEquals(id, map(row("case_number", "1", "trustdeck_id", id.toString().toUpperCase())).entityId());
		assertThrows(MappingException.class, () -> map(row("case_number", "1", "trustdeck_id", "not-a-uuid")));
		assertThrows(MappingException.class, () -> map(row("case_number", "1")));
	}

	/**
	 * Maps a row with the current settings.
	 *
	 * @param row source row
	 * @return mapped message
	 */
	private MappedMessage map(SourceRow row) {
		return new SqlRowMapper(properties).map(row);
	}

	/**
	 * Creates a row with key 1 and the given column values; the id type is always set.
	 *
	 * @param columnsAndValues alternating column names and values
	 * @return source row
	 */
	private static SourceRow row(Object... columnsAndValues) {
		Map<String, Object> values = new HashMap<>();
		values.put("ID_TYPE", "case");

		for (int index = 0; index < columnsAndValues.length; index += 2) {
			values.put((String) columnsAndValues[index], columnsAndValues[index + 1]);
		}

		return new SourceRow(new Position(1L, 1L), values);
	}

	/**
	 * Creates an entity field mapping.
	 *
	 * @param column source column
	 * @param target target JSON Pointer
	 * @param required whether the column must not be NULL
	 * @return field mapping
	 */
	private static ConnectorProperties.FieldMapping field(String column, String target, boolean required) {
		ConnectorProperties.FieldMapping field = new ConnectorProperties.FieldMapping();
		field.setColumn(column);
		field.setTargetPath(target);
		field.setRequired(required);

		return field;
	}
}
