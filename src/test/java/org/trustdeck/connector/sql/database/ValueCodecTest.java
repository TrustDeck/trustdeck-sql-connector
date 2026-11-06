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
package org.trustdeck.connector.sql.database;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Verifies that key and progress values keep their Java type through the progress file.
 *
 * @author Armin Müller
 */
class ValueCodecTest {

	/** Every supported type is restored with the same class and value. */
	@Test
	void restoresSupportedTypes() {
		List<Object> values = List.of("0042", 42, 42L, new BigDecimal("12.500"), 1.5d, LocalDateTime.of(2025, 10, 9, 21, 8, 10, 3_000_000),
				OffsetDateTime.of(2025, 10, 9, 21, 8, 10, 0, ZoneOffset.ofHours(2)), LocalDate.of(2025, 10, 9),
				UUID.fromString("11111111-1111-1111-1111-111111111111"));

		for (Object value : values) {
			Object restored = ValueCodec.decode(ValueCodec.encode(value));

			assertEquals(value.getClass(), restored.getClass());
			assertEquals(value, restored);
		}

		// Smaller integer types are widened to Integer, which binds to the same SQL column types
		assertEquals(7, ValueCodec.decode(ValueCodec.encode((short) 7)));
	}

	/** Binary values such as SQL Server rowversion columns survive unchanged. */
	@Test
	void restoresBinaryValues() {
		byte[] rowversion = { 0, 0, 0, 0, 0, 0, 7, (byte) 0xD1 };

		assertArrayEquals(rowversion, (byte[]) ValueCodec.decode(ValueCodec.encode(rowversion)));
		assertEquals("0x00000000000007D1", ValueCodec.text(rowversion));
	}

	/** Unsupported and NULL values cannot be stored as a position. */
	@Test
	void rejectsUnsupportedValues() {
		assertThrows(IllegalStateException.class, () -> ValueCodec.encode(Boolean.TRUE));
		assertThrows(IllegalStateException.class, () -> ValueCodec.encode(null));
		assertThrows(IllegalArgumentException.class, () -> ValueCodec.decode(new ValueCodec.Encoded("BLOB", "x")));
	}
}
