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

/**
 * Position of a row in the read order of the source table.
 *
 * Rows are read ordered by their progress value and then by their key. The
 * position of the last completed row is the starting point of the next query.
 * Both values are kept as the Java objects the driver returned, so they can be
 * bound unchanged as query parameters.
 *
 * @param progress value of the progress column
 * @param key value of the key column
 *
 * @author Armin Müller
 */
public record Position(Object progress, Object key) { }
