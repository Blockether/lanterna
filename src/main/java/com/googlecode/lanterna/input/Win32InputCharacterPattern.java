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

import static com.googlecode.lanterna.input.KeyDecodingProfile.ESC_CODE;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * This implementation of CharacterPattern matches the key reports of win32-input-mode,
 * {@code Esc [ Vk ; Sc ; Uc ; Kd ; Cs ; Rc _}. Windows Terminal and the Windows console
 * host send them after {@code Esc [ ? 9 0 0 1 h}; under the console host, for example in
 * WSL, they are the only way an application can tell Shift+Enter from Enter.<p>
 *
 * Each report describes one Windows key event: the virtual-key code, the scan code, the
 * UTF-16 code unit the key typed, whether the key went down, the control key state and the
 * repeat count. Omitted fields default to 0, the repeat count to 1.<p>
 *
 * Enter, Tab, Backspace and Escape decode like {@link ExtendedKeyCharacterPattern} reports
 * and keep every modifier, so Shift+Enter is {@link KeyType#Enter} with shift. Arrows,
 * editing and function keys decode like their legacy escape sequences. Every other key
 * decodes to the KeyStroke of the bytes the console host sends for it outside
 * win32-input-mode, so text and Ctrl and Alt chords behave as before, and a character
 * typed with AltGr (Polish ą, German @) stays plain text. Key releases and modifier keys
 * pressed alone carry no KeyStroke: {@link InputDecoder} consumes their reports without
 * returning anything.<p>
 *
 * Terminals send these reports only when asked to; see
 * {@link com.googlecode.lanterna.terminal.ansi.ANSITerminal#setWin32InputMode(boolean)}.
 */
public class Win32InputCharacterPattern implements CharacterPattern {
    // A longer parameter list is not a key report; stop waiting for its end.
    private static final int MAX_SEQUENCE_LENGTH = 64;
    private static final int FIELDS = 6;
    // dwControlKeyState flags
    private static final int RIGHT_ALT_PRESSED = 0x1, LEFT_ALT_PRESSED = 0x2;
    private static final int RIGHT_CTRL_PRESSED = 0x4, LEFT_CTRL_PRESSED = 0x8, SHIFT_PRESSED = 0x10;
    private static final int ALT_PRESSED = RIGHT_ALT_PRESSED | LEFT_ALT_PRESSED;
    private static final int CTRL_PRESSED = RIGHT_CTRL_PRESSED | LEFT_CTRL_PRESSED;
    // virtual-key codes
    private static final int VK_BACK = 0x08, VK_TAB = 0x09, VK_RETURN = 0x0D, VK_MENU = 0x12, VK_ESCAPE = 0x1B;
    private static final int VK_SPACE = 0x20, VK_PRIOR = 0x21, VK_INSERT = 0x2D, VK_DELETE = 0x2E;
    private static final int VK_NUMPAD0 = 0x60, VK_NUMPAD9 = 0x69, VK_F1 = 0x70;
    // VK_PRIOR to VK_DOWN, in virtual-key order
    private static final KeyType[] NAVIGATION_KEYS = {
            KeyType.PageUp, KeyType.PageDown, KeyType.End, KeyType.Home,
            KeyType.ArrowLeft, KeyType.ArrowUp, KeyType.ArrowRight, KeyType.ArrowDown
    };
    private static final KeyType[] FUNCTION_KEYS = {
            KeyType.F1, KeyType.F2, KeyType.F3, KeyType.F4, KeyType.F5, KeyType.F6, KeyType.F7,
            KeyType.F8, KeyType.F9, KeyType.F10, KeyType.F11, KeyType.F12, KeyType.F13, KeyType.F14,
            KeyType.F15, KeyType.F16, KeyType.F17, KeyType.F18, KeyType.F19
    };
    // what Ctrl+2 to Ctrl+8 type, as in xterm
    private static final char[] CTRL_DIGITS = {0, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x7f};

    @Override
    public Matching match(List<Character> seq) {
        int size = seq.size();
        if (size == 0 || seq.get(0) != ESC_CODE) {
            return null;
        }
        if (size > 1 && seq.get(1) != '[') {
            return null;
        }
        for (int i = 2; i < size; i++) {
            char ch = seq.get(i);
            boolean last = i == size - 1;
            if ((ch >= '0' && ch <= '9') || ch == ';') {
                if (last) {
                    return size < MAX_SEQUENCE_LENGTH ? Matching.NOT_YET : null;
                }
            } else if (last && i > 2 && ch == '_') {
                return new Matching(decode(parameters(seq, i)));
            } else {
                return null;
            }
        }
        return Matching.NOT_YET;
    }

    private static String parameters(List<Character> seq, int end) {
        StringBuilder sb = new StringBuilder(end - 2);
        for (int i = 2; i < end; i++) {
            sb.append(seq.get(i).charValue());
        }
        return sb.toString();
    }

    // Esc [ Vk ; Sc ; Uc ; Kd ; Cs ; Rc _ -- consumed whole, even when malformed.
    private static KeyStroke decode(String parameters) {
        String[] fields = parameters.split(";", -1);
        if (fields.length > FIELDS) {
            return InputDecoder.IGNORED;
        }
        int virtualKey = field(fields, 0);
        int unicodeChar = field(fields, 2);
        int keyDown = field(fields, 3);
        int controlKeyState = field(fields, 4);
        if (virtualKey < 0 || unicodeChar < 0 || unicodeChar > 0xFFFF || keyDown < 0 || controlKeyState < 0) {
            return InputDecoder.IGNORED;
        }
        if (keyDown == 0) {
            // Releasing Alt after typing a character code on the keypad types that character.
            return virtualKey == VK_MENU && unicodeChar != 0
                    ? legacy(String.valueOf((char) unicodeChar))
                    : InputDecoder.IGNORED;
        }
        return keyDown(virtualKey, unicodeChar, controlKeyState);
    }

    // Decimal digits only; omitted -> 0, anything else (overflow) -> -1.
    private static int field(String[] fields, int index) {
        if (index >= fields.length || fields[index].isEmpty()) {
            return 0;
        }
        String digits = fields[index];
        if (digits.length() > 7) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < digits.length(); i++) {
            value = value * 10 + (digits.charAt(i) - '0');
        }
        return value;
    }

    private static KeyStroke keyDown(int virtualKey, int unicodeChar, int controlKeyState) {
        boolean anyAlt = (controlKeyState & ALT_PRESSED) != 0;
        boolean anyCtrl = (controlKeyState & CTRL_PRESSED) != 0;
        // Windows reports AltGr as Ctrl+Alt: a graphic character typed with both is AltGr text,
        // the heuristic of Windows Terminal's own key encoder.
        boolean altGr = anyAlt && anyCtrl && unicodeChar > ' ' && unicodeChar != 0x7f;
        boolean ctrl = (controlKeyState & CTRL_PRESSED) == CTRL_PRESSED || (anyCtrl && !altGr);
        boolean alt = (controlKeyState & ALT_PRESSED) == ALT_PRESSED || (anyAlt && !altGr);
        boolean shift = (controlKeyState & SHIFT_PRESSED) != 0;
        int modifierValue = 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0);
        switch (virtualKey) {
            case VK_RETURN:
                return ExtendedKeyCharacterPattern.keyStroke('\r', -1, modifierValue);
            case VK_TAB:
                return ExtendedKeyCharacterPattern.keyStroke('\t', -1, modifierValue);
            case VK_BACK:
                return ExtendedKeyCharacterPattern.keyStroke(0x7f, -1, modifierValue);
            case VK_ESCAPE:
                return ExtendedKeyCharacterPattern.keyStroke(ESC_CODE, -1, modifierValue);
            default:
                break;
        }
        if (virtualKey >= VK_PRIOR && virtualKey < VK_PRIOR + NAVIGATION_KEYS.length) {
            return new KeyStroke(NAVIGATION_KEYS[virtualKey - VK_PRIOR], ctrl, alt, shift);
        }
        if (virtualKey == VK_INSERT || virtualKey == VK_DELETE) {
            return new KeyStroke(virtualKey == VK_INSERT ? KeyType.Insert : KeyType.Delete, ctrl, alt, shift);
        }
        if (virtualKey >= VK_F1 && virtualKey < VK_F1 + FUNCTION_KEYS.length) {
            return new KeyStroke(FUNCTION_KEYS[virtualKey - VK_F1], ctrl, alt, shift);
        }
        if (alt && !ctrl && virtualKey >= VK_NUMPAD0 && virtualKey <= VK_NUMPAD9) {
            // Alt with keypad digits composes a character code, typed when Alt is released.
            return InputDecoder.IGNORED;
        }
        int character;
        if (unicodeChar != 0 && !(ctrl && virtualKey == VK_SPACE)) {
            character = ctrl ? controlCharacter(unicodeChar) : unicodeChar;
        } else if (ctrl || alt) {
            // Nothing typed: fall back to the unmodified key, as the console host does.
            int unmodified = unmodifiedCharacter(virtualKey);
            if (unmodified < 0) {
                return InputDecoder.IGNORED;
            }
            character = ctrl ? controlCharacter(unmodified) : unmodified;
        } else {
            return InputDecoder.IGNORED;
        }
        String bytes = String.valueOf((char) character);
        KeyStroke keyStroke = legacy(alt ? ESC_CODE + bytes : bytes);
        return keyStroke != null ? keyStroke : InputDecoder.IGNORED;
    }

    // The unshifted character of a key that is the same on every layout, or -1.
    private static int unmodifiedCharacter(int virtualKey) {
        if (virtualKey >= '0' && virtualKey <= '9') {
            return virtualKey;
        }
        if (virtualKey >= 'A' && virtualKey <= 'Z') {
            return Character.toLowerCase(virtualKey);
        }
        return virtualKey == VK_SPACE ? ' ' : -1;
    }

    // The control character Ctrl turns a character into, as in Windows Terminal and xterm.
    private static int controlCharacter(int character) {
        if (character >= '@' && character <= '~') {
            return character & 0x1f;
        }
        if (character == ' ') {
            return 0;
        }
        if (character == '/') {
            return 0x1f;
        }
        if (character == '?') {
            return 0x7f;
        }
        if (character >= '2' && character <= '8') {
            return CTRL_DIGITS[character - '2'];
        }
        return character;
    }

    // The KeyStroke that the same key's legacy bytes decode to, or null.
    private static KeyStroke legacy(String bytes) {
        List<Character> seq = new ArrayList<>(bytes.length());
        for (int i = 0; i < bytes.length(); i++) {
            seq.add(bytes.charAt(i));
        }
        KeyStroke keyStroke = null;
        for (CharacterPattern pattern : LegacyPatterns.PATTERNS) {
            Matching matching = pattern.match(seq);
            if (matching != null && matching.fullMatch != null) {
                keyStroke = matching.fullMatch;
            }
        }
        return keyStroke;
    }

    // Loaded on first use: DefaultKeyDecodingProfile creates this pattern while it initializes.
    private static final class LegacyPatterns {
        private static final Collection<CharacterPattern> PATTERNS = new DefaultKeyDecodingProfile().getPatterns();
    }
}
