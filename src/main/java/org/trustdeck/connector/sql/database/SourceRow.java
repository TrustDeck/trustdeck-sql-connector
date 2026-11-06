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

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * One row read from the source table.
 *
 * Values are kept as the Java objects the driver returned for the column type,
 * with date and time columns converted to {@code java.time} types. Values can
 * be looked up case-insensitively by their configured column name.
 *
 * @param position position of the row in the read order
 * @param values column values by column name; {@code null} values are kept
 *
 * @author Armin Müller
 */
public record SourceRow(Position position, Map<String, Object> values) {

	/**
	 * Creates a row with a case-insensitive, unmodifiable copy of its values.
	 *
	 * @param position position of the row in the read order
	 * @param values column values by column name
	 */
	public SourceRow {
		Map<String, Object> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		copy.putAll(values);
		values = Collections.unmodifiableMap(copy);
	}

	/**
	 * Returns the value of a column.
	 *
	 * @param column column name, matched case-insensitively
	 * @return column value, or {@code null} if the value is SQL NULL or the column is not selected
	 */
	public Object value(String column) {
		return column == null ? null : values.get(column.trim());
	}

	/**
	 * Returns a description of the row that is safe for logs and recovery files.
	 *
	 * Only the key is included, never other column values.
	 *
	 * @return the key value as text
	 */
	public String describeKey() {
		return ValueCodec.text(position.key());
	}
}
