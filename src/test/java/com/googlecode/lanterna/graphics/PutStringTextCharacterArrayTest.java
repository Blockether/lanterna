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

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

/**
 * Verifies the pre-segmented {@link TextGraphics#putString(int, int, TextCharacter[])} overload paints a back buffer
 * byte-identical to the parsing {@link TextGraphics#putString(int, int, String)} for tab-free, newline-free input
 * (the contract callers who cache the array rely on), including double-width CJK / emoji column advance.
 */
public class PutStringTextCharacterArrayTest {

    private static TerminalScreen screen() throws IOException {
        TerminalScreen s = new TerminalScreen(new DefaultVirtualTerminal(new TerminalSize(60, 4)));
        s.startScreen();
        return s;
    }

    private static void assertSameBuffer(String line) throws IOException {
        TextColor fg = TextColor.ANSI.RED;
        TextColor bg = TextColor.ANSI.BLUE;

        TerminalScreen ref = screen();
        TextGraphics gref = ref.newTextGraphics();
        gref.setForegroundColor(fg);
        gref.setBackgroundColor(bg);
        gref.putString(2, 1, line);

        TerminalScreen arr = screen();
        TextGraphics garr = arr.newTextGraphics();
        garr.setForegroundColor(fg);
        garr.setBackgroundColor(bg);
        garr.putString(2, 1, TextCharacter.fromString(line, fg, bg));

        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 60; x++) {
                assertEquals("cell mismatch at (" + x + "," + y + ") for line <" + line + ">",
                        ref.getBackCharacter(x, y), arr.getBackCharacter(x, y));
            }
        }
    }

    @Test
    public void plainAsciiMatchesStringOverload() throws IOException {
        assertSameBuffer("hello world plain ascii");
    }

    @Test
    public void cjkDoubleWidthMatchesStringOverload() throws IOException {
        assertSameBuffer("\u65E5\u672C\u8A9E CJK wide chars");
    }

    @Test
    public void emojiMatchesStringOverload() throws IOException {
        assertSameBuffer("emoji \uD83D\uDE00\uD83C\uDF89 mix");
    }

    @Test
    public void mixedWidthMatchesStringOverload() throws IOException {
        assertSameBuffer("mixed \u65E5a\u672Cb\u8A9Ec end");
    }

    @Test
    public void emptyLineIsANoOp() throws IOException {
        assertSameBuffer("");
    }
}
