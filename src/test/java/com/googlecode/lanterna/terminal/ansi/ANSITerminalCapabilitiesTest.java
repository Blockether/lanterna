/*
 * This file is part of lanterna (https://github.com/mabe02/lanterna).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2010-2024 Martin Berglund
 */
package com.googlecode.lanterna.terminal.ansi;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.UnicodeWidth;
import com.googlecode.lanterna.UnicodeWidth.WidthMethod;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.TerminalResponse;
import com.googlecode.lanterna.terminal.ansi.TerminalCapabilities.Multiplexer;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ANSITerminalCapabilitiesTest {

    private static final String QUERY = "\033[>0q\033[?25l\033[s\033[6n\033[?2027$p"
            + "\033[H\033]66;w=1; \033\\\033[6n\033[H\033]66;s=2; \033\\\033[6n\033[u\033[c";

    @After
    public void restoreWidthMethod() {
        UnicodeWidth.setWidthMethod(WidthMethod.UNICODE);
    }

    private static ANSITerminal terminal(InputStream input, ByteArrayOutputStream output) {
        return new ANSITerminal(input, output, StandardCharsets.UTF_8) {};
    }

    private static InputStream replies(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static TerminalCapabilities emptyEnvironment() {
        return new TerminalCapabilities(Collections.<String, String>emptyMap());
    }

    private static int count(String text, String part) {
        int count = 0;
        for(int index = text.indexOf(part); index >= 0; index = text.indexOf(part, index + 1)) {
            count++;
        }
        return count;
    }

    @Test
    public void detectsCapabilitiesWhenEnteringPrivateMode() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ANSITerminal terminal = terminal(replies("x\033P>|kitty(0.40.1)\033\\\033[3;4R\033[?2027;2$y"
                + "\033[1;2R\033[1;3R\033[9;9R\033[?62;22c"), output);
        TerminalCapabilities capabilities = emptyEnvironment();
        terminal.setTerminalCapabilities(capabilities);
        assertSame(capabilities, terminal.getTerminalCapabilities());

        terminal.enterPrivateMode();

        String control = output.toString(StandardCharsets.UTF_8.name());
        assertTrue(control.startsWith(QUERY + "\033[?1049h"));
        // opentui writes the mode after the query and after each reply until explicit width is known
        assertEquals(4, count(control, TerminalCapabilities.UNICODE_SET));
        assertEquals("kitty", capabilities.getTerminalName());
        assertTrue(capabilities.isExplicitWidth());
        assertTrue(capabilities.isScaledText());
        assertFalse(capabilities.isAwaitingCursorPositionReports());
        assertEquals(WidthMethod.UNICODE, UnicodeWidth.getWidthMethod());

        KeyStroke key = terminal.pollInput();
        assertEquals(KeyType.Character, key.getKeyType());
        assertEquals(Character.valueOf('x'), key.getCharacter());
        assertNull(terminal.pollInput());
        terminal.exitPrivateMode();
    }

    @Test
    public void stopsWaitingAfterTheTimeout() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ANSITerminal terminal = terminal(replies(""), output);
        TerminalCapabilities capabilities = new TerminalCapabilities(Collections.singletonMap("TERM", "tmux-256color"));
        terminal.setTerminalCapabilities(capabilities);
        terminal.setCapabilityQueryTimeout(20);

        terminal.enterPrivateMode();

        assertTrue(capabilities.isAwaitingCursorPositionReports());
        assertEquals(0, count(output.toString(StandardCharsets.UTF_8.name()), TerminalCapabilities.UNICODE_SET));
        assertEquals(WidthMethod.WCWIDTH, UnicodeWidth.getWidthMethod());
    }

    @Test
    public void processesLateRepliesAsInputArrives() throws Exception {
        PipedOutputStream terminalSide = new PipedOutputStream();
        PipedInputStream input = new PipedInputStream(terminalSide);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ANSITerminal terminal = terminal(input, output);
        TerminalCapabilities capabilities = emptyEnvironment();
        terminal.setTerminalCapabilities(capabilities);
        terminal.setCapabilityQueryTimeout(20);
        terminal.enterPrivateMode();
        assertEquals(WidthMethod.UNICODE, UnicodeWidth.getWidthMethod());

        terminalSide.write("\033P>|tmux 3.4\033\\\033[1;2Ry".getBytes(StandardCharsets.UTF_8));
        terminalSide.flush();
        KeyStroke key = null;
        for(int attempt = 0; attempt < 1000 && key == null; attempt++) {
            key = terminal.pollInput();
            if(key == null) {
                Thread.sleep(1);
            }
        }

        assertEquals(Character.valueOf('y'), key.getCharacter());
        assertEquals(Multiplexer.TMUX, capabilities.getMultiplexer());
        assertEquals(WidthMethod.WCWIDTH, UnicodeWidth.getWidthMethod());
        // the cursor report came after the wait, so it can answer the application and does not count as a probe
        assertFalse(capabilities.isExplicitWidth());
        assertTrue(capabilities.isAwaitingCursorPositionReports());
    }

    @Test
    public void waitsForCursorPositionReports() throws Exception {
        ANSITerminal terminal = terminal(replies("\033[5;10R"), new ByteArrayOutputStream());
        assertEquals(new TerminalPosition(9, 4), terminal.getCursorPosition());

        terminal = terminal(replies(""), new ByteArrayOutputStream());
        assertNull(terminal.waitForCursorPositionReport(20));
    }

    @Test
    public void givesRepliesToTheApplicationWithoutDetection() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ANSITerminal terminal = terminal(replies("\033[?62;22c"), output);
        assertNull(terminal.getTerminalCapabilities());

        terminal.enterPrivateMode();
        assertFalse(output.toString(StandardCharsets.UTF_8.name()).contains("\033[c"));

        KeyStroke key = terminal.pollInput();
        assertEquals(KeyType.TerminalResponse, key.getKeyType());
        assertEquals("\033[?62;22c", ((TerminalResponse) key).getSequence());
    }
}
