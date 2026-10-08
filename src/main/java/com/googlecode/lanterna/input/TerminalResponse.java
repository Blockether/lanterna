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

/**
 * A terminal reply to a capability query: a mode report (DECRPM), the primary device attributes (DA1) or the
 * terminal version (XTVERSION). It is not a key that the user pressed.
 */
public class TerminalResponse extends KeyStroke {
    private final String sequence;

    /**
     * Creates the reply from its raw characters.
     * @param sequence Raw reply, with the leading escape character
     */
    public TerminalResponse(String sequence) {
        super(KeyType.TerminalResponse);
        this.sequence = sequence;
    }

    /** @return Raw reply, with the leading escape character */
    public String getSequence() {
        return sequence;
    }

    /** @return {@code true} if this is the reply to the primary device attributes query (DA1) */
    public boolean isPrimaryDeviceAttributes() {
        return sequence.startsWith("\033[?") && sequence.endsWith("c");
    }

    @Override
    public String toString() {
        return "TerminalResponse{sequence=" + sequence.replace("\033", "\\e") + '}';
    }
}
