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

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ansi.AnsiOutput;

/** Verifies the TrustDeck-style level colors and that coloring can be switched off. */
class LevelColorConverterTest {

    /** Converter under test. */
    private final LevelColorConverter converter = new LevelColorConverter();

    /** Restores the default ANSI detection after each test. */
    @AfterEach
    void resetAnsi() {
        AnsiOutput.setEnabled(AnsiOutput.Enabled.DETECT);
    }

    /** Errors red, warnings yellow, information blue, debug uncolored. */
    @Test
    void colorsLevelsLikeTrustDeck() {
        AnsiOutput.setEnabled(AnsiOutput.Enabled.ALWAYS);

        assertEquals("\u001B[31mERROR\u001B[0;39m", converter.transform(event(Level.ERROR), "ERROR"));
        assertEquals("\u001B[33mWARN\u001B[0;39m", converter.transform(event(Level.WARN), "WARN"));
        assertEquals("\u001B[34mINFO\u001B[0;39m", converter.transform(event(Level.INFO), "INFO"));
        assertEquals("DEBUG", converter.transform(event(Level.DEBUG), "DEBUG"));
    }

    /** With spring.output.ansi.enabled=never the text stays plain. */
    @Test
    void leavesTextPlainWhenAnsiIsDisabled() {
        AnsiOutput.setEnabled(AnsiOutput.Enabled.NEVER);

        assertEquals("ERROR", converter.transform(event(Level.ERROR), "ERROR"));
    }

    /**
     * Creates a logging event of a level.
     *
     * @param level event level
     * @return logging event
     */
    private LoggingEvent event(Level level) {
        LoggingEvent event = new LoggingEvent();
        event.setLevel(level);
        return event;
    }
}
