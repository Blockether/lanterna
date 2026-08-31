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

import java.util.List;

/**
 * Character pattern that matches characters pressed while ALT key is held down
 * 
 * @author Martin, Andreas
 */
public class AltAndCharacterPattern implements CharacterPattern {

    @Override
    public Matching match(List<Character> seq) {
        int size = seq.size();
        if (size > 2 || seq.get(0) != KeyDecodingProfile.ESC_CODE) {
            return null; // nope
        }
        if (size == 1) {
            return Matching.NOT_YET; // maybe later
        }
        char character = seq.get(1);
        if (character == '\n' || character == '\r') {
            return new Matching(new KeyStroke(KeyType.Enter, false, true));
        }
        if (character == 0x7f || character == 0x08) {
            return new Matching(new KeyStroke(KeyType.Backspace, false, true));
        }
        if (character == '\t') {
            return new Matching(new KeyStroke(KeyType.Tab, false, true));
        }
        if (Character.isISOControl(character)) return null;
        return new Matching(new KeyStroke(character, false, true));
    }
}
