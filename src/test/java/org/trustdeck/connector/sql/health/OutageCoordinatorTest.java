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
package org.trustdeck.connector.sql.health;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.SQLTransientConnectionException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.trustdeck.connector.sql.database.SourceReader;
import org.trustdeck.connector.sql.notification.NotificationService;
import org.trustdeck.connector.sql.recovery.RecoveryWriter;
import org.trustdeck.connector.sql.state.ProgressStore;
import org.trustdeck.connector.sql.state.StateFileException;
import org.trustdeck.connector.sql.trustdeck.TrustDeckPreflight;

/**
 * Verifies pausing, probing, and resuming around outages.
 *
 * @author Armin Müller
 */
class OutageCoordinatorTest {

	/**
	 * Processing stays paused until the database probe succeeds, and both transitions are notified once.
	 *
	 * @throws Exception if waiting is interrupted
	 */
	@Test
	void resumesAfterAllProbesSucceed() throws Exception {
		OutageState state = new OutageState();
		TrustDeckPreflight preflight = mock(TrustDeckPreflight.class);
		SourceReader reader = mock(SourceReader.class);
		NotificationService notifications = mock(NotificationService.class);
		doThrow(new SQLTransientConnectionException("down", "08001")).doThrow(new SQLTransientConnectionException("down", "08001"))
				.doNothing().when(reader).probe();
		OutageCoordinator coordinator = new OutageCoordinator(state, preflight, reader, mock(RecoveryWriter.class), mock(ProgressStore.class),
				notifications, Duration.ofMillis(10), Duration.ofMillis(10));

		coordinator.pause("DATABASE_UNAVAILABLE", "synthetic-domain", null, null);
		coordinator.pause("DATABASE_UNAVAILABLE", "synthetic-domain", null, null);
		assertTrue(state.isPaused());

		coordinator.awaitRecovery();

		assertFalse(state.isPaused());
		verify(reader, times(3)).probe();
		verify(preflight, times(3)).verifyDomain("synthetic-domain");
		verify(preflight, times(2)).invalidate();
		verify(notifications, times(1)).send(eq("Connector outage"), anyString());
		verify(notifications, times(1)).send(eq("Connector recovered"), anyString());
	}

	/**
	 * A failing state file keeps processing paused just like a failing backend.
	 *
	 * @throws Exception if waiting is interrupted
	 */
	@Test
	void probesStateFiles() throws Exception {
		OutageState state = new OutageState();
		RecoveryWriter recovery = mock(RecoveryWriter.class);
		doThrow(new StateFileException("full", null)).doNothing().when(recovery).probe();
		OutageCoordinator coordinator = new OutageCoordinator(state, mock(TrustDeckPreflight.class), mock(SourceReader.class), recovery,
				mock(ProgressStore.class), mock(NotificationService.class), Duration.ofMillis(10), Duration.ofMillis(10));

		coordinator.pause("STATE_FILE_UNAVAILABLE", null, null, null);
		coordinator.awaitRecovery();

		verify(recovery, times(2)).probe();
		assertFalse(state.isPaused());
	}
}
