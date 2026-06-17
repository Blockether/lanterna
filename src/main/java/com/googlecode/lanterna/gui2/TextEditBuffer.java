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
package com.googlecode.lanterna.gui2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.googlecode.lanterna.TerminalPosition;

/**
 * An immutable multi-line text buffer with a caret, exposing the cursor-motion
 * and editing primitives that a line/prompt editor needs: character and word
 * motion, line/word deletion, kill-line, transpose, and so on. The operations
 * follow the well-known Emacs/readline semantics.
 * <p>
 * This class is intentionally decoupled from {@link TextBox} and the rest of
 * gui2: it carries no rendering, no theming, and no {@code KeyStroke}
 * dispatch. It is a pure value type — every mutating method returns a NEW
 * {@code TextEditBuffer} and leaves the receiver untouched — so a host (a
 * gui2 component, a custom screen renderer, or a non-Java binding) can keep its
 * own key map and simply delegate the buffer arithmetic here.
 * <p>
 * The buffer always holds at least one line; an "empty" buffer is a single
 * empty string with the caret at {@code (0, 0)}. The caret column may equal the
 * current line length (caret after the last character).
 */
public final class TextEditBuffer {

    private final List<String> lines;
    private final int row;
    private final int column;

    private TextEditBuffer(List<String> source, int row, int column) {
        List<String> safe = (source == null || source.isEmpty())
                ? new ArrayList<String>(Collections.singletonList(""))
                : new ArrayList<String>(source);
        int r = clamp(row, 0, safe.size() - 1);
        int c = clamp(column, 0, safe.get(r).length());
        this.lines = Collections.unmodifiableList(safe);
        this.row = r;
        this.column = c;
    }

    // ── Construction ────────────────────────────────────────────────────────

    /** A pristine, empty buffer: one empty line, caret at the origin. */
    public static TextEditBuffer create() {
        return new TextEditBuffer(Collections.singletonList(""), 0, 0);
    }

    /** Build from a string, splitting on {@code '\n'}; caret lands at the end. */
    public static TextEditBuffer of(String text) {
        if (text == null) {
            text = "";
        }
        String[] parts = text.split("\n", -1);
        int last = parts.length - 1;
        return new TextEditBuffer(new ArrayList<String>(Arrays.asList(parts)), last, parts[last].length());
    }

    /** Build from explicit lines and an explicit caret (both clamped to range). */
    public static TextEditBuffer of(List<String> lines, int row, int column) {
        return new TextEditBuffer(lines, row, column);
    }

    // ── Accessors ───────────────────────────────────────────────────────────

    /** The lines, as an unmodifiable list (never empty). */
    public List<String> getLines() {
        return lines;
    }

    public int getRow() {
        return row;
    }

    public int getColumn() {
        return column;
    }

    /** The caret as a {@link TerminalPosition} (column, row). */
    public TerminalPosition getCaret() {
        return new TerminalPosition(column, row);
    }

    /** The full text, lines joined with {@code '\n'}. */
    public String getText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /** True for the pristine single-empty-line buffer with the caret at origin. */
    public boolean isEmpty() {
        return lines.size() == 1 && lines.get(0).isEmpty() && row == 0 && column == 0;
    }

    // ── Insertion ───────────────────────────────────────────────────────────

    public TextEditBuffer insertCharacter(char ch) {
        if (ch == '\n') {
            return newline();
        }
        String line = lines.get(row);
        String edited = line.substring(0, column) + ch + line.substring(column);
        return with(replaced(row, edited), row, column + 1);
    }

    /** Insert a string, honouring any embedded {@code '\n'} as line breaks. */
    public TextEditBuffer insertText(String text) {
        if (text == null || text.isEmpty()) {
            return this;
        }
        TextEditBuffer buffer = this;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            buffer = (ch == '\n') ? buffer.newline() : buffer.insertCharacter(ch);
        }
        return buffer;
    }

    /** Split the current line at the caret, moving the tail onto a new line. */
    public TextEditBuffer newline() {
        String line = lines.get(row);
        String before = line.substring(0, column);
        String after = line.substring(column);
        List<String> next = new ArrayList<String>(lines);
        next.set(row, before);
        next.add(row + 1, after);
        return with(next, row + 1, 0);
    }

    // ── Caret motion ────────────────────────────────────────────────────────

    public TextEditBuffer moveLeft() {
        if (column > 0) {
            return with(lines, row, column - 1);
        }
        if (row > 0) {
            return with(lines, row - 1, lines.get(row - 1).length());
        }
        return this;
    }

    public TextEditBuffer moveRight() {
        if (column < lines.get(row).length()) {
            return with(lines, row, column + 1);
        }
        if (row < lines.size() - 1) {
            return with(lines, row + 1, 0);
        }
        return this;
    }

    public TextEditBuffer moveUp() {
        if (row > 0) {
            return with(lines, row - 1, Math.min(column, lines.get(row - 1).length()));
        }
        return this;
    }

    public TextEditBuffer moveDown() {
        if (row < lines.size() - 1) {
            return with(lines, row + 1, Math.min(column, lines.get(row + 1).length()));
        }
        return this;
    }

    public TextEditBuffer moveLineStart() {
        return with(lines, row, 0);
    }

    public TextEditBuffer moveLineEnd() {
        return with(lines, row, lines.get(row).length());
    }

    public TextEditBuffer moveWordLeft() {
        if (column > 0) {
            return with(lines, row, wordBoundaryLeft(lines.get(row), column));
        }
        if (row > 0) {
            // Step to the end of the previous line, then keep moving by word.
            return with(lines, row - 1, lines.get(row - 1).length()).moveWordLeft();
        }
        return this;
    }

    public TextEditBuffer moveWordRight() {
        String line = lines.get(row);
        if (column < line.length()) {
            return with(lines, row, wordBoundaryRight(line, column));
        }
        if (row < lines.size() - 1) {
            return with(lines, row + 1, 0).moveWordRight();
        }
        return this;
    }

    // ── Deletion / kill ─────────────────────────────────────────────────────

    /** Backspace: delete the character before the caret, joining lines if at BOL. */
    public TextEditBuffer deleteBackward() {
        if (column > 0) {
            String line = lines.get(row);
            String edited = line.substring(0, column - 1) + line.substring(column);
            return with(replaced(row, edited), row, column - 1);
        }
        if (row > 0) {
            String previous = lines.get(row - 1);
            List<String> next = new ArrayList<String>(lines);
            next.set(row - 1, previous + lines.get(row));
            next.remove(row);
            return with(next, row - 1, previous.length());
        }
        return this;
    }

    /** Emacs C-d: delete the character under the caret, pulling the next line up at EOL. */
    public TextEditBuffer deleteForward() {
        String line = lines.get(row);
        if (column < line.length()) {
            return with(replaced(row, line.substring(0, column) + line.substring(column + 1)), row, column);
        }
        if (row < lines.size() - 1) {
            return joinNextLine();
        }
        return this;
    }

    /** Emacs M-DEL / readline C-w: delete the word before the caret. */
    public TextEditBuffer deleteWordBackward() {
        if (column > 0) {
            String line = lines.get(row);
            int start = wordBoundaryLeft(line, column);
            return with(replaced(row, line.substring(0, start) + line.substring(column)), row, start);
        }
        return deleteBackward();
    }

    /** Emacs C-k: delete from the caret to end of line, or the newline at EOL. */
    public TextEditBuffer killLine() {
        String line = lines.get(row);
        if (column < line.length()) {
            return with(replaced(row, line.substring(0, column)), row, column);
        }
        if (row < lines.size() - 1) {
            return joinNextLine();
        }
        return this;
    }

    /** Emacs/readline C-u: delete from the start of the line up to the caret. */
    public TextEditBuffer killToLineStart() {
        if (column > 0) {
            return with(replaced(row, lines.get(row).substring(column)), row, 0);
        }
        return this;
    }

    /** Emacs C-t: swap the two characters around the caret and step forward. */
    public TextEditBuffer transposeCharacters() {
        String line = lines.get(row);
        int n = line.length();
        if (n < 2) {
            return this;
        }
        if (column >= n) {
            String edited = line.substring(0, n - 2) + line.charAt(n - 1) + line.charAt(n - 2);
            return with(replaced(row, edited), row, column);
        }
        if (column >= 1) {
            String edited = line.substring(0, column - 1) + line.charAt(column) + line.charAt(column - 1)
                    + line.substring(column + 1);
            return with(replaced(row, edited), row, column + 1);
        }
        return this;
    }

    // ── Static word-boundary helpers (reusable on their own) ─────────────────

    /**
     * The column at the start of the word at or before {@code column}: skip any
     * whitespace immediately to the left, then skip the word characters.
     */
    public static int wordBoundaryLeft(String line, int column) {
        int i = column;
        while (i > 0 && Character.isWhitespace(line.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && !Character.isWhitespace(line.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /**
     * The column at the end of the word at or after {@code column}: skip any
     * whitespace immediately to the right, then skip the word characters.
     */
    public static int wordBoundaryRight(String line, int column) {
        int n = line.length();
        int i = column;
        while (i < n && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        while (i < n && !Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private TextEditBuffer joinNextLine() {
        List<String> next = new ArrayList<String>(lines);
        next.set(row, lines.get(row) + lines.get(row + 1));
        next.remove(row + 1);
        return with(next, row, column);
    }

    private List<String> replaced(int index, String value) {
        List<String> next = new ArrayList<String>(lines);
        next.set(index, value);
        return next;
    }

    private TextEditBuffer with(List<String> source, int row, int column) {
        return new TextEditBuffer(source, row, column);
    }

    private static int clamp(int value, int low, int high) {
        if (value < low) {
            return low;
        }
        return Math.min(value, high);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TextEditBuffer)) {
            return false;
        }
        TextEditBuffer that = (TextEditBuffer) o;
        return row == that.row && column == that.column && lines.equals(that.lines);
    }

    @Override
    public int hashCode() {
        int result = lines.hashCode();
        result = 31 * result + row;
        result = 31 * result + column;
        return result;
    }

    @Override
    public String toString() {
        return "TextEditBuffer{row=" + row + ", column=" + column + ", lines=" + lines + '}';
    }
}
