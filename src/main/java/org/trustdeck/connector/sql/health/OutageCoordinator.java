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

import java.sql.SQLException;
import java.time.Duration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.trustdeck.connector.sql.config.ConnectorProperties;
import org.trustdeck.connector.sql.database.SourceReader;
import org.trustdeck.connector.sql.notification.NotificationService;
import org.trustdeck.connector.sql.processing.FailureClassifier;
import org.trustdeck.connector.sql.recovery.RecoveryWriter;
import org.trustdeck.connector.sql.state.ProgressStore;
import org.trustdeck.connector.sql.trustdeck.TrustDeckPreflight;

/**
 * Coordinates connector behavior during temporary outages.
 *
 * When TrustDeck, the source database, or a local state file fails, processing
 * pauses at the first row that is not completed. The coordinator retains any
 * dynamic TrustDeck resources involved in the failure and periodically probes
 * them together with the static configuration, the source query, and the state
 * files. Processing continues once all checks succeed.
 *
 * @author Armin Müller
 */
@Slf4j
@Component
public class OutageCoordinator {

	/** Default minimum pause, so a merely slow backend gets time to recover before the next attempt. */
	static final Duration DEFAULT_MINIMUM_PAUSE = Duration.ofSeconds(10);

	/** Shared outage state exposed to the health indicator. */
	private final OutageState state;

	/** Performs authenticated read-only checks against configured TrustDeck resources. */
	private final TrustDeckPreflight preflight;

	/** Runs the source query to check the database. */
	private final SourceReader reader;

	/** Checks the dead-letter and review files. */
	private final RecoveryWriter recovery;

	/** Checks the progress file. */
	private final ProgressStore progress;

	/** Sends optional notifications when processing is paused or resumed. */
	private final NotificationService notifications;

	/** Delay between two recovery probes. */
	private final Duration probeInterval;

	/** Minimum time a pause lasts before a probe may resume processing. */
	private final Duration minimumPause;

	/** Last dynamic domain involved in the outage, if any. */
	private volatile String failedDomain;

	/** Last dynamic entity type involved in the outage, if any. */
	private volatile String failedEntityType;

	/** Project associated with the retained dynamic entity type, if any. */
	private volatile String failedProject;

	/**
	 * Creates the outage coordinator.
	 *
	 * @param state shared outage state
	 * @param preflight authenticated TrustDeck resource preflight
	 * @param reader source reader used to probe the database
	 * @param recovery recovery writer used to probe the recovery files
	 * @param progress progress store used to probe the progress file
	 * @param notifications notification service for outage and recovery messages
	 * @param properties connector settings containing the probe interval
	 */
	@Autowired
	public OutageCoordinator(OutageState state, TrustDeckPreflight preflight, SourceReader reader, RecoveryWriter recovery,
			ProgressStore progress, NotificationService notifications, ConnectorProperties properties) {
		this(state, preflight, reader, recovery, progress, notifications, properties.getRecovery().getProbeInterval(), DEFAULT_MINIMUM_PAUSE);
	}

	/**
	 * Creates the outage coordinator with explicit probe timing.
	 *
	 * A probe directly after a pause usually succeeds against a backend that is merely
	 * slow, which would resume processing only to pause it again moments later.
	 *
	 * @param state shared outage state
	 * @param preflight authenticated TrustDeck resource preflight
	 * @param reader source reader used to probe the database
	 * @param recovery recovery writer used to probe the recovery files
	 * @param progress progress store used to probe the progress file
	 * @param notifications notification service for outage and recovery messages
	 * @param probeInterval delay between two recovery probes
	 * @param minimumPause minimum time between a pause and a resuming probe
	 */
	public OutageCoordinator(OutageState state, TrustDeckPreflight preflight, SourceReader reader, RecoveryWriter recovery,
			ProgressStore progress, NotificationService notifications, Duration probeInterval, Duration minimumPause) {
		this.state = state;
		this.preflight = preflight;
		this.reader = reader;
		this.recovery = recovery;
		this.progress = progress;
		this.notifications = notifications;
		this.probeInterval = probeInterval;
		this.minimumPause = minimumPause;
	}

	/**
	 * Pauses processing and retains the dynamic TrustDeck resources required for
	 * the recovery probe.
	 *
	 * The outage notification is sent only when transitioning from running to
	 * paused state. Cached preflight results are discarded, so the probe really
	 * contacts TrustDeck again.
	 *
	 * @param reason sanitized outage category
	 * @param domain failed dynamic domain, or {@code null} if none
	 * @param entityType failed dynamic entity type, or {@code null} if none
	 * @param project project associated with the failed entity type, or {@code null} if none
	 */
	public synchronized void pause(String reason, String domain, String entityType, String project) {
		boolean alreadyPaused = state.isPaused();
		state.setPaused(true, reason);
		failedDomain = domain;
		failedEntityType = entityType;
		failedProject = project;
		preflight.invalidate();

		if (!alreadyPaused) {
			log.warn("Processing paused: {}", reason);
			notifications.send("Connector outage", "Processing paused: " + reason);
		}
	}

	/**
	 * Blocks until all probes succeed, then resumes processing.
	 *
	 * Static TrustDeck resources are verified first, then any retained dynamic
	 * domain or entity type, the source query, and the state files. A changed
	 * probe failure is logged at WARN, a repeated one at DEBUG.
	 *
	 * @throws InterruptedException if the thread is interrupted while waiting, typically during shutdown
	 */
	public void awaitRecovery() throws InterruptedException {
		String lastFailure = null;
		Thread.sleep(Math.max(minimumPause.toMillis(), probeInterval.toMillis()));

		while (true) {
			try {
				probe();

				state.setPaused(false, "");
				failedDomain = null;
				failedEntityType = null;
				failedProject = null;

				log.info("Recovery probe succeeded; processing resumed.");
				notifications.send("Connector recovered", "All probes succeeded; processing resumed.");

				return;
			} catch (RuntimeException | SQLException failure) {
				// Keep processing paused until every probe succeeds
				String description = FailureClassifier.describe(failure);
				if (!description.equals(lastFailure)) {
					log.warn("Recovery probe failed; processing stays paused{}: {}",
							failedDomain == null ? "" : " (domain " + failedDomain + ")", description);
					lastFailure = description;
				} else {
					log.debug("Recovery probe failed again: {}", description);
				}
			}

			Thread.sleep(probeInterval.toMillis());
		}
	}

	/**
	 * Runs all recovery probes once.
	 *
	 * @throws SQLException if the source query fails
	 * @throws RuntimeException if a TrustDeck resource or a state file is unavailable
	 */
	private synchronized void probe() throws SQLException {
		preflight.verifyStaticResources();

		if (failedDomain != null) {
			preflight.verifyDomain(failedDomain);
		}

		if (failedEntityType != null) {
			preflight.verifyEntityType(failedProject, failedEntityType);
		}

		reader.probe();
		recovery.probe();
		progress.probe();
	}
}
