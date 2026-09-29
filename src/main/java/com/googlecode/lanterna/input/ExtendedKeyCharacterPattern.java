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

import java.util.List;

/**
 * This implementation of CharacterPattern matches the extended key reports that
 * terminals send for modified keys the legacy encoding cannot tell apart, most
 * importantly Shift+Enter.<p>
 *
 * Two encodings are recognized:
 * <ul>
 * <li>{@code Esc [ code[:shifted[:base]] ; modifiers[:event] ; text u}: the "CSI u"
 * form of fixterms, the kitty keyboard protocol and tmux's
 * {@code extended-keys-format csi-u}. Shift+Enter is {@code Esc [ 1 3 ; 2 u}.</li>
 * <li>{@code Esc [ 2 7 ; modifiers ; code ~}: xterm's modifyOtherKeys, also sent by
 * WezTerm and by tmux's default {@code extended-keys-format xterm}. Shift+Enter is
 * {@code Esc [ 2 7 ; 2 ; 1 3 ~}.</li>
 * </ul>
 * The modifier value is 1 + the sum of shift (1), alt (2), ctrl (4) and meta (32,
 * reported as alt); super, hyper and the lock modifiers are ignored.<p>
 *
 * A key that also has a legacy encoding decodes to the same KeyStroke as that
 * encoding: Escape stays {@link KeyType#Escape}, Shift+Tab stays
 * {@link KeyType#ReverseTab}, Ctrl+M stays {@link KeyType#Enter} and Alt+Shift+B
 * stays {@code 'B'} with alt. Release events and keys without a matching KeyType
 * decode to {@link KeyType#Unknown}, so their sequence is consumed instead of
 * leaking into the input as text.<p>
 *
 * Terminals send these reports only when asked to; see
 * {@link com.googlecode.lanterna.terminal.ansi.ANSITerminal#setKittyKeyboardProtocol(boolean)}
 * and {@link com.googlecode.lanterna.terminal.ansi.ANSITerminal#setModifyOtherKeys(boolean)}.
 * Konsole's default Shift+Return, {@code Esc O M}, is matched by
 * {@link EscapeSequenceCharacterPattern}.
 */
public class ExtendedKeyCharacterPattern implements CharacterPattern {
    // A longer parameter list is not a key report; stop waiting for its end.
    private static final int MAX_SEQUENCE_LENGTH = 64;
    // bit-values of the reported modifiers (value - 1)
    private static final int SHIFT = 1, ALT = 2, CTRL = 4, META = 32;
    private static final int RELEASE_EVENT = 3;
    // xterm's modifyOtherKeys introducer: Esc [ 27 ; modifiers ; code ~
    private static final int MODIFY_OTHER_KEYS = 27;
    // kitty reports keys without a Unicode code point in the private use area
    private static final int FUNCTIONAL_KEYS_START = 0xE000, FUNCTIONAL_KEYS_END = 0xF8FF;
    private static final int F13 = 57376, KEYPAD_0 = 57399, KEYPAD_ENTER = 57414;
    private static final String KEYPAD_CHARACTERS = "0123456789./*-+";
    private static final KeyType[] F13_TO_F19 = {
            KeyType.F13, KeyType.F14, KeyType.F15, KeyType.F16, KeyType.F17, KeyType.F18, KeyType.F19
    };
    private static final KeyType[] KEYPAD_NAVIGATION = {
            KeyType.ArrowLeft, KeyType.ArrowRight, KeyType.ArrowUp, KeyType.ArrowDown,
            KeyType.PageUp, KeyType.PageDown, KeyType.Home, KeyType.End, KeyType.Insert, KeyType.Delete
    };

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
            if (isParameter(ch)) {
                if (last) {
                    return size < MAX_SEQUENCE_LENGTH ? Matching.NOT_YET : null;
                }
            } else if (last && i > 2) {
                KeyStroke keyStroke = decode(parameters(seq, i), ch);
                return keyStroke != null ? new Matching(keyStroke) : null;
            } else {
                return null;
            }
        }
        return Matching.NOT_YET;
    }

    private static boolean isParameter(char ch) {
        return (ch >= '0' && ch <= '9') || ch == ';' || ch == ':';
    }

    private static String parameters(List<Character> seq, int end) {
        StringBuilder sb = new StringBuilder(end - 2);
        for (int i = 2; i < end; i++) {
            sb.append(seq.get(i).charValue());
        }
        return sb.toString();
    }

    private static KeyStroke decode(String parameters, char last) {
        String[] fields = parameters.split(";", -1);
        if (last == 'u') {
            return decodeCsiU(fields);
        }
        if (last == '~') {
            return decodeTilde(fields);
        }
        return null;
    }

    // Esc [ code[:shifted[:base]] ; modifiers[:event] ; text u -- consumed whole, even when malformed.
    private static KeyStroke decodeCsiU(String[] fields) {
        if (fields.length > 3) {
            return new KeyStroke(KeyType.Unknown);
        }
        String[] codes = fields[0].split(":", -1);
        String[] modifiers = fields.length > 1 ? fields[1].split(":", -1) : new String[]{""};
        if (codes.length > 3 || modifiers.length > 2) {
            return new KeyStroke(KeyType.Unknown);
        }
        int code = number(codes[0], -1);
        int shiftedCode = codes.length > 1 ? number(codes[1], -1) : -1;
        int modifierValue = number(modifiers[0], 1);
        int event = modifiers.length > 1 ? number(modifiers[1], 1) : 1;
        if (code < 0 || modifierValue < 1 || event < 1) {
            return new KeyStroke(KeyType.Unknown);
        }
        if (event == RELEASE_EVENT) {
            return new KeyStroke(KeyType.Unknown);
        }
        return keyStroke(code, shiftedCode, modifierValue);
    }

    // Esc [ 27 ; modifiers ; code ~ (xterm), or Esc [ code ; modifiers ~ for a kitty
    // functional key such as KP_BEGIN. Every other tilde sequence belongs to other patterns.
    private static KeyStroke decodeTilde(String[] fields) {
        int first = number(fields[0], -1);
        int modifierValue = fields.length > 1 ? number(fields[1], -1) : 1;
        if (fields.length == 3 && first == MODIFY_OTHER_KEYS) {
            int code = number(fields[2], -1);
            if (code < 0 || modifierValue < 1) {
                return null;
            }
            return keyStroke(code, -1, modifierValue);
        }
        if (fields.length <= 2 && first >= FUNCTIONAL_KEYS_START && first <= FUNCTIONAL_KEYS_END
                && modifierValue >= 1) {
            return keyStroke(first, -1, modifierValue);
        }
        return null;
    }

    // Decimal digits only; empty -> ifEmpty, anything else (sub-parameters, overflow) -> -1.
    private static int number(String digits, int ifEmpty) {
        if (digits.isEmpty()) {
            return ifEmpty;
        }
        if (digits.length() > 7) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < digits.length(); i++) {
            char ch = digits.charAt(i);
            if (ch < '0' || ch > '9') {
                return -1;
            }
            value = value * 10 + (ch - '0');
        }
        return value;
    }

    static KeyStroke keyStroke(int code, int shiftedCode, int modifierValue) {
        int modifiers = modifierValue - 1;
        boolean shift = (modifiers & SHIFT) != 0;
        boolean alt = (modifiers & (ALT | META)) != 0;
        boolean ctrl = (modifiers & CTRL) != 0;
        switch (code) {
            case '\r':
            case KEYPAD_ENTER:
                return new KeyStroke(KeyType.Enter, ctrl, alt, shift);
            case '\t':
                return shift ? new KeyStroke(KeyType.ReverseTab, ctrl, alt) : new KeyStroke(KeyType.Tab, ctrl, alt);
            case '\b':
            case 0x7f:
                return new KeyStroke(KeyType.Backspace, ctrl, alt, shift);
            case ESC_CODE:
                return new KeyStroke(KeyType.Escape, ctrl, alt, shift);
            default:
                break;
        }
        if (code >= F13 && code < F13 + F13_TO_F19.length) {
            return new KeyStroke(F13_TO_F19[code - F13], ctrl, alt, shift);
        }
        if (code >= KEYPAD_0 && code < KEYPAD_0 + KEYPAD_CHARACTERS.length()) {
            return characterKey(KEYPAD_CHARACTERS.charAt(code - KEYPAD_0), -1, ctrl, alt, shift);
        }
        if (code == KEYPAD_ENTER + 1) {
            return characterKey('=', -1, ctrl, alt, shift);
        }
        if (code == KEYPAD_ENTER + 2) {
            return characterKey(',', -1, ctrl, alt, shift);
        }
        int keypadNavigation = code - (KEYPAD_ENTER + 3);
        if (keypadNavigation >= 0 && keypadNavigation < KEYPAD_NAVIGATION.length) {
            return new KeyStroke(KEYPAD_NAVIGATION[keypadNavigation], ctrl, alt, shift);
        }
        if (!isText(code)) {
            return new KeyStroke(KeyType.Unknown);
        }
        return characterKey((char) code, shiftedCode, ctrl, alt, shift);
    }

    private static boolean isText(int code) {
        return code >= ' ' && code <= 0xFFFF && code != 0x7f
                && !(code >= 0x80 && code < 0xA0)
                && !(code >= FUNCTIONAL_KEYS_START && code <= FUNCTIONAL_KEYS_END)
                && !Character.isSurrogate((char) code);
    }

    private static KeyStroke characterKey(char character, int shiftedCode, boolean ctrl, boolean alt, boolean shift) {
        // The report names the unshifted key; legacy input delivers the shifted character without shift.
        if (shift && isText(shiftedCode)) {
            character = (char) shiftedCode;
            shift = false;
        } else if (shift && Character.isLowerCase(character) && Character.toUpperCase(character) != character) {
            character = Character.toUpperCase(character);
            shift = false;
        }
        if (ctrl && !shift) {
            // Legacy terminals send these Ctrl chords as the control byte of another key.
            switch (character) {
                case '[':
                    return new KeyStroke(KeyType.Escape, false, alt);
                case 'i':
                    return new KeyStroke(KeyType.Tab, false, alt);
                case 'j':
                case 'm':
                    return new KeyStroke(KeyType.Enter, false, alt);
                default:
                    break;
            }
        }
        return new KeyStroke(character, ctrl, alt, shift);
    }
}
