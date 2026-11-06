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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Converts key and progress values to and from a typed text form.
 *
 * The progress file must restore a value with the Java type the driver returned,
 * so that it is bound as a query parameter of the same SQL type. Each value is
 * therefore stored together with a type tag.
 *
 * @author Armin Müller
 */
public final class ValueCodec {

	/** Prevents instantiation of this utility class. */
	private ValueCodec() { }

	/**
	 * Encodes a key or progress value.
	 *
	 * @param value value returned by the driver
	 * @return typed text form
	 * @throws IllegalStateException if the value has a type that cannot be stored
	 */
	public static Encoded encode(Object value) {
		return switch (value) {
			case String text -> new Encoded("STRING", text);
			case Integer number -> new Encoded("INTEGER", number.toString());
			case Short number -> new Encoded("INTEGER", number.toString());
			case Byte number -> new Encoded("INTEGER", number.toString());
			case Long number -> new Encoded("LONG", number.toString());
			case BigDecimal number -> new Encoded("DECIMAL", number.toPlainString());
			case BigInteger number -> new Encoded("DECIMAL", number.toString());
			case Double number -> new Encoded("DOUBLE", number.toString());
			case Float number -> new Encoded("DOUBLE", Double.toString(number.doubleValue()));
			case LocalDateTime timestamp -> new Encoded("LOCAL_DATE_TIME", timestamp.toString());
			case OffsetDateTime timestamp -> new Encoded("OFFSET_DATE_TIME", timestamp.toString());
			case LocalDate date -> new Encoded("LOCAL_DATE", date.toString());
			case UUID uuid -> new Encoded("UUID", uuid.toString());
			case byte[] bytes -> new Encoded("BYTES", Base64.getEncoder().encodeToString(bytes));
			case null -> throw new IllegalStateException("Key and progress values must not be NULL.");
			default -> throw new IllegalStateException("Key or progress column type " + value.getClass().getSimpleName()
					+ " is not supported; use a numeric, text, date, time stamp, UUID, or binary column.");
		};
	}

	/**
	 * Restores a value from its typed text form.
	 *
	 * @param encoded typed text form
	 * @return value with the original Java type
	 * @throws IllegalArgumentException if the type tag is unknown or the text is invalid for it
	 */
	public static Object decode(Encoded encoded) {
		String value = encoded.value();

		return switch (encoded.type()) {
			case "STRING" -> value;
			case "INTEGER" -> Integer.valueOf(value);
			case "LONG" -> Long.valueOf(value);
			case "DECIMAL" -> new BigDecimal(value);
			case "DOUBLE" -> Double.valueOf(value);
			case "LOCAL_DATE_TIME" -> LocalDateTime.parse(value);
			case "OFFSET_DATE_TIME" -> OffsetDateTime.parse(value);
			case "LOCAL_DATE" -> LocalDate.parse(value);
			case "UUID" -> UUID.fromString(value);
			case "BYTES" -> Base64.getDecoder().decode(value);
			default -> throw new IllegalArgumentException("Unknown progress value type " + encoded.type() + ".");
		};
	}

	/**
	 * Converts a key value to readable text for logs, recovery files, and correlations.
	 *
	 * @param value key value, may be {@code null}
	 * @return text form; binary values are shown as hexadecimal with a {@code 0x} prefix
	 */
	public static String text(Object value) {
		if (value instanceof byte[] bytes) {
			return "0x" + HexFormat.of().withUpperCase().formatHex(bytes);
		}

		if (value instanceof BigDecimal number) {
			return number.toPlainString();
		}

		return String.valueOf(value);
	}

	/**
	 * Typed text form of a key or progress value.
	 *
	 * @param type type tag, for example {@code LONG} or {@code LOCAL_DATE_TIME}
	 * @param value value in the canonical text form of its type
	 */
	public record Encoded(String type, String value) { }
}
