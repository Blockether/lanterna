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
