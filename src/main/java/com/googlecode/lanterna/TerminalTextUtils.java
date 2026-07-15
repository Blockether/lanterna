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
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedList;
import java.util.List;

import com.googlecode.lanterna.graphics.StyleSet;
import com.googlecode.lanterna.screen.TabBehaviour;

/**
 * This class contains a number of utility methods for analyzing characters and strings in a terminal context. The main
 * purpose is to make it easier to work with text that may or may not contain double-width text characters, such as CJK
 * (Chinese, Japanese, Korean) and other special symbols. This class assumes those are all double-width and in case the
 * terminal (-emulator) chooses to draw them (somehow) as single-column then all the calculations in this class will be
 * wrong. It seems safe to assume what this class considers double-width really is taking up two columns though.
 *
 * @author Martin
 */
public class TerminalTextUtils {
    private TerminalTextUtils() {
    }

    /**
     * Given a string and an index in that string, returns the ANSI control sequence beginning on this index. If there
     * is no control sequence starting there, the method will return null. The returned value is the complete escape
     * sequence including the ESC prefix.
     * @param string String to scan for control sequences
     * @param index Index in the string where the control sequence begins
     * @return {@code null} if there was no control sequence starting at the specified index, otherwise the entire
     * control sequence
     */
    public static String getANSIControlSequenceAt(String string, int index) {
        int len = getANSIControlSequenceLength(string, index);
        return len == 0 ? null : string.substring(index,index+len);
    }

    /**
     * Given a string and an index in that string, returns the number of characters starting at index that make up
     * a complete ANSI control sequence. If there is no control sequence starting there, the method will return 0.
     * @param string String to scan for control sequences
     * @param index Index in the string where the control sequence begins
     * @return {@code 0} if there was no control sequence starting at the specified index, otherwise the length
     * of the entire control sequence
     */
    public static int getANSIControlSequenceLength(String string, int index) {
        int len = 0, restlen = string.length() - index;
        if (restlen >= 3) { // Control sequences require a minimum of three characters
            char esc = string.charAt(index),
                 bracket = string.charAt(index+1);
            if (esc == 0x1B && bracket == '[') { // escape & open bracket
                len = 3; // esc,bracket and (later)terminator.
                //  digits or semicolons can still precede the terminator:
                for (int i = 2; i < restlen; i++) {
                    char ch = string.charAt(i + index);
                    // only ascii-digits or semicolons allowed here:
                    if ( (ch >= '0' && ch <= '9') || ch == ';') {
                        len++;
                    } else {
                        break;
                    }
                }
                // if string ends in digits/semicolons, then it's not a sequence.
                if (len > restlen) {
                    len = 0;
                }
            }
        }
        return len;
    }

    /**
     * Given a character, is this character considered to be a CJK character?
     * Shamelessly stolen from
     * <a href="http://stackoverflow.com/questions/1499804/how-can-i-detect-japanese-text-in-a-java-string">StackOverflow</a>
     * where it was contributed by user Rakesh N
     * @param c Character to test
     * @return {@code true} if the character is a CJK character
     */
    public static boolean isCharCJK(final char c) {
        Character.UnicodeBlock unicodeBlock = Character.UnicodeBlock.of(c);
        return (unicodeBlock == Character.UnicodeBlock.HIRAGANA)
                || (unicodeBlock == Character.UnicodeBlock.KATAKANA)
                || (unicodeBlock == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS)
                || (unicodeBlock == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO)
                || (unicodeBlock == Character.UnicodeBlock.HANGUL_JAMO)
                || (unicodeBlock == Character.UnicodeBlock.HANGUL_SYLLABLES)
                || (unicodeBlock == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS)
                || (unicodeBlock == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A)
                || (unicodeBlock == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B)
                || (unicodeBlock == Character.UnicodeBlock.CJK_COMPATIBILITY_FORMS)
                || (unicodeBlock == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS)
                || (unicodeBlock == Character.UnicodeBlock.CJK_RADICALS_SUPPLEMENT)
                || (unicodeBlock == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION)
                || (unicodeBlock == Character.UnicodeBlock.ENCLOSED_CJK_LETTERS_AND_MONTHS)
                || (unicodeBlock == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS && c < 0xFF61);    //The magic number here is the separating index between full-width and half-width
    }

    /**
     * Given a character, is this character considered to be a Thai character?
     * @param c Character to test
     * @return {@code true} if the character is a Thai character
     */
    public static boolean isCharThai(char c) {
        Character.UnicodeBlock unicodeBlock = Character.UnicodeBlock.of(c);
        return unicodeBlock == Character.UnicodeBlock.THAI;
    }

    /**
     * Checks if a character is expected to be taking up two columns if printed to a terminal. This will generally be
     * {@code true} for CJK (Chinese, Japanese and Korean) characters. Notice that emoji generally takes up more than a
     * single char and can't be tested properly with this method.
     * @param c Character to test if it's double-width when printed to a terminal
     * @return {@code true} if this character is expected to be taking up two columns when printed to the terminal,
     * otherwise {@code false}
     */
    public static boolean isCharDoubleWidth(final char c) {
        // STANDARD WIDTH ONLY: CJK plus the Unicode East-Asian Wide/Fullwidth
        // table. Geometric / symbol glyphs that are EAW=Ambiguous or EAW=Neutral
        // — ● ○ ◯ ◎ ◐ ◑ (circle status dots), ▶ ◀ (media triangles), ⚠ (warning
        // sign) and kin — are ONE column on standard/modern terminals. They were
        // briefly force-widened here, but that OVER-counts the row on terminals
        // that render them narrow, pushing following cells and borders one column
        // out of alignment (e.g. a focused tab's "● " prefix shoving its trailing
        // │ left). Genuine emoji are still handled wide in
        // TextCharacter.isDoubleWidth (emoji-presentation table + astral/length>1).
        // Terminals configured emoji/ambiguous-wide can opt back in with
        // -Dlanterna.eastAsianAmbiguousWide=true.
        return isCharCJK(c) || isCharEastAsianWide(c);
    }

    /**
     * True for BMP code points whose Unicode East-Asian-Width is Wide (W) or
     * Fullwidth (F) — they occupy TWO terminal columns. Generated from Unicode
     * 15.1.0 {@code EastAsianWidth.txt} (W + F rows, restricted to the BMP; SMP
     * wide code points arrive as surrogate pairs and are handled by the
     * {@code length() > 1} grapheme branch in {@link TextCharacter#isDoubleWidth()}).
     *
     * This is the single source of truth for genuine double-width, so the
     * measured width and the painted width agree. W/F symbols that live OUTSIDE
     * the CJK Unicode blocks — angle brackets (U+2329..U+232A), Yi, Bopomofo,
     * CJK compatibility forms, vertical forms — used to fall through
     * {@link #isCharCJK(char)} and undercount by one column, drifting every
     * following cell (the "scrollbar teeth" / sticky-residue symptom).
     *
     * East-Asian *Ambiguous* (A) is deliberately NOT here: it is
     * terminal-dependent and resolved (default narrow) in {@code TextCharacter}.
     */
    public static boolean isCharEastAsianWide(final char c) {
        return c >= 0x1100 && c <= EAW_WIDE_MAX && EAW_WIDE.get(c);
    }

    private static final int EAW_WIDE_MAX = 0xFFE6;

    /** Inclusive (lo, hi) range pairs of BMP EAW=W / EAW=F code points (Unicode 15.1.0). */
    private static final int[] EAW_WIDE_RANGES = {
        0x1100, 0x115F, 0x231A, 0x231B, 0x2329, 0x232A, 0x23E9, 0x23EC,
        0x23F0, 0x23F0, 0x23F3, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615,
        0x2648, 0x2653, 0x267F, 0x267F, 0x2693, 0x2693, 0x26A1, 0x26A1,
        0x26AA, 0x26AB, 0x26BD, 0x26BE, 0x26C4, 0x26C5, 0x26CE, 0x26CE,
        0x26D4, 0x26D4, 0x26EA, 0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26F5,
        0x26FA, 0x26FA, 0x26FD, 0x26FD, 0x2705, 0x2705, 0x270A, 0x270B,
        0x2728, 0x2728, 0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755,
        0x2757, 0x2757, 0x2795, 0x2797, 0x27B0, 0x27B0, 0x27BF, 0x27BF,
        0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x2E80, 0x2E99,
        0x2E9B, 0x2EF3, 0x2F00, 0x2FD5, 0x2FF0, 0x303E, 0x3041, 0x3096,
        0x3099, 0x30FF, 0x3105, 0x312F, 0x3131, 0x318E, 0x3190, 0x31E3,
        0x31EF, 0x321E, 0x3220, 0x3247, 0x3250, 0x4DBF, 0x4E00, 0xA48C,
        0xA490, 0xA4C6, 0xA960, 0xA97C, 0xAC00, 0xD7A3, 0xF900, 0xFAFF,
        0xFE10, 0xFE19, 0xFE30, 0xFE52, 0xFE54, 0xFE66, 0xFE68, 0xFE6B,
        0xFF01, 0xFF60, 0xFFE0, 0xFFE6,
    };

    /** Built once at class load from {@link #EAW_WIDE_RANGES}. */
    private static final BitSet EAW_WIDE = buildWideBitSet();

    private static BitSet buildWideBitSet() {
        BitSet bits = new BitSet(EAW_WIDE_MAX + 1);
        for (int i = 0; i < EAW_WIDE_RANGES.length; i += 2) {
            bits.set(EAW_WIDE_RANGES[i], EAW_WIDE_RANGES[i + 1] + 1);
        }
        return bits;
    }

    /**
     * Checks if a particular character is a control character, in Lanterna this currently means it's 0-31 or 127 in the
     * ascii table.
     * @param c character to test
     * @return {@code true} if the character is a control character, {@code false} otherwise
     */
    public static boolean isControlCharacter(char c) {
        return c < 32 || c == 127;
    }

    /**
     * Checks if a particular character is printable. This generally means that the code is not a control character that
     * isn't able to be printed to the terminal properly. For example, NULL, ENQ, BELL and ESC and all control codes
     * that has no proper character associated with it so the behaviour is undefined and depends completely on the
     * terminal what happens if you try to print them. However, certain control characters have a particular meaning to
     * the terminal and are as such considered printable. In Lanterna, we consider these control characters printable:
     * <ul>
     *     <li>Backspace</li>
     *     <li>Horizontal Tab</li>
     *     <li>Line feed</li>
     * </ul>
     *
     * @param c character to test
     * @return {@code true} if the character is considered printable, {@code false} otherwise
     */
    public static boolean isPrintableCharacter(char c) {
        return !isControlCharacter(c) || c == '\t' || c == '\n' || c == '\b';
    }

    // ── Grapheme-cluster, per-terminal column measurement ───────────────────
    //
    // These mirror what AbstractTextGraphics.putString actually paints: text is
    // segmented into grapheme clusters via TextCharacter.fromString and each
    // cluster's width comes from TextCharacter.isDoubleWidth (which owns the
    // per-terminal VS-16 policy, auto-detected from TERM_PROGRAM). So what we
    // measure here always matches what the renderer emits. Pure printable-ASCII
    // strings (the common case) short-circuit to their char length, skipping the
    // grapheme/width segmentation entirely. All arithmetic is primitive int.

    /**
     * Inline-span sentinels: the BMP private-use range U+E110..U+E119 carries
     * zero-width inline style toggles (bold/italic/strike/code/link on and off).
     * They are never painted and occupy zero terminal columns.
     */
    private static final int INLINE_SENTINEL_LO = 0xE110;
    private static final int INLINE_SENTINEL_HI = 0xE119;

    private static boolean isInlineSentinel(String g) {
        if (g.length() != 1) {
            return false;
        }
        char c = g.charAt(0);
        return c >= INLINE_SENTINEL_LO && c <= INLINE_SENTINEL_HI;
    }

    private static boolean allNarrowAscii(String s, int n) {
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * Replace every ASCII control character (U+0000..U+001F) in {@code s} with
     * {@code '/'}, so a stray newline/tab/CR from a malformed string can never
     * reach the grapheme splitter and crash a render. Returns {@code s} unchanged
     * (same identity, no allocation) when it is already clean — the common case.
     * Inline-span sentinels live in the private-use area, not C0, so they pass
     * through untouched.
     * @param s String to sanitize
     * @return {@code s} with control characters replaced by {@code '/'}
     */
    public static String sanitizeControlChars(String s) {
        int n = s.length();
        int firstBad = -1;
        for (int i = 0; i < n; i++) {
            if (s.charAt(i) < 0x20) {
                firstBad = i;
                break;
            }
        }
        if (firstBad < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(n);
        sb.append(s, 0, firstBad);
        for (int k = firstBad; k < n; k++) {
            char c = s.charAt(k);
            sb.append(c < 0x20 ? '/' : c);
        }
        return sb.toString();
    }

    /**
     * Number of terminal columns {@code s} occupies when painted, honouring
     * grapheme clusters (via {@link TextCharacter#fromString}), CJK/emoji as two
     * columns and ASCII as one. Inline-span sentinels count as zero columns.
     * Control bytes are sanitized first. Returns 0 for {@code null} or empty.
     * @param s String to measure
     * @return number of terminal columns
     */
    public static int displayColumns(String s) {
        if (s == null) {
            return 0;
        }
        String safe = sanitizeControlChars(s);
        int len = safe.length();
        if (len == 0) {
            return 0;
        }
        if (allNarrowAscii(safe, len)) {
            return len;
        }
        TextCharacter[] cells = TextCharacter.fromString(safe);
        int width = 0;
        for (int i = 0; i < cells.length; i++) {
            TextCharacter tc = cells[i];
            if (isInlineSentinel(tc.getCharacterString())) {
                continue;
            }
            width += tc.isDoubleWidth() ? 2 : 1;
        }
        return width;
    }

    /**
     * Length (in chars) of the longest prefix of {@code s} whose
     * {@link #displayColumns} is at most {@code maxCols} and which does not split
     * a grapheme cluster. Returns 0 for {@code null}/empty or non-positive
     * {@code maxCols}.
     * @param s String to scan
     * @param maxCols Column budget
     * @return char length of the fitting prefix
     */
    public static int columnPrefixLength(String s, int maxCols) {
        if (s == null || maxCols <= 0) {
            return 0;
        }
        if (displayColumns(s) <= maxCols) {
            return s.length();
        }
        TextCharacter[] cells = TextCharacter.fromString(s);
        int charIdx = 0;
        int used = 0;
        for (int i = 0; i < cells.length; i++) {
            TextCharacter tc = cells[i];
            String g = tc.getCharacterString();
            int w = isInlineSentinel(g) ? 0 : (tc.isDoubleWidth() ? 2 : 1);
            if (used + w > maxCols) {
                return charIdx;
            }
            charIdx += g.length();
            used += w;
        }
        return charIdx;
    }

    /**
     * Longest prefix of {@code s} fitting in at most {@code maxCols} columns,
     * never splitting a grapheme. If a double-width grapheme would straddle the
     * cut it is dropped and one space appended, so the result's
     * {@link #displayColumns} is exactly {@code maxCols}. Zero-width inline
     * sentinels are always emitted (never stranded past the cut). Returns
     * {@code ""} for {@code null} or non-positive {@code maxCols}, and {@code s}
     * itself when it already fits.
     * @param s String to truncate
     * @param maxCols Column budget
     * @return the column-fitted prefix
     */
    public static String truncateColumns(String s, int maxCols) {
        if (s == null || maxCols <= 0) {
            return "";
        }
        if (displayColumns(s) <= maxCols) {
            return s;
        }
        TextCharacter[] cells = TextCharacter.fromString(s);
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < cells.length; i++) {
            TextCharacter tc = cells[i];
            String g = tc.getCharacterString();
            int w = isInlineSentinel(g) ? 0 : (tc.isDoubleWidth() ? 2 : 1);
            int next = used + w;
            if (next > maxCols) {
                if (used < maxCols) {
                    sb.append(' ');
                }
                return sb.toString();
            }
            sb.append(g);
            used = next;
        }
        return sb.toString();
    }

    /**
     * Truncate {@code s} to at most {@code maxCols} columns, appending
     * {@code ellipsis} when it does not fit (reserving the ellipsis's own column
     * width). Grapheme-cluster safe. Returns {@code ""} for non-positive
     * {@code maxCols}; when even the ellipsis will not fit, returns the ellipsis
     * truncated to {@code maxCols}.
     * @param s String to ellipsize ({@code null} treated as empty)
     * @param maxCols Column budget
     * @param ellipsis Marker appended on truncation (e.g. "…" or "...")
     * @return the ellipsized string
     */
    public static String ellipsize(String s, int maxCols, String ellipsis) {
        String txt = s == null ? "" : s;
        if (maxCols <= 0) {
            return "";
        }
        if (displayColumns(txt) <= maxCols) {
            return txt;
        }
        int ew = displayColumns(ellipsis);
        if (maxCols <= ew) {
            return truncateColumns(ellipsis, maxCols);
        }
        return truncateColumns(txt, maxCols - ew) + ellipsis;
    }

    /**
     * Given a string, returns how many columns this string would need to occupy in a terminal, taking into account that
     * CJK characters takes up two columns.
     * @param s String to check length
     * @return Number of actual terminal columns the string would occupy
     */
    public static int getColumnWidth(String s) {
        return getColumnIndex(s, s.length());
    }

    /**
     * Given a string and a character index inside that string, find out what the column index of that character would
     * be if printed in a terminal. If the string only contains non-CJK characters then the returned value will be same
     * as {@code stringCharacterIndex}, but if there are CJK characters the value will be different due to CJK
     * characters taking up two columns in width. If the character at the index in the string is a CJK character itself,
     * the returned value will be the index of the left-side of character. The tab character is counted as four spaces.
     * @param s String to translate the index from
     * @param stringCharacterIndex Index within the string to get the terminal column index of
     * @return Index of the character inside the String at {@code stringCharacterIndex} when it has been writted to a
     * terminal
     * @throws StringIndexOutOfBoundsException if the index given is outside the String length or negative
     */
    public static int getColumnIndex(String s, int stringCharacterIndex) throws StringIndexOutOfBoundsException {
        return getColumnIndex(s, stringCharacterIndex, TabBehaviour.CONVERT_TO_FOUR_SPACES, -1);
    }

    /**
     * Given a string and a character index inside that string, find out what the column index of that character would
     * be if printed in a terminal. If the string only contains non-CJK characters then the returned value will be same
     * as {@code stringCharacterIndex}, but if there are CJK characters the value will be different due to CJK
     * characters taking up two columns in width. If the character at the index in the string is a CJK character itself,
     * the returned value will be the index of the left-side of character.
     * @param s String to translate the index from
     * @param stringCharacterIndex Index within the string to get the terminal column index of
     * @param tabBehaviour The behavior to use when encountering the tab character
     * @param firstCharacterColumnPosition Where on the screen the first character in the string would be printed, this
     *                                     applies only when you have an alignment-based {@link TabBehaviour}
     * @return Index of the character inside the String at {@code stringCharacterIndex} when it has been writted to a
     * terminal
     * @throws StringIndexOutOfBoundsException if the index given is outside the String length or negative
     */
    public static int getColumnIndex(String s, int stringCharacterIndex, TabBehaviour tabBehaviour, int firstCharacterColumnPosition) throws StringIndexOutOfBoundsException {
        int index = 0;
        for(int i = 0; i < stringCharacterIndex; i++) {
            if(s.charAt(i) == '\t') {
                index += tabBehaviour.getTabReplacement(firstCharacterColumnPosition).length();
            }
            else {
                if (isCharDoubleWidth(s.charAt(i))) {
                    index++;
                }
                index++;
            }
        }
        return index;
    }

    /**
     * This method does the reverse of getColumnIndex, given a String and imagining it has been printed out to the
     * top-left corner of a terminal, in the column specified by {@code columnIndex}, what is the index of that
     * character in the string. If the string contains no CJK characters, this will always be the same as
     * {@code columnIndex}. If the index specified is the right column of a CJK character, the index is the same as if
     * the column was the left column. So calling {@code getStringCharacterIndex("英", 0)} and
     * {@code getStringCharacterIndex("英", 1)} will both return 0.
     * @param s String to translate the index to
     * @param columnIndex Column index of the string written to a terminal
     * @return The index in the string of the character in terminal column {@code columnIndex}
     */
    public static int getStringCharacterIndex(String s, int columnIndex) {
        int index = 0;
        int counter = 0;
        while(counter < columnIndex) {
            if(isCharDoubleWidth(s.charAt(index++))) {
                counter++;
                if(counter == columnIndex) {
                    return index - 1;
                }
            }
            counter++;
        }
        return index;
    }

    /**
     * Given a string that may or may not contain CJK characters, returns the substring which will fit inside
     * <code>availableColumnSpace</code> columns. This method does not handle special cases like tab or new-line.
     * <p>
     * Calling this method is the same as calling {@code fitString(string, 0, availableColumnSpace)}.
     * @param string The string to fit inside the availableColumnSpace
     * @param availableColumnSpace Number of columns to fit the string inside
     * @return The whole or part of the input string which will fit inside the supplied availableColumnSpace
     */
    public static String fitString(String string, int availableColumnSpace) {
        return fitString(string, 0, availableColumnSpace);
    }

    /**
     * Given a string that may or may not contain CJK characters, returns the substring which will fit inside
     * <code>availableColumnSpace</code> columns. This method does not handle special cases like tab or new-line.
     * <p>
     * This overload has a {@code fromColumn} parameter that specified where inside the string to start fitting. Please
     * notice that {@code fromColumn} is not a character index inside the string, but a column index as if the string
     * has been printed from the left-most side of the terminal. So if the string is "日本語", fromColumn set to 1 will
     * not starting counting from the second character ("本") in the string but from the CJK filler character belonging
     * to "日". If you want to count from a particular character index inside the string, please pass in a substring
     * and use fromColumn set to 0.
     * @param string The string to fit inside the availableColumnSpace
     * @param fromColumn From what column of the input string to start fitting (see description above!)
     * @param availableColumnSpace Number of columns to fit the string inside
     * @return The whole or part of the input string which will fit inside the supplied availableColumnSpace
     */
    public static String fitString(String string, int fromColumn, int availableColumnSpace) {
        if(availableColumnSpace <= 0) {
            return "";
        }

        StringBuilder bob = new StringBuilder();
        int column = 0;
        int index = 0;
        while(index < string.length() && column < fromColumn) {
            char c = string.charAt(index++);
            column += TerminalTextUtils.isCharDoubleWidth(c) ? 2 : 1;
        }
        if(column > fromColumn) {
            bob.append(" ");
            availableColumnSpace--;
        }

        while(availableColumnSpace > 0 && index < string.length()) {
            char c = string.charAt(index++);
            availableColumnSpace -= TerminalTextUtils.isCharDoubleWidth(c) ? 2 : 1;
            if(availableColumnSpace < 0) {
                bob.append(' ');
            }
            else {
                bob.append(c);
            }
        }
        return bob.toString();
    }

    /**
     * This method will calculate word wrappings given a number of lines of text and how wide the text can be printed.
     * The result is a list of new rows where word-wrapping was applied.
     * @param maxWidth Maximum number of columns that can be used before word-wrapping is applied, if &lt;= 0 then the
     *                 lines will be returned unchanged
     * @param lines Input text
     * @return The input text word-wrapped at {@code maxWidth}; this may contain more rows than the input text
     */
    public static List<String> getWordWrappedText(int maxWidth, String... lines) {
        //Bounds checking
        if(maxWidth <= 0) {
            return Arrays.asList(lines);
        }

        List<String> result = new ArrayList<>();
        LinkedList<String> linesToBeWrapped = new LinkedList<>(Arrays.asList(lines));
        while(!linesToBeWrapped.isEmpty()) {
            String row = linesToBeWrapped.removeFirst();
            int rowWidth = getColumnWidth(row);
            if(rowWidth <= maxWidth) {
                result.add(row);
            }
            else {
                //Now search in reverse and find the first possible line-break
                final int characterIndexMax = getStringCharacterIndex(row, maxWidth);
                int characterIndex = characterIndexMax;
                while(characterIndex >= 0 &&
                        !Character.isSpaceChar(row.charAt(characterIndex)) &&
                        !isCharCJK(row.charAt(characterIndex))) {
                    characterIndex--;
                }
                // right *after* a CJK is also a "nice" spot to break the line!
                if (characterIndex >= 0 && characterIndex < characterIndexMax &&
                      isCharCJK(row.charAt(characterIndex))) {
                    characterIndex++; // with these conditions it fits!
                }

                if(characterIndex < 0) {
                    //Failed! There was no 'nice' place to cut so just cut it at maxWidth
                    characterIndex = Math.max(characterIndexMax, 1); // at least 1 char
                    result.add(row.substring(0, characterIndex));
                    linesToBeWrapped.addFirst(row.substring(characterIndex));
                }
                else {
                    // characterIndex == 0 only happens, if either
                    //   - first char is CJK and maxWidth==1   or
                    //   - first char is whitespace
                    // either way: put it in row before break to prevent infinite loop.
                    characterIndex = Math.max( characterIndex, 1); // at least 1 char

                    //Ok, split the row, add it to the result and continue processing the second half on a new line
                    result.add(row.substring(0, characterIndex));
                    while(characterIndex < row.length() &&
                          Character.isSpaceChar(row.charAt(characterIndex))) {
                        characterIndex++;
                    }
                    if (characterIndex < row.length()) { // only if rest contains non-whitespace
                        linesToBeWrapped.addFirst(row.substring(characterIndex));
                    }
                }
            }
        }
        return result;
    }

    // ===================================================================
    // vis fork: grapheme/EAW-aware text flow — display-width word-wrap and
    // full justification.
    //
    // Upstream getColumnWidth/getWordWrappedText measure by Java `char`
    // (isCharDoubleWidth), so an emoji surrogate pair, a ZWJ sequence, a
    // regional-indicator flag or a VS-16 grapheme is mis-measured. These
    // methods measure the SAME way the screen paints — one TextCharacter per
    // grapheme cluster, double-width clusters = 2 columns — so wrap points and
    // justified widths line up exactly with what lands on the terminal.
    //
    // Plain text only: they have no notion of inline-style sentinels or ANSI
    // escapes (use the channel's styled-run wrapper for those).
    // ===================================================================

    /**
     * Display columns a string occupies, grapheme-cluster and East-Asian-Width
     * aware (one {@link TextCharacter} per cluster; double-width clusters count
     * as two). Matches the cell model the screen renders with, unlike the
     * {@code char}-based {@link #getColumnWidth(String)}.
     * @param s string to measure (nullable)
     * @return number of terminal columns
     */
    public static int displayWidth(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        // Fast path: a string with NO character at or above U+0300 has no
        // combining marks, variation selectors, ZWJ, surrogate pairs (emoji /
        // SMP) and no wide glyphs — every char is its own width-1 cell, so the
        // width is simply the length. This skips BreakIterator grapheme
        // segmentation (and its per-cluster allocation) for the dominant
        // ASCII/Latin case, which is ~100x cheaper. Anything from U+0300 up
        // (CJK, emoji, accents-as-combining, …) takes the exact grapheme path.
        int n = s.length();
        boolean simple = true;
        for (int i = 0; i < n; i++) {
            if (s.charAt(i) >= 0x0300) {
                simple = false;
                break;
            }
        }
        if (simple) {
            return n;
        }
        int width = 0;
        for (TextCharacter tc : TextCharacter.fromString(s)) {
            width += tc.isDoubleWidth() ? 2 : 1;
        }
        return width;
    }

    /**
     * Greedy word-wrap {@code text} so every returned line fits within
     * {@code maxColumns} display columns. Breaks on whitespace; a single token
     * wider than {@code maxColumns} is hard-split at grapheme boundaries (never
     * mid-cluster). Embedded {@code '\n'} are honoured as hard breaks. Always
     * returns at least one (possibly empty) line.
     * @param maxColumns wrap width in columns; &lt;= 0 yields a single empty line
     * @param text input (nullable)
     * @return wrapped lines, each {@link #displayWidth} &lt;= {@code maxColumns}
     */
    public static List<String> wordWrap(int maxColumns, String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            text = "";
        }
        if (maxColumns <= 0) {
            out.add("");
            return out;
        }
        for (String paragraph : text.split("\n", -1)) {
            wrapParagraph(maxColumns, paragraph, out);
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
    }

    private static void wrapParagraph(int maxColumns, String paragraph, List<String> out) {
        String trimmed = paragraph.trim();
        if (trimmed.isEmpty()) {
            out.add("");
            return;
        }
        StringBuilder line = new StringBuilder();
        int lineWidth = 0;
        for (String word : trimmed.split("\\s+")) {
            int wordWidth = displayWidth(word);
            if (wordWidth > maxColumns) {
                if (lineWidth > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                    lineWidth = 0;
                }
                List<String> pieces = hardSplitColumns(word, maxColumns);
                for (int i = 0; i < pieces.size() - 1; i++) {
                    out.add(pieces.get(i));
                }
                String last = pieces.get(pieces.size() - 1);
                line.append(last);
                lineWidth = displayWidth(last);
            }
            else {
                int separator = lineWidth == 0 ? 0 : 1;
                if (lineWidth + separator + wordWidth <= maxColumns) {
                    if (separator == 1) {
                        line.append(' ');
                    }
                    line.append(word);
                    lineWidth += separator + wordWidth;
                }
                else {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                    lineWidth = wordWidth;
                }
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
    }

    private static List<String> hardSplitColumns(String word, int maxColumns) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentWidth = 0;
        for (TextCharacter tc : TextCharacter.fromString(word)) {
            int w = tc.isDoubleWidth() ? 2 : 1;
            if (currentWidth > 0 && currentWidth + w > maxColumns) {
                pieces.add(current.toString());
                current.setLength(0);
                currentWidth = 0;
            }
            current.append(tc.getCharacterString());
            currentWidth += w;
        }
        pieces.add(current.toString());
        return pieces;
    }

    /**
     * Character-fold (terminal-style SOFT WRAP) {@code text} so every returned
     * line fits within {@code maxColumns} display columns. Unlike
     * {@link #wordWrap(int, String)} this never reflows or drops whitespace:
     * the bytes are preserved exactly and a break is inserted only at the
     * column boundary — always at a grapheme-cluster edge, never mid-cluster.
     * So source/data indentation and in-row column alignment survive while a
     * pathologically wide single line (a one-line commit-message arg, a wide
     * value map) folds at the edge instead of overflowing or being clipped.
     *
     * Embedded {@code '\n'} are honoured as hard breaks; a line already within
     * budget is returned unchanged (so normal multi-line source is untouched
     * and only the over-wide rows fold). Always returns at least one
     * (possibly empty) line.
     *
     * Plain text only — no notion of inline-style sentinels or ANSI escapes
     * (use the channel's styled-run wrapper for those).
     * @param maxColumns fold width in columns; &lt;= 0 returns the input split
     *                   into lines, unfolded
     * @param text input (nullable)
     * @return folded lines, each {@link #displayWidth} &lt;= {@code maxColumns}
     */
    public static List<String> foldColumns(int maxColumns, String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            text = "";
        }
        for (String line : text.split("\n", -1)) {
            if (maxColumns <= 0 || displayWidth(line) <= maxColumns) {
                out.add(line);
            }
            else {
                out.addAll(hardSplitColumns(line, maxColumns));
            }
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
    }

    /**
     * ANSI-SGR-aware character-fold (terminal-style SOFT WRAP) of {@code s} into
     * segments each at most {@code maxColumns} display columns wide, never
     * splitting a grapheme cluster and never counting a {@code \u001b[..m} escape
     * toward the width. The SGR sequence active at a cut is RE-OPENED at the head
     * of the next segment (and the cut segment is closed with {@code \u001b[0m}),
     * so a syntax-highlighted line that folds keeps each token's color across the
     * break instead of being clipped at the edge.
     *
     * <p>ESC-free input delegates to {@link #foldColumns(int, String)}; a segment
     * always makes progress so a pathological width cannot loop. {@code null}/empty
     * returns a single {@code ""}.
     * @param maxColumns fold width in columns (clamped to at least 1)
     * @param s input (nullable), may carry {@code \u001b[..m} SGR escapes
     * @return folded segments, escapes balanced per segment
     */
    public static List<String> ansiFoldColumns(int maxColumns, String s) {
        String str = s == null ? "" : s;
        int budget = Math.max(1, maxColumns);
        if (str.indexOf(27) < 0) { // no ESC: plain fold is enough
            return foldColumns(budget, str);
        }
        List<String> out = new ArrayList<>();
        String rest = str;
        String active = ""; // SGR prefix to re-open on the next segment
        int col = 0;
        StringBuilder seg = new StringBuilder();
        while (true) {
            if (rest.isEmpty()) {
                out.add(seg.toString());
                return out;
            }
            if (rest.startsWith("\u001b[")) {
                int m = rest.indexOf('m');
                if (m < 0) { // malformed trailing escape: keep it verbatim and stop
                    out.add(seg.append(rest).toString());
                    return out;
                }
                String esc = rest.substring(0, m + 1);
                String body = rest.substring(2, m);
                active = (body.isEmpty() || body.equals("0") || body.equals("00")) ? "" : active + esc;
                seg.append(esc);
                rest = rest.substring(m + 1);
                continue;
            }
            int escIdx = rest.indexOf("\u001b[");
            String run = escIdx < 0 ? rest : rest.substring(0, escIdx);
            String after = escIdx < 0 ? "" : rest.substring(escIdx);
            int avail = budget - col;
            int k = columnPrefixLength(run, avail);
            if (k >= run.length()) { // whole run fits on the current row
                col += displayColumns(run);
                seg.append(run);
                rest = after;
                continue;
            }
            if (k == 0 && col > 0) { // nothing more fits on a partial row: close it, restart fresh
                out.add(seg.toString() + "\u001b[0m");
                seg = new StringBuilder(active);
                col = 0;
                rest = run + after;
                continue;
            }
            // overflow at a fresh row: emit what fits (force >=1 grapheme so a
            // double-width glyph under a tiny budget still progresses), close the
            // row, and continue on a new row carrying `active`.
            if (k == 0) {
                k = TextCharacter.fromString(run)[0].getCharacterString().length();
            }
            String head = run.substring(0, k);
            String tail = run.substring(k);
            out.add(seg.append(head).toString() + "\u001b[0m");
            seg = new StringBuilder(active);
            col = 0;
            rest = tail + after;
        }
    }

    /**
     * Return the display-column WINDOW {@code [start, start+width)} of {@code s} as
     * a string — the horizontal {@code less -S} clip a code pager paints each row
     * with (CHOP, not fold). ANSI-SGR aware: {@code \u001b[..m} escapes never count
     * toward a column, the SGR active at the window's LEFT edge is RE-OPENED at the
     * head of the result, escapes that fall INSIDE the window are kept inline, and
     * the result is closed with {@code \u001b[0m} whenever any SGR was emitted — so
     * a syntax-highlighted row keeps its colors when scrolled sideways.
     *
     * <p>ESC-free input is a plain grapheme-safe column slice (never splits a
     * cluster). Negative {@code start} clamps to 0; non-positive {@code width}
     * yields {@code ""}.
     * @param s input (nullable), may carry {@code \u001b[..m} SGR escapes
     * @param start left column of the window (clamped to 0)
     * @param width window width in columns (&lt;= 0 yields "")
     * @return the clipped window, escapes balanced
     */
    public static String ansiSliceColumns(String s, int start, int width) {
        String str = s == null ? "" : s;
        if (width <= 0) {
            return "";
        }
        int lo0 = Math.max(0, start);
        int end = lo0 + Math.max(0, width);
        if (str.indexOf(27) < 0) { // plain text: two grapheme-safe prefix cuts bound the window exactly
            return str.substring(columnPrefixLength(str, lo0), columnPrefixLength(str, end));
        }
        String rest = str;
        String active = ""; // SGR prefix active at the cursor
        int col = 0; // display column of the next glyph
        StringBuilder out = new StringBuilder();
        boolean opened = false; // emitted the active-SGR head yet?
        boolean sgr = false; // emitted ANY escape (=> needs a reset)?
        while (true) {
            if (rest.isEmpty() || col >= end) {
                if (sgr) {
                    out.append("\u001b[0m");
                }
                return out.toString();
            }
            if (rest.startsWith("\u001b[")) {
                int m = rest.indexOf('m');
                if (m < 0) {
                    if (sgr) {
                        out.append("\u001b[0m");
                    }
                    return out.toString();
                }
                String esc = rest.substring(0, m + 1);
                String body = rest.substring(2, m);
                active = (body.isEmpty() || body.equals("0") || body.equals("00")) ? "" : active + esc;
                // Escapes past the window's left edge paint inline; earlier ones
                // only update `active` (re-opened when emitting starts).
                if (opened) {
                    out.append(esc);
                    sgr = true;
                }
                rest = rest.substring(m + 1);
                continue;
            }
            int escIdx = rest.indexOf("\u001b[");
            String run = escIdx < 0 ? rest : rest.substring(0, escIdx);
            String after = escIdx < 0 ? "" : rest.substring(escIdx);
            int w = displayColumns(run);
            int runEnd = col + w;
            int lo = Math.max(lo0, col) - col; // cols to skip from run head
            int hi = Math.min(end, runEnd) - col; // cols to keep to
            if (lo < hi) {
                String piece = run.substring(columnPrefixLength(run, lo), columnPrefixLength(run, hi));
                boolean open = !opened && !active.isEmpty();
                if (open) {
                    out.append(active);
                }
                out.append(piece);
                opened = true;
                sgr = sgr || open;
            }
            col = runEnd;
            rest = after;
        }
    }

    /**
     * Full-justify the words on a single line to EXACTLY {@code width} display
     * columns by distributing spaces as evenly as possible between them (flush
     * to both margins). A blank line is returned as {@code width} spaces; a
     * single-word line (nothing to stretch) is left-aligned and right-padded.
     * Intended for lines already produced by {@link #wordWrap}; an over-long
     * line is joined with single spaces and right-padded instead of overflowing.
     * @param line one line of words (nullable)
     * @param width target width in columns
     * @return a string of {@link #displayWidth} == {@code width} (when stretchable)
     */
    public static String justifyLine(String line, int width) {
        if (width <= 0) {
            return "";
        }
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) {
            return repeatChar(' ', width);
        }
        String[] words = trimmed.split("\\s+");
        if (words.length == 1) {
            return padRightColumns(words[0], width);
        }
        int textWidth = 0;
        for (String w : words) {
            textWidth += displayWidth(w);
        }
        int gaps = words.length - 1;
        int totalSpaces = width - textWidth;
        if (totalSpaces < gaps) {
            // Doesn't even fit with single spaces — caller passed an over-long
            // line. Fall back to a left-aligned single-spaced join.
            return padRightColumns(String.join(" ", words), width);
        }
        int base = totalSpaces / gaps;
        int extra = totalSpaces - (base * gaps); // first `extra` gaps get one more
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            sb.append(words[i]);
            if (i < gaps) {
                sb.append(repeatChar(' ', base + (i < extra ? 1 : 0)));
            }
        }
        return sb.toString();
    }

    /**
     * Word-wrap {@code text} to {@code width} columns, then full-justify every
     * line. The final line of each paragraph stays left-aligned unless
     * {@code justifyLastLine} is true — the standard typographic convention.
     * @param width wrap + justify width in columns
     * @param text input (nullable)
     * @param justifyLastLine also stretch each paragraph's final line
     * @return justified lines
     */
    public static List<String> justify(int width, String text, boolean justifyLastLine) {
        List<String> wrapped = wordWrap(width, text);
        if (justifyLastLine) {
            List<String> out = new ArrayList<>(wrapped.size());
            for (String line : wrapped) {
                out.add(justifyLine(line, width));
            }
            return out;
        }
        // Leave the last line of each paragraph (run up to a blank line or the
        // end) left-aligned.
        List<String> out = new ArrayList<>(wrapped.size());
        for (int i = 0; i < wrapped.size(); i++) {
            String line = wrapped.get(i);
            boolean lastOfParagraph = (i == wrapped.size() - 1) || wrapped.get(i + 1).isEmpty();
            out.add(line.isEmpty() || lastOfParagraph ? line : justifyLine(line, width));
        }
        return out;
    }

    /**
     * Pad {@code s} on the RIGHT with spaces so it occupies exactly
     * {@code width} display columns. A string already exactly {@code width}
     * wide is returned as-is; one WIDER than {@code width} is column-truncated
     * (grapheme-safe) to fit.
     * @param s string to pad (nullable, treated as empty)
     * @param width target width in columns
     * @return a string of {@link #displayWidth} == {@code width}
     */
    public static String padRight(String s, int width) {
        String txt = s == null ? "" : s;
        int cols = displayWidth(txt);
        if (cols == width) {
            return txt;
        }
        if (cols > width) {
            return truncateColumns(txt, width);
        }
        return txt + repeatChar(' ', width - cols);
    }

    /**
     * Pad {@code s} on the LEFT with spaces so it occupies exactly {@code width}
     * display columns (right-aligned). Over-wide input is column-truncated.
     * @param s string to pad (nullable, treated as empty)
     * @param width target width in columns
     * @return a string of {@link #displayWidth} == {@code width}
     */
    public static String padLeft(String s, int width) {
        String txt = s == null ? "" : s;
        int cols = displayWidth(txt);
        if (cols == width) {
            return txt;
        }
        if (cols > width) {
            return truncateColumns(txt, width);
        }
        return repeatChar(' ', width - cols) + txt;
    }

    /**
     * Center {@code s} within {@code width} display columns, padding both sides
     * with spaces (extra odd column goes to the RIGHT). Over-wide input is
     * column-truncated.
     * @param s string to center (nullable, treated as empty)
     * @param width target width in columns
     * @return a string of {@link #displayWidth} == {@code width}
     */
    public static String center(String s, int width) {
        String txt = s == null ? "" : s;
        int cols = displayWidth(txt);
        if (cols >= width) {
            return truncateColumns(txt, width);
        }
        int leftPad = (width - cols) / 2;
        int rightPad = width - cols - leftPad;
        return repeatChar(' ', leftPad) + txt + repeatChar(' ', rightPad);
    }

    /**
     * Shorten {@code s} to at most {@code maxCols} display columns by ELIDING
     * THE MIDDLE behind a single {@code '…'}, keeping both the HEAD and the
     * TAIL. Ideal for file paths, where the basename (tail) is as informative
     * as the leading dirs. Falls back to plain head truncation when there
     * isn't room for both sides plus the ellipsis. Grapheme-cluster safe.
     * @param s string to shorten (nullable → {@code ""})
     * @param maxCols column budget; &lt;= 0 → {@code ""}
     * @return the middle-elided string, {@link #displayWidth} &lt;= {@code maxCols}
     */
    public static String truncateMiddle(String s, int maxCols) {
        if (s == null || maxCols <= 0) {
            return "";
        }
        if (displayWidth(s) <= maxCols) {
            return s;
        }
        if (maxCols <= 2) {
            return truncateColumns(s, maxCols);
        }
        int budget = maxCols - 1; // one column spent on the ellipsis
        int tailCols = budget / 2;
        int headCols = budget - tailCols;
        String head = truncateColumns(s, headCols);
        TextCharacter[] cells = TextCharacter.fromString(s);
        StringBuilder tail = new StringBuilder();
        int used = 0;
        for (int i = cells.length - 1; i >= 0; i--) {
            TextCharacter tc = cells[i];
            int w = tc.isDoubleWidth() ? 2 : 1;
            if (used + w > tailCols) {
                break;
            }
            tail.insert(0, tc.getCharacterString());
            used += w;
        }
        return head + "…" + tail;
    }

    /**
     * Distribute {@code items} across {@code width} display columns with equal
     * gaps: first item flush-left, last flush-right, the rest evenly spaced
     * (CSS {@code justify-content: space-between}). One item is centered; none
     * yields {@code width} spaces. Gaps are never smaller than one column.
     * @param items segments to distribute (each nullable → empty)
     * @param width total width in columns
     * @return the laid-out row
     */
    public static String spaceBetween(List<String> items, int width) {
        int n = items.size();
        if (n == 0) {
            return repeatChar(' ', width);
        }
        if (n == 1) {
            return center(items.get(0), width);
        }
        int totalText = 0;
        for (String it : items) {
            totalText += displayWidth(it);
        }
        int totalGaps = width - totalText;
        int gapCount = n - 1;
        int baseGap = Math.max(1, totalGaps / gapCount);
        int extra = totalGaps - (baseGap * gapCount); // first `extra` gaps get one more
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(items.get(i) == null ? "" : items.get(i));
            if (i < gapCount) {
                sb.append(repeatChar(' ', baseGap + (i < extra ? 1 : 0)));
            }
        }
        return sb.toString();
    }

    /**
     * Distribute {@code items} across {@code width} display columns with equal
     * space AROUND each item (CSS {@code justify-content: space-around}). One
     * item is centered; none yields {@code width} spaces. The result is padded
     * or column-truncated to land on exactly {@code width}.
     * @param items segments to distribute (each nullable → empty)
     * @param width total width in columns
     * @return the laid-out row, {@link #displayWidth} == {@code width}
     */
    public static String spaceAround(List<String> items, int width) {
        int n = items.size();
        if (n == 0) {
            return repeatChar(' ', width);
        }
        if (n == 1) {
            return center(items.get(0), width);
        }
        int totalText = 0;
        for (String it : items) {
            totalText += displayWidth(it);
        }
        int totalGaps = width - totalText;
        int slots = 2 * n; // each item gets space on both sides
        int base = Math.max(0, totalGaps / slots);
        String unitGap = repeatChar(' ', base);
        StringBuilder sb = new StringBuilder();
        for (String it : items) {
            sb.append(unitGap).append(it == null ? "" : it).append(unitGap);
        }
        String result = sb.toString();
        int resultW = displayWidth(result);
        if (resultW == width) {
            return result;
        }
        if (resultW < width) {
            return result + repeatChar(' ', width - resultW);
        }
        return truncateColumns(result, width);
    }

    /**
     * Vertical offset that centers {@code contentH} rows within a
     * {@code containerH}-row region — {@code 0} when the content is at least as
     * tall as the container.
     * @param contentH content height in rows
     * @param containerH container height in rows
     * @return the top offset in rows
     */
    public static int verticalCenterOffset(int contentH, int containerH) {
        return contentH < containerH ? (containerH - contentH) / 2 : 0;
    }

    /**
     * Clamps {@code value} into the inclusive range {@code [low, high]}. The one
     * canonical range clamp for layout / scroll coordinate math.
     * @param value value to clamp
     * @param low inclusive lower bound
     * @param high inclusive upper bound
     * @return {@code low} if {@code value < low}, {@code high} if {@code value > high}, else {@code value}
     */
    public static int clamp(int value, int low, int high) {
        return value < low ? low : Math.min(value, high);
    }

    /**
     * Clamps {@code value} into the inclusive range {@code [low, high]} (long overload).
     * @param value value to clamp
     * @param low inclusive lower bound
     * @param high inclusive upper bound
     * @return {@code low} if {@code value < low}, {@code high} if {@code value > high}, else {@code value}
     */
    public static long clamp(long value, long low, long high) {
        return value < low ? low : Math.min(value, high);
    }

    /**
     * Returns a string containing {@code count} copies of {@code character}.
     * Negative counts are treated as zero.
     *
     * <p>This is the shared primitive behind terminal chrome lines (borders,
     * separators, table rules) so downstream code doesn't rebuild the same
     * horizontal runs via higher-level language sequence helpers.</p>
     *
     * @param character character to repeat
     * @param count number of copies to append
     * @return repeated-character string, or the empty string when {@code count <= 0}
     */
    public static String repeat(char character, int count) {
        return repeatChar(character, count);
    }

    /**
     * Returns repeated-character segments joined by {@code separator}. Each
     * entry in {@code segmentWidths} is clamped to zero before rendering.
     *
     * @param character repeated character for each segment
     * @param segmentWidths widths of each repeated segment
     * @param separator separator character between segments
     * @return joined repeated segments
     */
    public static String joinedLine(char character, int[] segmentWidths, char separator) {
        if (segmentWidths == null || segmentWidths.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < segmentWidths.length; i++) {
            if (i > 0) {
                builder.append(separator);
            }
            appendRepeated(builder, character, segmentWidths[i]);
        }
        return builder.toString();
    }

    /**
     * Returns a bordered repeated-character line such as {@code ┌──┬──┐}.
     * The left/right and separator glyphs are supplied by the caller so the
     * same primitive can render top, middle, and bottom rules.
     *
     * @param segmentWidths widths of each repeated segment
     * @param left left edge glyph
     * @param character repeated character for each segment
     * @param separator separator glyph between segments
     * @param right right edge glyph
     * @return bordered line
     */
    public static String boxedLine(int[] segmentWidths, char left, char character, char separator, char right) {
        StringBuilder builder = new StringBuilder();
        builder.append(left);
        if (segmentWidths != null) {
            for (int i = 0; i < segmentWidths.length; i++) {
                if (i > 0) {
                    builder.append(separator);
                }
                appendRepeated(builder, character, segmentWidths[i]);
            }
        }
        builder.append(right);
        return builder.toString();
    }

    private static void appendRepeated(StringBuilder builder, char character, int count) {
        if (count <= 0) {
            return;
        }
        for (int i = 0; i < count; i++) {
            builder.append(character);
        }
    }

    private static String repeatChar(char c, int count) {
        if (count <= 0) {
            return "";
        }
        char[] buf = new char[count];
        Arrays.fill(buf, c);
        return new String(buf);
    }

    private static String padRightColumns(String s, int width) {
        int w = displayWidth(s);
        return w >= width ? s : s + repeatChar(' ', width - w);
    }

    private static Integer[] mapCodesToIntegerArray(String[] codes) {
        Integer[] result = new Integer[codes.length];
        for (int i = 0; i < result.length; i++) {
            if (codes[i].isEmpty()) {
                result[i] = 0;
            } else {
                try {
                    // An empty string is equivalent to 0.
                    // Warning: too large values could throw an Exception!
                    result[i] = Integer.parseInt(codes[i]);
                } catch (NumberFormatException ignored) {
                    throw new IllegalArgumentException("Unknown CSI code " + codes[i]);
                }
            }
        }
        return result;
    }

    public static void updateModifiersFromCSICode(
            String controlSequence,
            StyleSet<?> target,
            StyleSet<?> original) {

        char controlCodeType = controlSequence.charAt(controlSequence.length() - 1);
        controlSequence = controlSequence.substring(2, controlSequence.length() - 1);
        Integer[] codes = mapCodesToIntegerArray(controlSequence.split(";"));

        TextColor[] palette = TextColor.ANSI.values();

        if(controlCodeType == 'm') { // SGRs
            for (int i = 0; i < codes.length; i++) {
                int code = codes[i];
                switch (code) {
                case 0:
                    target.setStyleFrom(original);
                    break;
                case 1:
                    target.enableModifiers(SGR.BOLD);
                    break;
                case 3:
                    target.enableModifiers(SGR.ITALIC);
                    break;
                case 4:
                    target.enableModifiers(SGR.UNDERLINE);
                    break;
                case 5:
                    target.enableModifiers(SGR.BLINK);
                    break;
                case 7:
                    target.enableModifiers(SGR.REVERSE);
                    break;
                case 21: // both do. 21 seems more straightforward.
                case 22:
                    target.disableModifiers(SGR.BOLD);
                    break;
                case 23:
                    target.disableModifiers(SGR.ITALIC);
                    break;
                case 24:
                    target.disableModifiers(SGR.UNDERLINE);
                    break;
                case 25:
                    target.disableModifiers(SGR.BLINK);
                    break;
                case 27:
                    target.disableModifiers(SGR.REVERSE);
                    break;
                case 38:
                    if (i + 2 < codes.length && codes[i + 1] == 5) {
                        target.setForegroundColor(new TextColor.Indexed(codes[i + 2]));
                        i += 2;
                    } else if (i + 4 < codes.length && codes[i + 1] == 2) {
                        target.setForegroundColor(new TextColor.RGB(codes[i + 2], codes[i + 3], codes[i + 4]));
                        i += 4;
                    }
                    break;
                case 39:
                    target.setForegroundColor(original.getForegroundColor());
                    break;
                case 48:
                    if (i + 2 < codes.length && codes[i + 1] == 5) {
                        target.setBackgroundColor(new TextColor.Indexed(codes[i + 2]));
                        i += 2;
                    } else if (i + 4 < codes.length && codes[i + 1] == 2) {
                        target.setBackgroundColor(new TextColor.RGB(codes[i + 2], codes[i + 3], codes[i + 4]));
                        i += 4;
                    }
                    break;
                case 49:
                    target.setBackgroundColor(original.getBackgroundColor());
                    break;
                default:
                    if (code >= 30 && code <= 37) {
                        target.setForegroundColor( palette[code - 30] );
                    }
                    else if (code >= 40 && code <= 47) {
                        target.setBackgroundColor( palette[code - 40] );
                    }
                }
            }
        }
    }
}
