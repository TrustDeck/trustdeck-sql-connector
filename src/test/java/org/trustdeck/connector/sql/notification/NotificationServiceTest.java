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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import java.time.Duration;

/**
 * Verifies advisory SMTP notification behavior.
 *
 * @author Armin Müller
 */
class NotificationServiceTest {

	/** Ensures notifications are suppressed when no recipients are configured. */
	@Test
	void suppressesNotificationWithoutRecipients() {
		@SuppressWarnings("unchecked")
		ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
		JavaMailSender sender = mock(JavaMailSender.class);
		when(provider.getIfAvailable()).thenReturn(sender);

		new NotificationService(provider, "", "from@example.test", "[Test]").send("Subject", "Body");

		verify(sender, never()).send(any(SimpleMailMessage.class));
	}

	/** Ensures a mail transport failure does not escape into row processing. */
	@Test
	void swallowsNotificationTransportFailure() {
		@SuppressWarnings("unchecked")
		ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
		JavaMailSender sender = mock(JavaMailSender.class);
		when(provider.getIfAvailable()).thenReturn(sender);
		doThrow(new IllegalStateException("SMTP unavailable")).when(sender)
				.send(any(SimpleMailMessage.class));

		assertDoesNotThrow(() -> new NotificationService(provider, "to@example.test", "from@example.test", "[Test]")
				.send("Subject", "Body"));
		verify(sender).send(any(SimpleMailMessage.class));
	}

	/** Ensures repeated outage notices are cooled down while recovery is always sent. */
	@Test
	void coolsDownOutageButNotRecoveryNotification() {
		@SuppressWarnings("unchecked")
		ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
		JavaMailSender sender = mock(JavaMailSender.class);
		when(provider.getIfAvailable()).thenReturn(sender);
		NotificationService notifications = new NotificationService(provider, "to@example.test", "from@example.test",
				"[Test]", Duration.ofHours(1));

		notifications.send("TrustDeck outage", "first");
		notifications.send("TrustDeck outage", "duplicate");
		notifications.send("TrustDeck recovered", "recovered");

		verify(sender, times(2)).send(any(SimpleMailMessage.class));
	}
}
