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
package com.googlecode.lanterna;

import static org.junit.Assert.*;
import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

public class InlineSpansTest {
    private static final TextColor FG = TextColor.ANSI.WHITE;
    private static final TextColor BG = TextColor.ANSI.BLACK;
    private static final TextColor CODE_FG = TextColor.ANSI.CYAN;
    private static final TextColor CODE_BG = TextColor.ANSI.BLUE;
    private static final TextColor ERROR_FG = TextColor.ANSI.RED;

    private static TextCharacter[] cells(String line, EnumSet<SGR> modifiers) {
        return InlineSpans.cells(line, FG, BG, CODE_FG, CODE_BG, ERROR_FG, modifiers);
    }

    private static TextCharacter cell(char c, TextColor fg, TextColor bg, SGR... modifiers) {
        return TextCharacter.fromCharacter(c, fg, bg, modifiers)[0];
    }

    @Test
    public void stripAndContainsCoverEachSentinel() {
        String clean = "no spans";
        assertFalse(InlineSpans.contains(clean));
        assertFalse(InlineSpans.contains(null));
        assertSame("clean text: same instance", clean, InlineSpans.strip(clean));
        for (char c = InlineSpans.BOLD_ON; c <= InlineSpans.ERROR_OFF; c++) {
            String s = "a" + c + "b";
            assertTrue(InlineSpans.contains(s));
            assertEquals("ab", InlineSpans.strip(s));
        }
        assertFalse("the char before the range", InlineSpans.isSentinel((char) (InlineSpans.BOLD_ON - 1)));
        assertFalse("the char after the range", InlineSpans.isSentinel((char) (InlineSpans.ERROR_OFF + 1)));
    }

    @Test
    public void cellsCarryTheStyleOfTheOpenSpans() {
        String line = "a" + InlineSpans.BOLD_ON + "b" + InlineSpans.ITALIC_ON + "c" + InlineSpans.BOLD_OFF
                + InlineSpans.ITALIC_OFF + InlineSpans.STRIKE_ON + "d" + InlineSpans.STRIKE_OFF
                + InlineSpans.LINK_ON + "e" + InlineSpans.LINK_OFF + InlineSpans.ERROR_ON + "f"
                + InlineSpans.ERROR_OFF + "g";
        TextCharacter[] expected = {
                cell('a', FG, BG),
                cell('b', FG, BG, SGR.BOLD),
                cell('c', FG, BG, SGR.BOLD, SGR.ITALIC),
                cell('d', FG, BG, SGR.CROSSED_OUT),
                cell('e', FG, BG, SGR.UNDERLINE),
                cell('f', ERROR_FG, BG),
                cell('g', FG, BG)};
        assertArrayEquals(expected, cells(line, EnumSet.noneOf(SGR.class)));
    }

    @Test
    public void codeSpanUsesCodeColoursAndNoModifiers() {
        EnumSet<SGR> inherited = EnumSet.of(SGR.REVERSE);
        String line = InlineSpans.BOLD_ON + "a" + InlineSpans.CODE_ON + "b" + InlineSpans.CODE_OFF + "c";
        TextCharacter[] expected = {
                cell('a', FG, BG, SGR.REVERSE, SGR.BOLD),
                cell('b', CODE_FG, CODE_BG),
                cell('c', FG, BG, SGR.REVERSE, SGR.BOLD)};
        assertArrayEquals(expected, cells(line, inherited));
        assertEquals("the caller's set is not changed", EnumSet.of(SGR.REVERSE), inherited);
    }

    @Test
    public void cellsAgreeWithTheMeasuredColumns() {
        String line = InlineSpans.BOLD_ON + "\u4e2d" + InlineSpans.BOLD_OFF + "x" + InlineSpans.ERROR_OFF;
        int columns = 0;
        for (TextCharacter tc : cells(line, EnumSet.noneOf(SGR.class))) {
            columns += tc.isDoubleWidth() ? 2 : 1;
        }
        assertEquals(3, columns);
        assertEquals(columns, TerminalTextUtils.displayColumns(line));
        assertEquals(0, cells("" + InlineSpans.CODE_ON + InlineSpans.CODE_OFF, EnumSet.noneOf(SGR.class)).length);
        assertEquals("a missing error colour means the base colour",
                cell('x', FG, BG),
                InlineSpans.cells(InlineSpans.ERROR_ON + "x", FG, BG, CODE_FG, CODE_BG, null, EnumSet.noneOf(SGR.class))[0]);
    }

    @Test
    public void carryMakesEachWrappedLineOpenAndCloseItsSpans() {
        String closed = "plain";
        List<String> lines = Arrays.asList(
                closed,
                "a" + InlineSpans.CODE_ON + "b",
                "c" + InlineSpans.BOLD_ON,
                "d" + InlineSpans.CODE_OFF + InlineSpans.ERROR_ON,
                "e" + InlineSpans.BOLD_OFF + InlineSpans.ERROR_OFF,
                "f");
        List<String> carried = InlineSpans.carry(lines);
        assertEquals(Arrays.asList(
                "plain",
                "a" + InlineSpans.CODE_ON + "b" + InlineSpans.CODE_OFF,
                InlineSpans.CODE_ON + "c" + InlineSpans.BOLD_ON + InlineSpans.CODE_OFF + InlineSpans.BOLD_OFF,
                "" + InlineSpans.BOLD_ON + InlineSpans.CODE_ON + "d" + InlineSpans.CODE_OFF + InlineSpans.ERROR_ON
                        + InlineSpans.ERROR_OFF + InlineSpans.BOLD_OFF,
                "" + InlineSpans.BOLD_ON + InlineSpans.ERROR_ON + "e" + InlineSpans.BOLD_OFF + InlineSpans.ERROR_OFF,
                "f"), carried);
        assertSame("a line outside each span stays the same instance", closed, carried.get(0));
        assertSame(lines.get(5), carried.get(5));
        for (int i = 0; i < lines.size(); i++) {
            assertEquals("carried sentinels have no columns",
                    TerminalTextUtils.displayColumns(lines.get(i)), TerminalTextUtils.displayColumns(carried.get(i)));
        }
    }
}
