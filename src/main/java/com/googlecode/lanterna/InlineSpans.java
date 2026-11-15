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
 * Copyright (C) 2010-2020 Martin Berglund
 */
package com.googlecode.lanterna;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Inline spans: zero-width style toggles inside one line of text. Each style
 * has an ON and an OFF sentinel in the private-use range U+E110..U+E11B. A
 * sentinel is never painted and has no columns. This class is the one owner
 * of the range: the measures of {@link TerminalTextUtils} read it from here.
 */
public final class InlineSpans {
    private InlineSpans() {
    }

    public static final char BOLD_ON = 0xE110;
    public static final char BOLD_OFF = 0xE111;
    public static final char ITALIC_ON = 0xE112;
    public static final char ITALIC_OFF = 0xE113;
    public static final char STRIKE_ON = 0xE114;
    public static final char STRIKE_OFF = 0xE115;
    /** A code span paints with the code colours and no modifiers. */
    public static final char CODE_ON = 0xE116;
    public static final char CODE_OFF = 0xE117;
    /** A link span is underlined. */
    public static final char LINK_ON = 0xE118;
    public static final char LINK_OFF = 0xE119;
    /** An error span paints with the error foreground. */
    public static final char ERROR_ON = 0xE11A;
    public static final char ERROR_OFF = 0xE11B;

    private static final int STYLE_COUNT = (ERROR_OFF - BOLD_ON + 1) / 2;
    private static final int CODE_BIT = 1 << ((CODE_ON - BOLD_ON) / 2);
    private static final int ERROR_BIT = 1 << ((ERROR_ON - BOLD_ON) / 2);
    /** Modifier of each style, in sentinel order; null for a style that changes colours. */
    private static final SGR[] MODIFIERS = {SGR.BOLD, SGR.ITALIC, SGR.CROSSED_OUT, null, SGR.UNDERLINE, null};

    /**
     * @param c char to test
     * @return true when {@code c} is an inline-span sentinel
     */
    public static boolean isSentinel(char c) {
        return c >= BOLD_ON && c <= ERROR_OFF;
    }

    /**
     * @param s text to scan (nullable)
     * @return true when {@code s} holds one sentinel or more
     */
    public static boolean contains(CharSequence s) {
        return s != null && firstSentinel(s, 0) < s.length();
    }

    /**
     * Removes each sentinel. Returns {@code s} itself when it holds none.
     * @param s text to strip (nullable)
     * @return {@code s} without sentinels, or {@code ""} for {@code null}
     */
    public static String strip(String s) {
        if (s == null) {
            return "";
        }
        final int n = s.length();
        int i = firstSentinel(s, 0);
        if (i == n) {
            return s;
        }
        final StringBuilder out = new StringBuilder(n);
        out.append(s, 0, i);
        for (; i < n; i++) {
            final char c = s.charAt(i);
            if (!isSentinel(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Resolves {@code line} to the cells that a painter writes. A sentinel makes
     * no cell; it changes the style of the cells after it. Outside a code span a
     * cell has {@code fg} ({@code errorFg} in an error span), {@code bg} and
     * {@code modifiers} plus the modifiers of the open spans. In a code span a
     * cell has {@code codeFg}, {@code codeBg} and no modifiers. An OFF sentinel
     * without its ON sentinel does nothing.
     * @param line text with sentinels
     * @param fg foreground outside code and error spans
     * @param bg background outside code spans
     * @param codeFg foreground of a code span
     * @param codeBg background of a code span
     * @param errorFg foreground of an error span; {@code null} means {@code fg}
     * @param modifiers modifiers that are active before the line; not changed
     * @return the cells of the line, in order
     */
    public static TextCharacter[] cells(String line, TextColor fg, TextColor bg, TextColor codeFg,
            TextColor codeBg, TextColor errorFg, EnumSet<SGR> modifiers) {
        final int n = line.length();
        final List<TextCharacter> out = new ArrayList<>(n);
        final EnumSet<SGR> none = EnumSet.noneOf(SGR.class);
        final TextColor error = errorFg == null ? fg : errorFg;
        int open = 0;
        int runStart = 0;
        for (int i = firstSentinel(line, 0); ; i = firstSentinel(line, i + 1)) {
            if (i > runStart) {
                final String run = line.substring(runStart, i);
                final TextCharacter[] runCells;
                if ((open & CODE_BIT) != 0) {
                    runCells = TextCharacter.fromString(run, codeFg, codeBg, none);
                } else {
                    runCells = TextCharacter.fromString(run, (open & ERROR_BIT) != 0 ? error : fg, bg,
                            withOpenModifiers(modifiers, open));
                }
                for (TextCharacter cell : runCells) {
                    out.add(cell);
                }
            }
            if (i >= n) {
                break;
            }
            open = apply(open, line.charAt(i));
            runStart = i + 1;
        }
        return out.toArray(new TextCharacter[0]);
    }

    /**
     * Makes each line of a wrapped text open and close its own spans. A line
     * that starts in an open span gets the ON sentinels of that span in front;
     * a line that ends in an open span gets the OFF sentinels at its end. Thus a
     * painter can paint each line alone. The added sentinels have no columns. A
     * line that needs no sentinel stays the same instance.
     * @param lines the lines of one wrapped text, in order
     * @return a new list with one line for each input line
     */
    public static List<String> carry(List<String> lines) {
        final List<String> out = new ArrayList<>(lines.size());
        int open = 0;
        for (String line : lines) {
            final int before = open;
            final int n = line.length();
            for (int i = firstSentinel(line, 0); i < n; i = firstSentinel(line, i + 1)) {
                open = apply(open, line.charAt(i));
            }
            if ((before | open) == 0) {
                out.add(line);
                continue;
            }
            final StringBuilder sb = new StringBuilder(n + 2 * STYLE_COUNT);
            for (int style = 0; style < STYLE_COUNT; style++) {
                if ((before & (1 << style)) != 0) {
                    sb.append((char) (BOLD_ON + 2 * style));
                }
            }
            sb.append(line);
            for (int style = STYLE_COUNT - 1; style >= 0; style--) {
                if ((open & (1 << style)) != 0) {
                    sb.append((char) (BOLD_OFF + 2 * style));
                }
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** Index of the first sentinel of {@code s} at or after {@code from}, or the length of {@code s}. */
    private static int firstSentinel(CharSequence s, int from) {
        final int n = s.length();
        int i = from;
        while (i < n && !isSentinel(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The open-span bits after {@code sentinel}: an ON sentinel sets the bit of its style, an OFF sentinel clears it. */
    private static int apply(int open, char sentinel) {
        final int offset = sentinel - BOLD_ON;
        final int bit = 1 << (offset >> 1);
        return (offset & 1) == 0 ? open | bit : open & ~bit;
    }

    private static EnumSet<SGR> withOpenModifiers(EnumSet<SGR> modifiers, int open) {
        final EnumSet<SGR> result = EnumSet.copyOf(modifiers);
        for (int style = 0; style < STYLE_COUNT; style++) {
            if ((open & (1 << style)) != 0 && MODIFIERS[style] != null) {
                result.add(MODIFIERS[style]);
            }
        }
        return result;
    }
}
