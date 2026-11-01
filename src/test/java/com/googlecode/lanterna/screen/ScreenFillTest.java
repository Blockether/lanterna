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
package com.googlecode.lanterna.screen;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.graphics.TextImage;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.Test;

import java.io.IOException;
import java.util.Random;

import static org.junit.Assert.assertEquals;

/**
 * Verifies that a rectangle fill and a cell-array put on a screen or an image give the same back buffer as one
 * {@code setCharacter} per cell: clipping, the double-width neighbour of the first cell, double-width characters and
 * tabs.
 */
public class ScreenFillTest {

    private static final TerminalSize ROOT = new TerminalSize(23, 9);
    private static final char[] CHARACTERS = {' ', 'x', '\u4e2d', '\t', '\u00e9'};
    private static final TextColor[] COLORS = {TextColor.ANSI.RED, TextColor.ANSI.BLUE, TextColor.ANSI.DEFAULT};

    private static TerminalScreen screen() throws IOException {
        TerminalScreen s = new TerminalScreen(new DefaultVirtualTerminal(ROOT));
        s.startScreen();
        return s;
    }

    private static TextCharacter character(Random random) {
        return TextCharacter.fromCharacter(CHARACTERS[random.nextInt(CHARACTERS.length)],
                COLORS[random.nextInt(COLORS.length)], COLORS[random.nextInt(COLORS.length)])[0];
    }

    /** Draws the same random cells on both targets, so the fill starts next to double-width characters too. */
    private static void scatter(long seed, TextGraphics a, TextGraphics b) {
        Random random = new Random(seed);
        for(int i = 0; i < 60; i++) {
            int column = random.nextInt(ROOT.getColumns());
            int row = random.nextInt(ROOT.getRows());
            TextCharacter c = character(random);
            a.setCharacter(column, row, c);
            b.setCharacter(column, row, c);
        }
    }

    /** Takes a view of the target: the target itself, a clipped box or a box with a negative origin. */
    private static TextGraphics view(TextGraphics g, int kind) {
        switch(kind) {
            case 1:
                return g.newTextGraphics(new TerminalPosition(3, 2), new TerminalSize(14, 5));
            case 2:
                return g.newTextGraphics(new TerminalPosition(3, 2), new TerminalSize(14, 5))
                        .newTextGraphics(new TerminalPosition(-2, -1), ROOT);
            default:
                return g;
        }
    }

    /** The fill of {@code DefaultShapeRenderer}: one write per cell, two columns for a double-width character. */
    private static void referenceFill(TextGraphics g, TerminalPosition topLeft, TerminalSize size, TextCharacter c) {
        boolean wide = c.isDoubleWidth();
        for(int y = 0; y < size.getRows(); y++) {
            for(int x = 0; x < size.getColumns(); x++) {
                g.setCharacter(topLeft.getColumn() + x, topLeft.getRow() + y,
                        wide && x + 1 == size.getColumns() ? c.withCharacter(' ') : c);
                if(wide) {
                    x++;
                }
            }
        }
    }

    private static void fillBoth(long seed, TextGraphics filled, TextGraphics reference) {
        Random random = new Random(seed);
        int kind = random.nextInt(3);
        TerminalPosition topLeft = new TerminalPosition(random.nextInt(30) - 6, random.nextInt(14) - 4);
        TerminalSize size = new TerminalSize(random.nextInt(30), random.nextInt(14));
        TextCharacter c = character(random);
        view(filled, kind).fillRectangle(topLeft, size, c);
        referenceFill(view(reference, kind), topLeft, size, c);
    }

    private static void assertSameCells(long seed, TextImage expected, TextImage actual) {
        for(int y = 0; y < ROOT.getRows(); y++) {
            for(int x = 0; x < ROOT.getColumns(); x++) {
                assertEquals("cell " + x + "," + y + " of seed " + seed,
                        expected.getCharacterAt(x, y), actual.getCharacterAt(x, y));
            }
        }
    }

    /** The cell loop of {@code AbstractTextGraphics.putString(int, int, TextCharacter[])}. */
    private static void referencePut(TextGraphics g, int column, int row, TextCharacter[] characters) {
        int previousDoubleCharacterCount = 0;
        for(int i = 0; i < characters.length; i++) {
            g.setCharacter(column + i + previousDoubleCharacterCount, row, characters[i]);
            if(characters[i].isDoubleWidth()) {
                previousDoubleCharacterCount++;
            }
        }
    }

    private static void putBoth(long seed, TextGraphics filled, TextGraphics reference) {
        Random random = new Random(seed);
        for(int line = 0; line < 4; line++) {
            int kind = random.nextInt(3);
            int column = random.nextInt(34) - 8;
            int row = random.nextInt(14) - 3;
            TextCharacter[] characters = new TextCharacter[random.nextInt(30)];
            for(int i = 0; i < characters.length; i++) {
                characters[i] = character(random);
            }
            view(filled, kind).putString(column, row, characters);
            referencePut(view(reference, kind), column, row, characters);
        }
    }

    @Test
    public void screenPutStringMatchesOneWritePerCell() throws IOException {
        for(long seed = 0; seed < 3000; seed++) {
            TerminalScreen filled = screen();
            TerminalScreen reference = screen();
            scatter(seed, filled.newTextGraphics(), reference.newTextGraphics());
            putBoth(seed, filled.newTextGraphics(), reference.newTextGraphics());
            assertSameCells(seed, reference.getBackBuffer(), filled.getBackBuffer());
        }
    }

    @Test
    public void screenFillMatchesOneWritePerCell() throws IOException {
        for(long seed = 0; seed < 3000; seed++) {
            TerminalScreen filled = screen();
            TerminalScreen reference = screen();
            scatter(seed, filled.newTextGraphics(), reference.newTextGraphics());
            fillBoth(seed, filled.newTextGraphics(), reference.newTextGraphics());
            assertSameCells(seed, reference.getBackBuffer(), filled.getBackBuffer());
        }
    }

    @Test
    public void imageFillMatchesOneWritePerCell() {
        for(long seed = 0; seed < 3000; seed++) {
            BasicTextImage filled = new BasicTextImage(ROOT);
            BasicTextImage reference = new BasicTextImage(ROOT);
            scatter(seed, filled.newTextGraphics(), reference.newTextGraphics());
            fillBoth(seed, filled.newTextGraphics(), reference.newTextGraphics());
            assertSameCells(seed, reference, filled);
        }
    }
}
