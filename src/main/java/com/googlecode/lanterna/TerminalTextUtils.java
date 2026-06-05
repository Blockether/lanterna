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
        return isCharCJK(c) || isCharEastAsianWide(c)
                // ▶ U+25B6 / ◀ U+25C0 — Emoji=Yes media triangles the target
                // terminals paint emoji-wide; mirrors TextCharacter's
                // isWideEmojiSymbol so getColumnWidth agrees with the paint.
                || c == 0x25B6 || c == 0x25C0;
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
