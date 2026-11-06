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

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.trustdeck.connector.sql.config.ConnectorProperties;
import org.trustdeck.connector.sql.database.SourceReader;
import org.trustdeck.connector.sql.database.SourceRow;
import org.trustdeck.connector.sql.database.SourceTable;
import org.trustdeck.connector.sql.database.ValueCodec;
import org.trustdeck.connector.sql.health.OutageCoordinator;
import org.trustdeck.connector.sql.notification.NotificationService;
import org.trustdeck.connector.sql.state.ProgressStore;
import org.trustdeck.connector.sql.statistics.ConnectorStatistics;

/**
 * Drives the connector: reads the source table page by page and hands every
 * page to the {@link RowProcessor}.
 *
 * In {@link ConnectorProperties.RunMode#ONE_SHOT one-shot} mode the runner stops
 * once no further rows exist, sends an optional completion notification, and
 * exits the JVM. In {@link ConnectorProperties.RunMode#POLLING polling} mode it
 * keeps querying for new rows at the configured interval. Systemic failures
 * pause processing until all recovery probes succeed. Processing runs on its
 * own thread, so the health endpoints are available while it is busy.
 *
 * @author Armin Müller
 */
@Slf4j
@Component
public class ConnectorRunner {

	/** Maximum time a graceful shutdown waits for the current row to finish. */
	private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(30);

	/** Minimum time between two progress log lines. */
	private static final Duration PROGRESS_LOG_INTERVAL = Duration.ofMinutes(1);

	/** Reads pages of source rows. */
	private final SourceReader reader;

	/** Processes pages of source rows. */
	private final RowProcessor processor;

	/** Position of the last completed row. */
	private final ProgressStore progress;

	/** Pauses and resumes processing around outages. */
	private final OutageCoordinator outage;

	/** Sends the optional completion and failure notifications. */
	private final NotificationService notifications;

	/** Collects optional runtime and backend statistics. */
	private final ConnectorStatistics statistics;

	/** Connector configuration containing the run mode and intervals. */
	private final ConnectorProperties properties;

	/** Resolved source table, used in log messages. */
	private final SourceTable table;

	/** Application context, closed before the JVM exits. */
	private final ConfigurableApplicationContext context;

	/** Terminates the JVM with an exit status. */
	private final IntConsumer terminator;

	/** Thread running the processing loop. */
	private volatile Thread worker;

	/** Whether a shutdown was requested. */
	private volatile boolean stopping;

	/**
	 * Creates the connector runner.
	 *
	 * @param reader source reader
	 * @param processor row processor
	 * @param progress progress store
	 * @param outage outage coordinator
	 * @param notifications notification service
	 * @param statistics statistics collector
	 * @param properties connector configuration
	 * @param table resolved source table
	 * @param context application context
	 */
	@Autowired
	public ConnectorRunner(SourceReader reader, RowProcessor processor, ProgressStore progress, OutageCoordinator outage,
			NotificationService notifications, ConnectorStatistics statistics, ConnectorProperties properties, SourceTable table,
			ConfigurableApplicationContext context) {
		this(reader, processor, progress, outage, notifications, statistics, properties, table, context, System::exit);
	}

	/**
	 * Creates the connector runner with an explicit JVM terminator.
	 *
	 * @param reader source reader
	 * @param processor row processor
	 * @param progress progress store
	 * @param outage outage coordinator
	 * @param notifications notification service
	 * @param statistics statistics collector
	 * @param properties connector configuration
	 * @param table resolved source table
	 * @param context application context
	 * @param terminator terminates the JVM with an exit status
	 */
	ConnectorRunner(SourceReader reader, RowProcessor processor, ProgressStore progress, OutageCoordinator outage,
			NotificationService notifications, ConnectorStatistics statistics, ConnectorProperties properties, SourceTable table,
			ConfigurableApplicationContext context, IntConsumer terminator) {
		this.reader = reader;
		this.processor = processor;
		this.progress = progress;
		this.outage = outage;
		this.notifications = notifications;
		this.statistics = statistics;
		this.properties = properties;
		this.table = table;
		this.context = context;
		this.terminator = terminator;
	}

	/**
	 * Starts the processing thread once the application is ready.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		Thread thread = new Thread(this::run, "connector-runner");
		worker = thread;
		thread.start();
	}

	/**
	 * Stops the processing thread on shutdown and waits for the current row to finish.
	 *
	 * When the runner itself closes the application after a one-shot import,
	 * this method runs on the processing thread and returns immediately.
	 */
	@PreDestroy
	public void stop() {
		stopping = true;
		Thread thread = worker;

		if (thread == null || thread == Thread.currentThread()) {
			return;
		}

		thread.interrupt();

		try {
			thread.join(SHUTDOWN_WAIT.toMillis());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Runs the processing loop and handles its end.
	 *
	 * An unexpected failure is a configuration or programming problem that a
	 * pause cannot fix, so the application exits with status 1.
	 */
	private void run() {
		try {
			loop();
		} catch (InterruptedException exception) {
			log.info("Processing stopped.");
		} catch (RuntimeException exception) {
			if (stopping) {
				return;
			}

			log.error("Processing failed and the connector stops: {}", FailureClassifier.describe(exception), exception);
			notifications.send("Connector stopped", "Processing failed and the connector stopped: " + FailureClassifier.describe(exception)
					+ summary());
			exit(1);
		}
	}

	/**
	 * Reads and processes pages until the table is exhausted (one-shot mode) or a
	 * shutdown is requested (polling mode).
	 *
	 * @throws InterruptedException if a shutdown interrupts processing or waiting
	 */
	private void loop() throws InterruptedException {
		Instant started = Instant.now();
		Instant lastReport = started;
		long rowsRead = 0;
		long rowsAtLastReport = 0;
		boolean idleAnnounced = false;
		log.info("Processing {} in {} mode with operation {}.", table.qualifiedName(), properties.getMode(), properties.getOperation());

		while (!stopping) {
			List<SourceRow> page;

			try {
				page = reader.read(progress.position());
			} catch (SQLException exception) {
				pause(new PauseRequired(exception, null, null));
				continue;
			}

			// Reached the end of the table
			if (page.isEmpty()) {
				if (properties.getMode() == ConnectorProperties.RunMode.ONE_SHOT) {
					complete(started);
					return;
				}

				if (!idleAnnounced) {
					log.info("All rows processed; polling for new rows every {}.{}", properties.getSource().getPollInterval(), summary());
					idleAnnounced = true;
				}

				Thread.sleep(properties.getSource().getPollInterval().toMillis());
				continue;
			}

			idleAnnounced = false;
			long pageStarted = System.nanoTime();

			try {
				processor.process(page);
				log.debug("Processed {} row(s) up to key {}.", page.size(), page.getLast().describeKey());
			} catch (PauseRequired pauseRequired) {
				pause(pauseRequired);
			} finally {
				statistics.recordBatch(page.size(), System.nanoTime() - pageStarted);
			}

			// Report the throughput at most once per interval; rows read again after a pause are counted again
			rowsRead += page.size();
			Instant now = Instant.now();
			Duration elapsed = Duration.between(lastReport, now);

			if (elapsed.compareTo(PROGRESS_LOG_INTERVAL) >= 0) {
				double rate = (rowsRead - rowsAtLastReport) / Math.max(1.0, elapsed.toMillis() / 1000.0);
				log.info("Progress: {} row(s) read, {} row(s)/s, last completed key {}.{}", rowsRead, String.format(Locale.ROOT, "%.1f", rate),
						progress.position() == null ? "-" : ValueCodec.text(progress.position().key()), summary());
				lastReport = now;
				rowsAtLastReport = rowsRead;
			}
		}
	}

	/**
	 * Pauses processing because of a systemic failure and waits for recovery.
	 *
	 * @param pauseRequired pause signal carrying the failure and the dynamic resources involved
	 * @throws InterruptedException if a shutdown interrupts waiting
	 */
	private void pause(PauseRequired pauseRequired) throws InterruptedException {
		if (stopping) {
			throw new InterruptedException("Processing stopped.");
		}

		Throwable failure = pauseRequired.getCause();

		// Domain and entity type names are configuration, not row content, and tell the operator what to fix
		log.warn("Systemic failure, pausing processing{}{}: {}",
				pauseRequired.getDomain() == null ? "" : " (domain " + pauseRequired.getDomain() + ")",
				pauseRequired.getEntityType() == null ? "" : " (entity type " + pauseRequired.getEntityType() + ")",
				FailureClassifier.describe(failure));

		outage.pause(FailureClassifier.category(failure), pauseRequired.getDomain(), pauseRequired.getEntityType(),
				properties.getTrustdeck().getProject());
		outage.awaitRecovery();
	}

	/**
	 * Ends a one-shot import: logs and sends the summary, then exits the JVM.
	 *
	 * @param started start of the import
	 */
	private void complete(Instant started) {
		Duration duration = Duration.between(started, Instant.now()).withNanos(0);
		String message = "Import of " + table.qualifiedName() + " completed after " + duration + "." + summary();

		log.info(message);
		notifications.send("Import completed", message);
		exit(0);
	}

	/**
	 * Builds a summary of the rows processed during this run.
	 *
	 * @return summary text starting with a space
	 */
	private String summary() {
		return " Succeeded: " + processor.succeeded() + ", dead-letter file: " + processor.deadLettered() + " ("
				+ properties.getRecovery().getDltFile() + "), review file: " + processor.reviewed() + " ("
				+ properties.getRecovery().getReviewFile() + ").";
	}

	/**
	 * Closes the application context and exits the JVM.
	 *
	 * @param status exit status
	 */
	private void exit(int status) {
		int code = SpringApplication.exit(context, () -> status);
		terminator.accept(code);
	}
}
