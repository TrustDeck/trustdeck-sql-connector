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

package org.trustdeck.connector.sql.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;
import org.springframework.boot.ansi.AnsiColor;
import org.springframework.boot.ansi.AnsiElement;
import org.springframework.boot.ansi.AnsiOutput;

/**
 * Colors the wrapped text by log level like the TrustDeck backend: errors red,
 * warnings yellow, information blue, debug and trace uncolored.
 *
 * Coloring follows {@code spring.output.ansi.enabled}, so it can be switched off
 * for log collectors that do not understand ANSI escape codes.
 *
 * @author Armin Müller
 */
public class LevelColorConverter extends CompositeConverter<ILoggingEvent> {

	/** Creates the converter; instantiated by Logback. */
	public LevelColorConverter() { }

	/**
	 * Wraps the text in the color of the event's level.
	 *
	 * @param event logging event
	 * @param in text produced by the nested pattern, typically the level name
	 * @return colored or unchanged text
	 */
	@Override
	protected String transform(ILoggingEvent event, String in) {
		AnsiElement color = switch (event.getLevel().toInt()) {
			case Level.ERROR_INT -> AnsiColor.RED;
			case Level.WARN_INT -> AnsiColor.YELLOW;
			case Level.INFO_INT -> AnsiColor.BLUE;
			default -> null;
		};

		return color == null ? in : AnsiOutput.toString(color, in);
	}
}
