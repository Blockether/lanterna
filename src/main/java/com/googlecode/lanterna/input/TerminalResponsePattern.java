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

import java.util.List;

/**
 * Recognizes terminal replies to capability queries and gives a {@link TerminalResponse}:
 * <ul>
 *     <li>{@code ESC [ ? <digits and ;> $ y}: mode report (DECRPM)</li>
 *     <li>{@code ESC [ ? <digits and ;> c}: primary device attributes (DA1)</li>
 *     <li>{@code ESC P > | <printable text> ESC \}: terminal version (XTVERSION)</li>
 * </ul>
 */
public class TerminalResponsePattern implements CharacterPattern {
    private static final char ESC = 0x1b;
    private static final int MAX_LENGTH = 256;

    @Override
    public Matching match(List<Character> seq) {
        int size = seq.size();
        if(seq.get(0) != ESC || size > MAX_LENGTH) {
            return null;
        }
        if(size == 1) {
            return Matching.NOT_YET;
        }
        char kind = seq.get(1);
        if(kind == '[') {
            return matchModeReport(seq);
        }
        if(kind == 'P') {
            return matchVersion(seq);
        }
        return null;
    }

    private static Matching matchModeReport(List<Character> seq) {
        int size = seq.size();
        if(size == 2) {
            return Matching.NOT_YET;
        }
        if(seq.get(2) != '?') {
            return null;
        }
        for(int i = 3; i < size; i++) {
            char c = seq.get(i);
            if((c >= '0' && c <= '9') || c == ';') {
                continue;
            }
            if(c == 'c') {
                return i == size - 1 ? new Matching(new TerminalResponse(text(seq))) : null;
            }
            if(c == '$') {
                if(i == size - 1) {
                    return Matching.NOT_YET;
                }
                return seq.get(i + 1) == 'y' && i + 1 == size - 1 ? new Matching(new TerminalResponse(text(seq))) : null;
            }
            return null;
        }
        return Matching.NOT_YET;
    }

    private static Matching matchVersion(List<Character> seq) {
        int size = seq.size();
        String prefix = ">|";
        for(int i = 2; i < size; i++) {
            char c = seq.get(i);
            if(i < 4) {
                if(c != prefix.charAt(i - 2)) {
                    return null;
                }
            }
            else if(c == ESC) {
                if(i == size - 1) {
                    return Matching.NOT_YET;
                }
                if(seq.get(i + 1) == '\\' && i + 1 == size - 1) {
                    return new Matching(new TerminalResponse(text(seq)));
                }
                return null;
            }
            else if(c < 0x20 || c > 0x7e) {
                return null;
            }
        }
        return Matching.NOT_YET;
    }

    private static String text(List<Character> seq) {
        StringBuilder builder = new StringBuilder(seq.size());
        for(char c: seq) {
            builder.append(c);
        }
        return builder.toString();
    }
}
