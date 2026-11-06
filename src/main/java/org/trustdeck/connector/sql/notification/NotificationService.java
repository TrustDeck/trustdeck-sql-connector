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

package org.trustdeck.connector.sql.notification;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Sends optional email notifications for connector outages, recoveries, and
 * the completion of a one-shot import.
 *
 * Notifications are sent only when recipients and a mail sender are available.
 * Repeated non-recovery notifications with the same subject are rate-limited by
 * a configurable cooldown, while recovery notifications bypass the cooldown.
 * Mail delivery failures are ignored so notification handling cannot interfere
 * with row processing.
 *
 * @author Armin Müller
 */
@Service
public class NotificationService {

	/** Spring mail sender, when configured. */
	private final ObjectProvider<JavaMailSender> sender;

	/** Configured notification recipients. */
	private final List<String> recipients;

	/** Optional sender address used for outgoing notifications. */
	private final String from;

	/** Prefix added to every notification subject. */
	private final String subjectPrefix;

	/** Minimum interval between repeated non-recovery notifications with the same subject. */
	private final Duration cooldown;

	/** Last attempted notification time for each rate-limited subject. */
	private final ConcurrentMap<String, Instant> lastSent = new ConcurrentHashMap<>();

	/**
	 * Creates the notification service with the default 15-minute cooldown.
	 *
	 * Recipient addresses are read as a comma-separated list. An empty recipient
	 * configuration effectively disables email notifications.
	 *
	 * @param sender optional Spring mail sender
	 * @param recipients comma-separated notification recipients
	 * @param from optional sender address
	 * @param subjectPrefix prefix added to notification subjects
	 */
	public NotificationService(ObjectProvider<JavaMailSender> sender, @Value("${SMTP_TO:}") String recipients,
			@Value("${SMTP_FROM:}") String from, @Value("${SUBJECT_PREFIX:[TrustDeck SQL Connector]}") String subjectPrefix) {
		this(sender, recipients, from, subjectPrefix, Duration.ofMinutes(15));
	}

	/**
	 * Creates the notification service with an explicit repeat-notification cooldown.
	 *
	 * Recipient addresses are normalized from the comma-separated configuration.
	 * A negative or missing cooldown is normalized to zero, which disables
	 * rate-limiting for repeated notifications.
	 *
	 * @param sender optional Spring mail sender
	 * @param recipients comma-separated notification recipients
	 * @param from optional sender address
	 * @param subjectPrefix prefix added to notification subjects
	 * @param cooldown minimum interval between repeated non-recovery notifications
	 */
	@Autowired
	public NotificationService(ObjectProvider<JavaMailSender> sender, @Value("${SMTP_TO:}") String recipients,
			@Value("${SMTP_FROM:}") String from, @Value("${SUBJECT_PREFIX:[TrustDeck SQL Connector]}") String subjectPrefix,
			@Value("${SMTP_COOLDOWN:15m}") Duration cooldown) {

		this.sender = sender;
		this.recipients = recipients == null ? List.of() : Arrays.stream(recipients.split(","))
				.map(String::trim).filter(value -> !value.isBlank()).toList();
		this.from = from;
		this.subjectPrefix = subjectPrefix;
		this.cooldown = cooldown == null || cooldown.isNegative() ? Duration.ZERO : cooldown;
	}

	/**
	 * Sends a connector notification when email delivery is configured.
	 *
	 * Repeated non-recovery notifications with the same subject are suppressed
	 * during the configured cooldown. Recovery notifications are always allowed.
	 * Missing mail infrastructure or delivery failures are ignored because email
	 * notifications are advisory and must not affect connector processing.
	 *
	 * @param subject sanitized notification subject
	 * @param body sanitized notification body
	 */
	public void send(String subject, String body) {
		if (recipients.isEmpty()) {
			return;
		}

		// Recovery notifications bypass rate limiting so successful recovery is reported immediately
		boolean recovery = subject != null && subject.toLowerCase(Locale.ROOT).contains("recovered");

		Instant now = Instant.now();

		// Rate-limit repeated non-recovery notifications by subject
		if (!recovery && cooldown.compareTo(Duration.ZERO) > 0) {
			Instant previous = lastSent.putIfAbsent(subject, now);

			if (previous != null && previous.plus(cooldown).isAfter(now)) {
				return;
			}

			if (previous != null) {
				lastSent.replace(subject, previous, now);
			}
		}

		try {
			SimpleMailMessage message = new SimpleMailMessage();
			message.setTo(recipients.toArray(String[]::new));

			if (from != null && !from.isBlank()) {
				message.setFrom(from);
			}

			message.setSubject((subjectPrefix == null ? "" : subjectPrefix) + " " + subject);
			message.setText(body);

			// Sending remains optional when no JavaMailSender has been configured
			JavaMailSender mailSender = sender.getIfAvailable();
			if (mailSender != null) {
				mailSender.send(message);
			}
		} catch (RuntimeException ignored) {
			// Mail is advisory and must never complete or block row processing
		}
	}
}
