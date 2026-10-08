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
package com.googlecode.lanterna.input;

import com.googlecode.lanterna.input.CharacterPattern.Matching;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TerminalResponsePatternTest {

    private final TerminalResponsePattern pattern = new TerminalResponsePattern();

    private Matching match(String text) {
        List<Character> seq = new ArrayList<>();
        for(char c: text.toCharArray()) {
            seq.add(c);
        }
        return pattern.match(seq);
    }

    private void assertResponse(String text) {
        Matching matching = match(text);
        assertEquals(KeyType.TerminalResponse, matching.fullMatch.getKeyType());
        assertEquals(text, ((TerminalResponse) matching.fullMatch).getSequence());
    }

    private void assertPartial(String text) {
        assertSame(Matching.NOT_YET, match(text));
    }

    @Test
    public void matchesModeReports() {
        assertResponse("\033[?2027;2$y");
        assertResponse("\033[?$y");
        assertPartial("\033");
        assertPartial("\033[");
        assertPartial("\033[?");
        assertPartial("\033[?2027;2");
        assertPartial("\033[?2027;2$");
        assertNull(match("\033[2027$y"));
        assertNull(match("\033[?2027$x"));
        assertNull(match("\033[?2027$yy"));
        assertNull(match("\033[?2027x"));
    }

    @Test
    public void matchesPrimaryDeviceAttributes() {
        assertResponse("\033[?62;22c");
        assertNull(match("\033[?62cc"));
        assertTrue(((TerminalResponse) match("\033[?1;2c").fullMatch).isPrimaryDeviceAttributes());
        assertFalse(((TerminalResponse) match("\033[?2027;2$y").fullMatch).isPrimaryDeviceAttributes());
    }

    @Test
    public void matchesTerminalVersions() {
        assertResponse("\033P>|kitty(0.40.1)\033\\");
        assertResponse("\033P>|\033\\");
        assertPartial("\033P");
        assertPartial("\033P>");
        assertPartial("\033P>|WezTerm 2024");
        assertPartial("\033P>|tmux 3.4\033");
        assertNull(match("\033P<|"));
        assertNull(match("\033P>>"));
        assertNull(match("\033P>|tmux\033x"));
        assertNull(match("\033P>|tmux\033\\x"));
        assertNull(match("\033P>|tmux\n"));
        assertNull(match("\033P>|tmux\u007f"));
        assertFalse(((TerminalResponse) match("\033P>|ab\033\\").fullMatch).isPrimaryDeviceAttributes());
    }

    @Test
    public void rejectsOtherInput() {
        assertNull(match("a"));
        assertNull(match("\033O"));
        StringBuilder longReply = new StringBuilder("\033P>|");
        while(longReply.length() < 257) {
            longReply.append('x');
        }
        assertNull(match(longReply.toString()));
        assertPartial(longReply.substring(0, 256));
    }

    @Test
    public void printsTheSequenceWithoutRawEscapes() {
        TerminalResponse response = new TerminalResponse("\033[?62c");
        assertEquals("TerminalResponse{sequence=\\e[?62c}", response.toString());
        assertTrue(new TerminalResponse("\033[?c").isPrimaryDeviceAttributes());
        assertFalse(new TerminalResponse("\033[?62").isPrimaryDeviceAttributes());
    }
}
