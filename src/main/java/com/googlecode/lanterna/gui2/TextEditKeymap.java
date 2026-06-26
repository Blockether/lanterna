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

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

/**
 * The single source of truth for the Emacs-style editing keybindings shared by
 * every text input: the {@link TextBox} widget uses it directly, and host
 * editors that drive a {@link TextEditBuffer} themselves (e.g. the vis TUI
 * prompt) call it too — so the chords behave identically in <em>every</em> input
 * rather than being re-implemented (and drifting) per call site.
 *
 * <p>Bindings (all with Ctrl held):
 * <pre>
 *   C-a  beginning-of-line     C-e  end-of-line
 *   C-b  backward-char         C-f  forward-char
 *   C-p  previous-line         C-n  next-line
 *   C-k  kill-to-line-end      C-u  kill-to-line-start
 *   C-w  backward-kill-word    C-d  delete-forward-char
 *   C-t  transpose-chars
 * </pre>
 *
 * <p>Deliberately a peer of {@link TextEditBuffer} rather than a method on it:
 * {@code TextEditBuffer} stays {@code KeyStroke}-free (pure editing ops), and the
 * key-to-op mapping lives here in one place.
 */
public final class TextEditKeymap {
    private TextEditKeymap() {}

    /** Letters (lowercase) that carry an Emacs editing binding when Ctrl is held. */
    private static final String BINDINGS = "aebfpnkuwdt";

    /**
     * Apply the Emacs editing chord {@code key} to {@code buffer}, returning the
     * resulting buffer — or {@code null} when {@code key} is not one of the
     * bindings, so the caller can fall back to its own handling (insert the
     * character, run an app verb, etc.).
     */
    public static TextEditBuffer apply(TextEditBuffer buffer, KeyStroke key) {
        if (buffer == null || !isBinding(key)) {
            return null;
        }
        switch (Character.toLowerCase(key.getCharacter())) {
            case 'a': return buffer.moveLineStart();
            case 'e': return buffer.moveLineEnd();
            case 'b': return buffer.moveLeft();
            case 'f': return buffer.moveRight();
            case 'p': return buffer.moveUp();
            case 'n': return buffer.moveDown();
            case 'k': return buffer.killLine();
            case 'u': return buffer.killToLineStart();
            case 'w': return buffer.deleteWordBackward();
            case 'd': return buffer.deleteForward();
            case 't': return buffer.transposeCharacters();
            default:  return null;
        }
    }

    /** True when {@code key} is one of the Ctrl + letter Emacs editing chords. */
    public static boolean isBinding(KeyStroke key) {
        return key != null
                && key.getKeyType() == KeyType.Character
                && key.isCtrlDown()
                && !key.isAltDown()
                && key.getCharacter() != null
                && BINDINGS.indexOf(Character.toLowerCase(key.getCharacter())) >= 0;
    }
}
