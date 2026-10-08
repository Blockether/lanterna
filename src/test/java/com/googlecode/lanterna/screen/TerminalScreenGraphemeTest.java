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
package com.googlecode.lanterna.screen;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.UnicodeWidth;
import com.googlecode.lanterna.UnicodeWidth.WidthMethod;
import com.googlecode.lanterna.terminal.ansi.ANSITerminal;
import com.googlecode.lanterna.terminal.ansi.TerminalCapabilities;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TerminalScreenGraphemeTest {

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @After
    public void restoreWidthMethod() {
        UnicodeWidth.setWidthMethod(WidthMethod.UNICODE);
    }

    private TerminalScreen screen(String... environment) throws IOException {
        ANSITerminal terminal = new ANSITerminal(new ByteArrayInputStream(new byte[0]), output,
                StandardCharsets.UTF_8) {
            @Override
            protected TerminalSize findTerminalSize() {
                return new TerminalSize(10, 3);
            }

            @Override
            public TerminalPosition getCursorPosition() {
                return TerminalPosition.TOP_LEFT_CORNER;
            }
        };
        TerminalScreen screen = new TerminalScreen(terminal);
        screen.startScreen();
        if(environment.length > 0) {
            TerminalCapabilities capabilities = new TerminalCapabilities(
                    Collections.singletonMap(environment[0], environment[1]));
            capabilities.checkEnvironmentOverrides();
            terminal.setTerminalCapabilities(capabilities);
        }
        output.reset();
        return screen;
    }

    private static TextCharacter cell(String text) {
        return TextCharacter.fromString(text)[0];
    }

    private String drawn() throws IOException {
        String text = output.toString(StandardCharsets.UTF_8.name());
        output.reset();
        return text;
    }

    @Test
    public void wrapsGraphemesInExplicitWidths() throws IOException {
        TerminalScreen screen = screen("OPENTUI_FORCE_EXPLICIT_WIDTH", "1");
        screen.setCharacter(0, 0, cell("é"));
        screen.setCharacter(1, 0, cell("a"));
        screen.setCharacter(2, 0, cell("中"));
        screen.refresh();
        String full = drawn();
        assertTrue(full.contains("\033[2J"));
        assertTrue(full.contains("\033]66;w=1;é\033\\a"));
        assertTrue(full.contains("\033]66;w=2;中\033\\"));

        screen.setCharacter(4, 1, cell("ü"));
        screen.setCharacter(5, 1, cell("ü"));
        screen.setCharacter(6, 1, cell("x"));
        screen.setCharacter(7, 1, cell("y"));
        screen.refresh();
        String delta = drawn();
        assertFalse(delta.contains("\033[2J"));
        assertTrue(delta.contains("\033]66;w=1;ü\033\\\033]66;w=1;ü\033\\xy"));
    }

    @Test
    public void movesTheCursorAfterGraphemesForExplicitPositioning() throws IOException {
        TerminalScreen screen = screen("TERM", "xterm-alacritty");
        screen.setCharacter(0, 0, cell("é"));
        screen.setCharacter(9, 0, cell("é"));
        screen.refresh();
        String full = drawn();
        assertTrue(full.contains("é\033[1;2H"));
        assertFalse(full.contains("é\033[1;11H"));
        assertFalse(full.contains("\033]66"));

        screen.setCharacter(3, 2, cell("ö"));
        screen.setCharacter(9, 2, cell("ö"));
        screen.refresh();
        String delta = drawn();
        assertTrue(delta.contains("ö\033[3;5H"));
        assertFalse(delta.contains("ö\033[3;11H"));
    }

    @Test
    public void writesGraphemesAsTextForOtherTerminals() throws IOException {
        for(TerminalScreen screen: new TerminalScreen[] {screen(), screen("TERM", "xterm-256color")}) {
            screen.setCharacter(0, 0, cell("é"));
            screen.setCharacter(1, 0, cell("é"));
            screen.refresh();
            assertTrue(drawn().contains("éé"));
            screen.setCharacter(0, 1, cell("ö"));
            screen.setCharacter(1, 1, cell("ö"));
            screen.refresh();
            assertTrue(drawn().contains("öö"));
        }

        DefaultVirtualTerminal virtualTerminal = new DefaultVirtualTerminal(new TerminalSize(10, 3));
        TerminalScreen screen = new TerminalScreen(virtualTerminal);
        screen.startScreen();
        screen.setCharacter(0, 0, cell("é"));
        screen.refresh();
        assertEquals("é", virtualTerminal.getCharacter(0, 0).getCharacterString());
    }

    @Test
    public void paintsEveryCellAgainWhenTheWidthMethodChanges() throws IOException {
        TerminalScreen screen = screen();
        screen.setCharacter(0, 0, cell("a"));
        screen.refresh();
        assertTrue(drawn().contains("\033[2J"));
        screen.refresh();
        assertFalse(drawn().contains("\033[2J"));

        UnicodeWidth.setWidthMethod(WidthMethod.WCWIDTH);
        screen.refresh();
        assertTrue(drawn().contains("\033[2J"));
        screen.refresh(Screen.RefreshType.DELTA);
        assertFalse(drawn().contains("\033[2J"));
        screen.refresh(Screen.RefreshType.COMPLETE);
        assertTrue(drawn().contains("\033[2J"));
    }
}
