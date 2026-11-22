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
package com.googlecode.lanterna.input;

import com.googlecode.lanterna.TerminalPosition;

import java.util.List;

/**
 * Pattern used to detect Xterm-protocol mouse events coming in on the standard input channel
 * Created by martin on 19/07/15.
 * 
 * @author Martin, Andreas
 */
public class MouseCharacterPattern implements CharacterPattern {
    private static final char[] LEGACY_PREFIX = { KeyDecodingProfile.ESC_CODE, '[', 'M' };
    private static final char[] SGR_PREFIX = { KeyDecodingProfile.ESC_CODE, '[', '<' };
    private boolean isMouseDown = false;
    /** The xterm modifier bits of a button code. */
    private static final int SHIFT_BIT = 0x04;
    private static final int ALT_BIT = 0x08;
    private static final int CTRL_BIT = 0x10;
    /** The wheel action of each low button bit pair: buttons 4, 5, 6 and 7. */
    private static final MouseActionType[] WHEEL_TYPES = {
            MouseActionType.SCROLL_UP, MouseActionType.SCROLL_DOWN,
            MouseActionType.SCROLL_LEFT, MouseActionType.SCROLL_RIGHT };

    @Override
    public Matching match(List<Character> sequence) {
        if (isPrefix(sequence, SGR_PREFIX)) {
            return matchSgr(sequence);
        }
        if (isPrefix(sequence, LEGACY_PREFIX)) {
            return matchLegacy(sequence);
        }
        return null;
    }

    private Matching matchSgr(List<Character> sequence) {
        if (sequence.size() < SGR_PREFIX.length) {
            return Matching.NOT_YET;
        }
        int[] fields = new int[3];
        int field = 0;
        boolean hasDigit = false;
        for (int index = SGR_PREFIX.length; index < sequence.size(); index++) {
            char character = sequence.get(index);
            if (character >= '0' && character <= '9') {
                long next = fields[field] * 10L + character - '0';
                if (next > Integer.MAX_VALUE) return null;
                fields[field] = (int) next;
                hasDigit = true;
            }
            else if (character == ';') {
                if (!hasDigit || field >= 2) return null;
                field++;
                hasDigit = false;
            }
            else if (character == 'M' || character == 'm') {
                if (index != sequence.size() - 1 || field != 2 || !hasDigit) return null;
                return new Matching(toSgrMouse(fields[0], fields[1], fields[2], character));
            }
            else {
                return null;
            }
        }
        return Matching.NOT_YET;
    }

    private MouseAction toSgrMouse(int code, int column, int row, char terminator) {
        int buttonBits = code & 0x03;
        boolean wheel = (code & 0x40) != 0;
        boolean motion = (code & 0x20) != 0;
        MouseActionType actionType;
        if (wheel) {
            actionType = WHEEL_TYPES[buttonBits];
        }
        else if (motion) {
            actionType = buttonBits == 3 ? MouseActionType.MOVE : MouseActionType.DRAG;
        }
        else if (terminator == 'm') {
            actionType = MouseActionType.CLICK_RELEASE;
        }
        else {
            actionType = MouseActionType.CLICK_DOWN;
        }
        int button = wheel ? buttonBits + 4 : (buttonBits == 3 ? 0 : buttonBits + 1);
        return new MouseAction(
                actionType,
                button,
                new TerminalPosition(Math.max(0, column - 1), Math.max(0, row - 1)),
                1,
                (code & CTRL_BIT) != 0, (code & ALT_BIT) != 0, (code & SHIFT_BIT) != 0);
    }

    private Matching matchLegacy(List<Character> sequence) {
        int size = sequence.size();
        if (size < LEGACY_PREFIX.length || size < 6) return Matching.NOT_YET;
        if (size > 6) return null;

        int code = sequence.get(3);
        int button = (code & 0x3) + 1;
        if (button == 4) button = 0;
        MouseActionType actionType;
        int actionCode = (sequence.get(3) & 0x60) >> 5;
        switch (actionCode) {
            case 1:
                if (button > 0) {
                    actionType = MouseActionType.CLICK_DOWN;
                    isMouseDown = true;
                }
                else {
                    actionType = MouseActionType.CLICK_RELEASE;
                    isMouseDown = false;
                }
                break;
            case 2:
            case 0:
                actionType = button == 0 ? MouseActionType.MOVE : MouseActionType.DRAG;
                break;
            case 3:
                actionType = WHEEL_TYPES[code & 0x03];
                button = (code & 0x03) + 4;
                break;
            default:
                return null;
        }
        if (isMouseDown && actionType == MouseActionType.MOVE) actionType = MouseActionType.DRAG;
        if (!isMouseDown && actionType == MouseActionType.DRAG) actionType = MouseActionType.MOVE;
        return new Matching(new MouseAction(
                actionType,
                button,
                new TerminalPosition(sequence.get(4) - 33, sequence.get(5) - 33),
                1,
                (code & CTRL_BIT) != 0, (code & ALT_BIT) != 0, (code & SHIFT_BIT) != 0));
    }

    private static boolean isPrefix(List<Character> sequence, char[] prefix) {
        if (sequence.size() > prefix.length) {
            for (int index = 0; index < prefix.length; index++) {
                if (sequence.get(index) != prefix[index]) return false;
            }
            return true;
        }
        for (int index = 0; index < sequence.size(); index++) {
            if (sequence.get(index) != prefix[index]) return false;
        }
        return true;
    }
}
