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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.trustdeck.connector.sql.config.ConnectorProperties;
import org.trustdeck.connector.sql.database.Position;
import org.trustdeck.connector.sql.database.SourceReader;
import org.trustdeck.connector.sql.database.SourceRow;
import org.trustdeck.connector.sql.health.OutageCoordinator;
import org.trustdeck.connector.sql.notification.NotificationService;
import org.trustdeck.connector.sql.state.ProgressStore;
import org.trustdeck.connector.sql.statistics.ConnectorStatistics;
import org.trustdeck.connector.sql.support.TestSettings;
import org.trustdeck.connector.sql.support.TestTables;

/**
 * Verifies the run modes, completion notification, and outage handling of the runner.
 *
 * @author Armin Müller
 */
class ConnectorRunnerTest {

	/** One page with a single row. */
	private static final List<SourceRow> PAGE = List.of(new SourceRow(new Position(1L, 1L), Map.of("ID", 1L)));

	/** Mocked source reader. */
	private SourceReader reader;

	/** Mocked row processor. */
	private RowProcessor processor;

	/** Mocked outage coordinator. */
	private OutageCoordinator outage;

	/** Mocked notification service. */
	private NotificationService notifications;

	/** Connector settings. */
	private ConnectorProperties properties;

	/** Exit status passed to the terminator. */
	private CompletableFuture<Integer> exit;

	/** Prepares the runner collaborators. */
	@BeforeEach
	void prepare() {
		reader = mock(SourceReader.class);
		processor = mock(RowProcessor.class);
		outage = mock(OutageCoordinator.class);
		notifications = mock(NotificationService.class);
		properties = TestSettings.pseudonymImport("jdbc:h2:mem:x", "sa", "sa", Path.of("state"), "http://localhost");
		properties.getSource().setPollInterval(Duration.ofMillis(20));
		exit = new CompletableFuture<>();
		when(processor.succeeded()).thenReturn(1L);
	}

	/**
	 * A one-shot import reads until the table is exhausted, notifies, and exits with status 0.
	 *
	 * @throws Exception if the runner does not exit in time
	 */
	@Test
	void oneShotNotifiesAndExits() throws Exception {
		when(reader.read(any())).thenReturn(PAGE, List.of());

		runner().start();

		assertEquals(0, exit.get(5, TimeUnit.SECONDS));
		verify(processor).process(PAGE);
		verify(notifications).send(eq("Import completed"), contains("Succeeded: 1"));
	}

	/**
	 * Polling mode keeps reading after the table is exhausted and stops without exiting the JVM.
	 *
	 * @throws Exception if reading fails unexpectedly
	 */
	@Test
	void pollingKeepsReadingUntilStopped() throws Exception {
		properties.setMode(ConnectorProperties.RunMode.POLLING);
		when(reader.read(any())).thenReturn(PAGE, List.of());
		ConnectorRunner runner = runner();

		runner.start();
		verify(reader, timeout(5_000).atLeast(4)).read(any());
		runner.stop();

		assertTrue(!exit.isDone());
		verify(notifications, never()).send(eq("Import completed"), anyString());
	}

	/**
	 * A database failure pauses processing and continues after recovery.
	 *
	 * @throws Exception if the runner does not exit in time
	 */
	@Test
	void pausesOnDatabaseFailure() throws Exception {
		when(reader.read(any())).thenThrow(new SQLTransientConnectionException("down", "08001")).thenReturn(PAGE, List.of());

		runner().start();

		assertEquals(0, exit.get(5, TimeUnit.SECONDS));
		verify(outage).pause(eq("DATABASE_UNAVAILABLE"), eq(null), eq(null), eq("synthetic-project"));
		verify(outage).awaitRecovery();
		verify(processor).process(PAGE);
	}

	/**
	 * An unexpected failure stops the connector with status 1 and a notification.
	 *
	 * @throws Exception if the runner does not exit in time
	 */
	@Test
	void exitsWithFailureStatusOnUnexpectedError() throws Exception {
		when(reader.read(any())).thenReturn(PAGE);
		doThrow(new IllegalStateException("unsupported key type")).when(processor).process(PAGE);

		runner().start();

		assertEquals(1, exit.get(5, TimeUnit.SECONDS));
		verify(notifications, atLeast(1)).send(eq("Connector stopped"), anyString());
	}

	/**
	 * Creates the runner under test with a terminator that records the exit status.
	 *
	 * @return connector runner
	 */
	private ConnectorRunner runner() {
		return new ConnectorRunner(reader, processor, mock(ProgressStore.class), outage, notifications, mock(ConnectorStatistics.class),
				properties, TestTables.patients(), mock(ConfigurableApplicationContext.class), exit::complete);
	}
}
