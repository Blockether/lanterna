/*
 * This file is part of lanterna (https://github.com/mabe02/lanterna).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Copyright (C) 2010-2024 Martin Berglund
 */
package com.googlecode.lanterna.gui2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

public class TextEditKeymapTest {

    private static TextEditBuffer buf(String text, int row, int column) {
        return TextEditBuffer.of(Arrays.asList(text.split("\n", -1)), row, column);
    }

    private static KeyStroke ctrl(char c) {
        return new KeyStroke(c, true, false);
    }

    @Test
    public void ctrlA_movesToLineStart() {
        assertEquals(0, TextEditKeymap.apply(buf("hello", 0, 3), ctrl('a')).getColumn());
    }

    @Test
    public void ctrlE_movesToLineEnd() {
        assertEquals(5, TextEditKeymap.apply(buf("hello", 0, 1), ctrl('e')).getColumn());
    }

    @Test
    public void ctrlB_movesBackwardOneChar() {
        assertEquals(2, TextEditKeymap.apply(buf("hello", 0, 3), ctrl('b')).getColumn());
    }

    @Test
    public void ctrlF_movesForwardOneChar() {
        assertEquals(4, TextEditKeymap.apply(buf("hello", 0, 3), ctrl('f')).getColumn());
    }

    @Test
    public void ctrlP_movesToPreviousLine() {
        assertEquals(0, TextEditKeymap.apply(buf("aa\nbbbb", 1, 2), ctrl('p')).getRow());
    }

    @Test
    public void ctrlN_movesToNextLine() {
        assertEquals(1, TextEditKeymap.apply(buf("aaaa\nbb", 0, 2), ctrl('n')).getRow());
    }

    @Test
    public void ctrlK_killsToLineEnd() {
        assertEquals("hel", TextEditKeymap.apply(buf("hello", 0, 3), ctrl('k')).getText());
    }

    @Test
    public void ctrlU_killsToLineStart() {
        assertEquals("lo", TextEditKeymap.apply(buf("hello", 0, 3), ctrl('u')).getText());
    }

    @Test
    public void ctrlW_killsWordBackward() {
        assertEquals("foo ", TextEditKeymap.apply(buf("foo bar", 0, 7), ctrl('w')).getText());
    }

    @Test
    public void ctrlD_deletesForwardChar() {
        assertEquals("helo", TextEditKeymap.apply(buf("hello", 0, 2), ctrl('d')).getText());
    }

    @Test
    public void ctrlT_transposesChars() {
        // Emacs transpose-chars: swap the two characters around the caret.
        assertEquals("hlelo", TextEditKeymap.apply(buf("hello", 0, 2), ctrl('t')).getText());
    }

    @Test
    public void uppercaseChordStillBinds() {
        // Shift/Caps must not matter — Ctrl+A and Ctrl+Shift+A both move to start.
        assertEquals(0, TextEditKeymap.apply(buf("hello", 0, 3), new KeyStroke('A', true, false)).getColumn());
    }

    @Test
    public void nonCtrlCharacterIsNotABinding() {
        assertNull(TextEditKeymap.apply(buf("hello", 0, 3), new KeyStroke('a', false, false)));
        assertTrue(!TextEditKeymap.isBinding(new KeyStroke('a', false, false)));
    }

    @Test
    public void unboundCtrlLetterReturnsNull() {
        // Ctrl+R is not an editing chord — the host keeps it for an app verb.
        assertNull(TextEditKeymap.apply(buf("hello", 0, 3), ctrl('r')));
        assertTrue(!TextEditKeymap.isBinding(ctrl('r')));
    }

    @Test
    public void nonCharacterKeyIsNotABinding() {
        assertNull(TextEditKeymap.apply(buf("hello", 0, 3), new KeyStroke(KeyType.Enter)));
        assertTrue(!TextEditKeymap.isBinding(new KeyStroke(KeyType.ArrowLeft)));
    }
}
