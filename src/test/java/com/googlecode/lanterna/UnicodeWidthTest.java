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

import com.googlecode.lanterna.UnicodeWidth.WidthMethod;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Checks {@link UnicodeWidth} against the OpenTUI 0.5.16 native library. The golden files come from the
 * real {@code libopentui} renderer: each grapheme is drawn into an OpenTUI buffer and its cells are counted.
 */
public class UnicodeWidthTest {

    private static final WidthMethod[] OPENTUI_METHODS = {
            WidthMethod.WCWIDTH, WidthMethod.UNICODE, WidthMethod.NO_ZWJ, WidthMethod.UNICODE_WIDE};

    private static List<String> lines(String name) throws IOException {
        List<String> lines = new ArrayList<>();
        try (InputStream in = UnicodeWidthTest.class.getResourceAsStream(name);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    private static String fromHex(String hex) {
        StringBuilder text = new StringBuilder();
        for (String token : hex.trim().split("\\s+")) {
            text.appendCodePoint(Integer.parseInt(token, 16));
        }
        return text.toString();
    }

    /** OpenTUI cell widths of the visible graphemes; a buffer cell holds at most 4 columns. */
    private static String cellWidths(String text, WidthMethod method) {
        StringBuilder widths = new StringBuilder();
        for (int begin = 0; begin < text.length(); ) {
            int end = UnicodeWidth.graphemeEnd(text, begin);
            int width = UnicodeWidth.graphemeWidth(text.substring(begin, end), method);
            if (width > 0) {
                widths.append(widths.length() == 0 ? "" : ",").append(Math.min(width, 4));
            }
            begin = end;
        }
        return widths.toString();
    }

    @Test
    public void singleCodePointWidthsMatchOpenTui() throws IOException {
        List<String> runs = lines("opentui-codepoint-widths.txt");
        int checked = 0;
        for (int i = 0; i < runs.size(); i++) {
            String[] run = runs.get(i).split(" ");
            int first = Integer.parseInt(run[0], 16);
            int width = Integer.parseInt(run[1]);
            int last = i + 1 < runs.size() ? Integer.parseInt(runs.get(i + 1).split(" ")[0], 16) - 1 : 0x10FFFF;
            if (width < 0) {
                continue;
            }
            for (int codePoint = first; codePoint <= last; codePoint++) {
                String text = new String(Character.toChars(codePoint));
                assertEquals(Integer.toHexString(codePoint), width, UnicodeWidth.codePointWidth(codePoint));
                for (WidthMethod method : OPENTUI_METHODS) {
                    assertEquals(Integer.toHexString(codePoint) + " " + method,
                            width, UnicodeWidth.graphemeWidth(text, method));
                }
                checked++;
            }
        }
        assertTrue(checked > 1_000_000);
    }

    @Test
    public void graphemeSequenceWidthsMatchOpenTui() throws IOException {
        List<String> rows = lines("opentui-sequence-widths.txt");
        for (String row : rows) {
            String[] fields = row.split(";", -1);
            String text = fromHex(fields[1]);
            for (char method : fields[0].toCharArray()) {
                WidthMethod widthMethod = WidthMethod.values()[method - '0'];
                assertEquals(fields[1] + " " + widthMethod, fields[2], cellWidths(text, widthMethod));
                if (widthMethod == WidthMethod.UNICODE) {
                    assertEquals(fields[1], fields[2], cellWidths(text, WidthMethod.NO_ZWJ));
                }
            }
        }
        assertTrue(rows.size() > 5000);
    }

    @Test
    public void graphemeBoundariesFollowUnicodeTestDataWithOpenTuiTailoring() throws IOException {
        List<String> cases = lines("GraphemeBreakTest.txt");
        for (String line : cases) {
            String data = line.split("#", 2)[0].trim();
            List<Integer> codePoints = new ArrayList<>();
            List<Boolean> breaks = new ArrayList<>();
            for (String token : data.split("\\s+")) {
                if (token.equals("\u00F7") || token.equals("\u00D7")) {
                    breaks.add(token.equals("\u00F7"));
                } else {
                    codePoints.add(Integer.parseInt(token, 16));
                }
            }
            int[] state = {UnicodeWidth.STATE_DEFAULT};
            for (int i = 1; i < codePoints.size(); i++) {
                int previous = codePoints.get(i - 1);
                int current = codePoints.get(i);
                boolean expected = breaks.get(i);
                // uucode (OpenTUI) tailoring: an emoji modifier only extends an emoji modifier base.
                if (UnicodeWidth.graphemeBreak(current) == UnicodeWidth.GB_EMOJI_MODIFIER
                        && UnicodeWidth.graphemeBreak(previous) != UnicodeWidth.GB_EMOJI_MODIFIER_BASE) {
                    expected = true;
                }
                assertEquals(data + " at " + i, expected, UnicodeWidth.isGraphemeBreak(previous, current, state));
            }
        }
        assertTrue(cases.size() > 700);
    }

    @Test
    public void graphemeEndKeepsClustersTogether() {
        String text = "e\u0301\uD83D\uDC68\u200D\uD83D\uDC69x\uD83C\uDDF5\uD83C\uDDF1\uD83C\uDDE9";
        int first = UnicodeWidth.graphemeEnd(text, 0);
        assertEquals(2, first);
        int family = UnicodeWidth.graphemeEnd(text, first);
        assertEquals(7, family);
        assertEquals(8, UnicodeWidth.graphemeEnd(text, family));
        assertEquals(12, UnicodeWidth.graphemeEnd(text, 8));
        assertEquals(14, UnicodeWidth.graphemeEnd(text, 12));
        assertEquals(1, UnicodeWidth.graphemeEnd("\r\n".substring(1), 0));
        assertEquals(2, UnicodeWidth.graphemeEnd("\r\n", 0));
    }

    @Test
    public void loneSurrogatesAreReplacementCharacters() {
        // A lone surrogate decodes as U+FFFD, which always starts and ends a cluster.
        assertEquals(1, UnicodeWidth.graphemeEnd("\uD800\u0301", 0));
        assertEquals(1, UnicodeWidth.graphemeEnd("a\uDC00", 0));
        assertEquals(1, UnicodeWidth.graphemeEnd("\uFFFD\u0301", 0));
        assertEquals(UnicodeWidth.graphemeWidth("\uFFFD", WidthMethod.UNICODE),
                UnicodeWidth.graphemeWidth("\uDC00", WidthMethod.UNICODE));
    }

    @Test
    public void codePointWidthOutsideUnicodeIsZero() {
        assertEquals(0, UnicodeWidth.codePointWidth(-1));
        assertEquals(0, UnicodeWidth.codePointWidth(0x110000));
        assertEquals(0, UnicodeWidth.codePointWidth(0));
        assertEquals(0, UnicodeWidth.codePointWidth(0x07));
        assertEquals(0, UnicodeWidth.codePointWidth(0x85));
        assertEquals(1, UnicodeWidth.codePointWidth('A'));
        assertEquals(2, UnicodeWidth.codePointWidth(0x4E00));
    }

    @Test
    public void wcwidthAddsCodePointWidthsWithoutALimit() {
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
        assertEquals(6, UnicodeWidth.graphemeWidth(family, WidthMethod.WCWIDTH));
        assertEquals(2, UnicodeWidth.graphemeWidth(family, WidthMethod.UNICODE));
    }

    @Test
    public void widthMethodIsGlobalAndNotNull() {
        WidthMethod previous = UnicodeWidth.getWidthMethod();
        try {
            assertEquals(WidthMethod.UNICODE, previous);
            UnicodeWidth.setWidthMethod(WidthMethod.UNICODE_WIDE);
            assertEquals(WidthMethod.UNICODE_WIDE, UnicodeWidth.getWidthMethod());
            try {
                UnicodeWidth.setWidthMethod(null);
                fail("null width method");
            } catch (NullPointerException expected) {
                assertEquals("method", expected.getMessage());
            }
            assertEquals(WidthMethod.UNICODE_WIDE, UnicodeWidth.getWidthMethod());
        } finally {
            UnicodeWidth.setWidthMethod(previous);
        }
    }

    private static boolean keeps(int state, int gb) {
        switch (state) {
            case UnicodeWidth.STATE_REGIONAL_INDICATOR:
                return gb == UnicodeWidth.GB_REGIONAL_INDICATOR;
            case UnicodeWidth.STATE_EXTENDED_PICTOGRAPHIC:
                return gb == UnicodeWidth.GB_INCB_EXTEND || gb == UnicodeWidth.GB_INCB_LINKER
                        || gb == UnicodeWidth.GB_ZWNJ || gb == UnicodeWidth.GB_ZWJ
                        || gb == UnicodeWidth.GB_EXTENDED_PICTOGRAPHIC
                        || gb == UnicodeWidth.GB_EMOJI_MODIFIER_BASE || gb == UnicodeWidth.GB_EMOJI_MODIFIER;
            default:
                return gb == UnicodeWidth.GB_INCB_CONSONANT || gb == UnicodeWidth.GB_INCB_LINKER
                        || gb == UnicodeWidth.GB_INCB_EXTEND || gb == UnicodeWidth.GB_ZWJ;
        }
    }

    @Test
    public void staleBreakStateActsAsTheDefaultState() {
        int[] states = {UnicodeWidth.STATE_REGIONAL_INDICATOR, UnicodeWidth.STATE_EXTENDED_PICTOGRAPHIC,
                UnicodeWidth.STATE_INCB_CONSONANT, UnicodeWidth.STATE_INCB_LINKER};
        int checked = 0;
        for (int state : states) {
            for (int gb1 = UnicodeWidth.GB_OTHER; gb1 <= UnicodeWidth.GB_INCB_CONSONANT; gb1++) {
                for (int gb2 = UnicodeWidth.GB_OTHER; gb2 <= UnicodeWidth.GB_INCB_CONSONANT; gb2++) {
                    if (keeps(state, gb1) && keeps(state, gb2)) {
                        continue;
                    }
                    int[] stale = {state};
                    int[] fresh = {UnicodeWidth.STATE_DEFAULT};
                    String pair = state + ":" + gb1 + "," + gb2;
                    assertEquals(pair, UnicodeWidth.computeGraphemeBreak(gb1, gb2, fresh),
                            UnicodeWidth.computeGraphemeBreak(gb1, gb2, stale));
                    assertEquals(pair, fresh[0], stale[0]);
                    checked++;
                }
            }
        }
        assertTrue(checked > 1000);
    }

    @Test
    public void graphemeWidthEdgeCases() {
        for (WidthMethod method : OPENTUI_METHODS) {
            assertEquals(0, UnicodeWidth.graphemeWidth("\u0007", method));
            assertEquals(0, UnicodeWidth.graphemeWidth("\u007F", method));
            assertEquals(0, UnicodeWidth.graphemeWidth("\t", method));
        }
        // VS16 widens only a narrow cluster that already has a width.
        assertEquals(0, UnicodeWidth.graphemeWidth("\u200B\uFE0F", WidthMethod.UNICODE));
        assertEquals(2, UnicodeWidth.graphemeWidth("\u2764\uFE0F", WidthMethod.UNICODE));
        assertEquals(2, UnicodeWidth.graphemeWidth("\u4E00\uFE0F", WidthMethod.UNICODE));
        // Zero-width extensions keep a zero-width cluster at zero.
        assertEquals(0, UnicodeWidth.graphemeWidth("\u200B\u200C", WidthMethod.UNICODE));
        assertEquals(0, UnicodeWidth.graphemeWidth("\u200B\u200C", WidthMethod.UNICODE_WIDE));
        // unicode_wide: only an extension that is not zero-width in a grapheme widens the cluster.
        assertEquals(1, UnicodeWidth.graphemeWidth("a\u200D", WidthMethod.UNICODE_WIDE));
        assertEquals(2, UnicodeWidth.graphemeWidth("a\u0E33", WidthMethod.UNICODE_WIDE));
        // A spacing mark widens the cluster only when the mark has a width.
        assertEquals(1, UnicodeWidth.graphemeWidth("a\u0903", WidthMethod.UNICODE));
        assertEquals(2, UnicodeWidth.graphemeWidth("a\u0E33", WidthMethod.UNICODE));
        // Devanagari conjuncts: a virama adds the next consonant, except RA.
        assertEquals(2, UnicodeWidth.graphemeWidth("\u0915\u094D\u0937", WidthMethod.UNICODE));
        assertEquals(1, UnicodeWidth.graphemeWidth("\u0915\u094D\u0930", WidthMethod.UNICODE));
        assertEquals(1, UnicodeWidth.graphemeWidth("\u0915\u0915", WidthMethod.UNICODE));
        assertEquals(1, UnicodeWidth.graphemeWidth("\u0915\u094Da", WidthMethod.UNICODE));
        assertEquals(1, UnicodeWidth.graphemeWidth("\u094D\u0915", WidthMethod.UNICODE));
    }

    @Test
    public void devanagariBasesHaveOneColumn() {
        // graphemeWidth relies on this: each Devanagari base adds its width after a virama.
        for (int codePoint = 0x0915; codePoint <= 0x095F; codePoint++) {
            if (codePoint <= 0x0939 || codePoint >= 0x0958) {
                assertEquals(Integer.toHexString(codePoint), 1, UnicodeWidth.codePointWidth(codePoint));
            }
        }
    }
}
