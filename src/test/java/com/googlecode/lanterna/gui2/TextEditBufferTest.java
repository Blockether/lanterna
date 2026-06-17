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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class TextEditBufferTest {

    private static TextEditBuffer at(String single, int column) {
        return TextEditBuffer.of(Arrays.asList(single.split("\n", -1)), 0, column);
    }

    @Test
    public void emptyBuffer() {
        TextEditBuffer b = TextEditBuffer.create();
        assertTrue(b.isEmpty());
        assertEquals("", b.getText());
        assertEquals(0, b.getRow());
        assertEquals(0, b.getColumn());
    }

    @Test
    public void ofRoundTripsTextAndCaret() {
        TextEditBuffer b = TextEditBuffer.of("ab\ncde");
        assertEquals("ab\ncde", b.getText());
        assertEquals(1, b.getRow());
        assertEquals(3, b.getColumn());
        assertFalse(b.isEmpty());
    }

    @Test
    public void insertCharacterAdvancesCaret() {
        TextEditBuffer b = TextEditBuffer.create().insertCharacter('h').insertCharacter('i');
        assertEquals("hi", b.getText());
        assertEquals(2, b.getColumn());
    }

    @Test
    public void insertTextSplitsOnNewlines() {
        TextEditBuffer b = TextEditBuffer.create().insertText("a\nb");
        assertEquals("a\nb", b.getText());
        assertEquals(1, b.getRow());
        assertEquals(1, b.getColumn());
    }

    @Test
    public void newlineSplitsAtCaret() {
        TextEditBuffer b = at("abcd", 2).newline();
        assertEquals("ab\ncd", b.getText());
        assertEquals(1, b.getRow());
        assertEquals(0, b.getColumn());
    }

    @Test
    public void horizontalMotion() {
        TextEditBuffer b = at("abc", 1);
        assertEquals(0, b.moveLeft().getColumn());
        assertEquals(2, b.moveRight().getColumn());
        assertEquals(0, b.moveLineStart().getColumn());
        assertEquals(3, b.moveLineEnd().getColumn());
    }

    @Test
    public void moveLeftWrapsToPreviousLineEnd() {
        TextEditBuffer b = TextEditBuffer.of(Arrays.asList("ab", "cd"), 1, 0).moveLeft();
        assertEquals(0, b.getRow());
        assertEquals(2, b.getColumn());
    }

    @Test
    public void moveRightWrapsToNextLineStart() {
        TextEditBuffer b = TextEditBuffer.of(Arrays.asList("ab", "cd"), 0, 2).moveRight();
        assertEquals(1, b.getRow());
        assertEquals(0, b.getColumn());
    }

    @Test
    public void verticalMotionKeepsColumnWhenPossible() {
        TextEditBuffer b = TextEditBuffer.of(Arrays.asList("abcd", "ef"), 0, 3);
        TextEditBuffer down = b.moveDown();
        assertEquals(1, down.getRow());
        assertEquals(2, down.getColumn()); // clamped to shorter line
        assertEquals(0, down.moveUp().getRow());
    }

    @Test
    public void wordMotionWithinLine() {
        TextEditBuffer b = at("foo bar baz", 0);
        assertEquals(3, b.moveWordRight().getColumn());
        assertEquals(7, b.moveWordRight().moveWordRight().getColumn());
        TextEditBuffer end = at("foo bar baz", 11);
        assertEquals(8, end.moveWordLeft().getColumn());
    }

    @Test
    public void wordMotionAcrossLines() {
        TextEditBuffer b = TextEditBuffer.of(Arrays.asList("foo", "bar"), 1, 0).moveWordLeft();
        assertEquals(0, b.getRow());
        assertEquals(0, b.getColumn());
    }

    @Test
    public void deleteBackwardAndForward() {
        assertEquals("ac", at("abc", 2).deleteBackward().getText());
        assertEquals("ac", at("abc", 1).deleteForward().getText());
    }

    @Test
    public void deleteBackwardJoinsLines() {
        TextEditBuffer b = TextEditBuffer.of(Arrays.asList("ab", "cd"), 1, 0).deleteBackward();
        assertEquals("abcd", b.getText());
        assertEquals(0, b.getRow());
        assertEquals(2, b.getColumn());
    }

    @Test
    public void deleteForwardJoinsNextLine() {
        TextEditBuffer b = TextEditBuffer.of(Arrays.asList("ab", "cd"), 0, 2).deleteForward();
        assertEquals("abcd", b.getText());
        assertEquals(0, b.getRow());
        assertEquals(2, b.getColumn());
    }

    @Test
    public void deleteWordBackward() {
        TextEditBuffer b = at("foo bar", 7).deleteWordBackward();
        assertEquals("foo ", b.getText());
        assertEquals(4, b.getColumn());
    }

    @Test
    public void killLineAndKillToStart() {
        assertEquals("ab", at("abcd", 2).killLine().getText());
        TextEditBuffer toStart = at("abcd", 2).killToLineStart();
        assertEquals("cd", toStart.getText());
        assertEquals(0, toStart.getColumn());
    }

    @Test
    public void killLineAtEolJoinsNext() {
        TextEditBuffer b = TextEditBuffer.of(Arrays.asList("ab", "cd"), 0, 2).killLine();
        assertEquals("abcd", b.getText());
    }

    @Test
    public void transposeInMiddleStepsForward() {
        TextEditBuffer b = at("abc", 1).transposeCharacters();
        assertEquals("bac", b.getText());
        assertEquals(2, b.getColumn());
    }

    @Test
    public void transposeAtEndSwapsLastTwo() {
        TextEditBuffer b = at("abc", 3).transposeCharacters();
        assertEquals("acb", b.getText());
    }

    @Test
    public void transposeNoopBelowTwoChars() {
        assertEquals("a", at("a", 1).transposeCharacters().getText());
    }

    @Test
    public void boundsAreClampedNotThrown() {
        TextEditBuffer b = TextEditBuffer.of(Arrays.asList("ab"), 9, 9);
        assertEquals(0, b.getRow());
        assertEquals(2, b.getColumn());
        // motion at the extremes is a no-op rather than an exception
        assertEquals(b, b.moveRight().moveDown());
        assertEquals(TextEditBuffer.create(), TextEditBuffer.create().moveLeft().moveUp().deleteBackward());
    }

    @Test
    public void wordBoundaryHelpersAreStatic() {
        assertEquals(0, TextEditBuffer.wordBoundaryLeft("foo bar", 3));
        assertEquals(7, TextEditBuffer.wordBoundaryRight("foo bar", 4));
    }

    @Test
    public void immutabilityOriginalUnchanged() {
        TextEditBuffer original = at("abc", 1);
        original.insertCharacter('X');
        original.deleteForward();
        assertEquals("abc", original.getText());
        assertEquals(1, original.getColumn());
    }
}
