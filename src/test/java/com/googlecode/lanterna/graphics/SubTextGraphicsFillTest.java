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
package com.googlecode.lanterna.graphics;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

/**
 * Verifies that a fill through nested {@link SubTextGraphics} views paints the same back buffer as one write per
 * cell, which is how {@link DefaultShapeRenderer} filled the rectangle before the views passed it down whole.
 */
public class SubTextGraphicsFillTest {

    private static final TerminalSize ROOT = new TerminalSize(30, 10);

    private static TerminalScreen screen() throws IOException {
        TerminalScreen s = new TerminalScreen(new DefaultVirtualTerminal(ROOT));
        s.startScreen();
        return s;
    }

    /** Two nested views: a clipped box, then a view with a negative origin, as Vis uses for screen coordinates. */
    private static TextGraphics nested(TerminalScreen s) {
        return s.newTextGraphics()
                .newTextGraphics(new TerminalPosition(3, 2), new TerminalSize(20, 6))
                .newTextGraphics(new TerminalPosition(-2, -1), new TerminalSize(30, 10));
    }

    private static void assertSameFill(int col, int row, int columns, int rows, char c) throws IOException {
        TerminalScreen filled = screen();
        TextGraphics g = nested(filled);
        g.setForegroundColor(TextColor.ANSI.RED);
        g.setBackgroundColor(TextColor.ANSI.BLUE);
        g.fillRectangle(new TerminalPosition(col, row), new TerminalSize(columns, rows), c);

        TerminalScreen reference = screen();
        TextGraphics r = nested(reference);
        TextCharacter tc = TextCharacter.fromCharacter(c, TextColor.ANSI.RED, TextColor.ANSI.BLUE)[0];
        boolean wide = tc.isDoubleWidth();
        for(int y = 0; y < rows; y++) {
            for(int x = 0; x < columns; x++) {
                r.setCharacter(col + x, row + y, wide && x + 1 == columns ? tc.withCharacter(' ') : tc);
                if(wide) {
                    x++;
                }
            }
        }

        for(int y = 0; y < ROOT.getRows(); y++) {
            for(int x = 0; x < ROOT.getColumns(); x++) {
                assertEquals("cell " + x + "," + y + " of fill " + col + "," + row + " " + columns + "x" + rows,
                        reference.getBackCharacter(x, y), filled.getBackCharacter(x, y));
            }
        }
    }

    @Test
    public void fillsInsideTheViews() throws IOException {
        assertSameFill(4, 3, 6, 2, ' ');
    }

    @Test
    public void clipsEveryEdge() throws IOException {
        assertSameFill(-5, -5, 60, 30, ' ');
        assertSameFill(0, 0, 30, 10, 'x');
        assertSameFill(20, 5, 10, 10, ' ');
    }

    @Test
    public void ignoresEmptyAndOutsideRectangles() throws IOException {
        assertSameFill(4, 3, 0, 4, ' ');
        assertSameFill(40, 3, 5, 4, ' ');
        assertSameFill(4, -9, 5, 4, ' ');
    }

    @Test
    public void keepsDoubleWidthFills() throws IOException {
        assertSameFill(-1, 2, 7, 3, '\u4e2d');
    }
}
