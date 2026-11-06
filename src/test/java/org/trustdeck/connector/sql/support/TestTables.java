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
package org.trustdeck.connector.sql.support;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Types;

import org.trustdeck.connector.sql.database.SourceTable;

/**
 * Creates resolved source tables without a database.
 *
 * @author Armin Müller
 */
public final class TestTables {

	/** Prevents instantiation of this utility class. */
	private TestTables() { }

	/**
	 * Creates a stand-in for the resolved {@code PATIENTS} table keyed by {@code ID}.
	 *
	 * @return resolved source table
	 */
	public static SourceTable patients() {
		SourceTable table = mock(SourceTable.class);
		SourceTable.Column key = new SourceTable.Column("ID", "\"ID\"", Types.BIGINT, "BIGINT");

		when(table.qualifiedName()).thenReturn("\"PUBLIC\".\"PATIENTS\"");
		when(table.key()).thenReturn(key);
		when(table.progress()).thenReturn(key);
		when(table.progressIsKey()).thenReturn(true);

		return table;
	}
}
