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
package com.googlecode.lanterna.terminal.image;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.googlecode.lanterna.terminal.image.TerminalImage.Protocol;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.Test;

/**
 * Correctness tests for {@link TerminalImage}, mirroring the Clojure adapter's
 * expectations so the fork is self-verifying without vis on the classpath.
 */
public class TerminalImageTest {

    private static Map<String, String> env(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---- capability detection -------------------------------------------

    @Test
    public void kittyFamilyDetected() {
        assertEquals(Protocol.KITTY, TerminalImage.detectCapabilities(env("KITTY_WINDOW_ID", "1")));
        assertEquals(Protocol.KITTY, TerminalImage.detectCapabilities(env("TERM_PROGRAM", "ghostty")));
        assertEquals(Protocol.KITTY, TerminalImage.detectCapabilities(env("WEZTERM_PANE", "0")));
        assertEquals(Protocol.KITTY, TerminalImage.detectCapabilities(env("WARP_SESSION_ID", "x")));
    }

    @Test
    public void iterm2Detected() {
        assertEquals(Protocol.ITERM2, TerminalImage.detectCapabilities(env("ITERM_SESSION_ID", "w0")));
        assertEquals(Protocol.ITERM2, TerminalImage.detectCapabilities(env("TERM_PROGRAM", "iTerm.app")));
    }

    @Test
    public void multiplexersAndPlainReportNone() {
        assertNull(TerminalImage.detectCapabilities(env("TMUX", "/tmp/s", "KITTY_WINDOW_ID", "1")));
        assertNull(TerminalImage.detectCapabilities(env("TERM", "screen-256color", "KITTY_WINDOW_ID", "1")));
        assertNull(TerminalImage.detectCapabilities(env("TERM", "xterm-256color")));
        assertNull(TerminalImage.detectCapabilities(env()));
    }

    @Test
    public void graphicalTerminalPredicate() {
        assertTrue(TerminalImage.isGraphicalTerminal(env("KITTY_WINDOW_ID", "1")));
        assertTrue(TerminalImage.isGraphicalTerminal(env("ITERM_SESSION_ID", "w0")));
        assertFalse(TerminalImage.isGraphicalTerminal(env("TERM", "xterm-256color")));
        assertFalse(TerminalImage.isGraphicalTerminal(env("TMUX", "/tmp/s")));
    }

    // ---- dimension sniffing ---------------------------------------------

    @Test
    public void pngDimensionsFromHeader() {
        byte[] b = new byte[24];
        // IHDR width @16, height @20 (big-endian): 800 x 600
        b[16] = 0; b[17] = 0; b[18] = 0x03; b[19] = 0x20; // 0x0320 = 800
        b[20] = 0; b[21] = 0; b[22] = 0x02; b[23] = 0x58; // 0x0258 = 600
        assertArrayEquals(new int[]{800, 600}, TerminalImage.imageDimensions(b, "image/png"));
    }

    @Test
    public void gifDimensionsLittleEndian() {
        byte[] b = new byte[10];
        b[6] = 0x40; b[7] = 0x01; // width  = 0x0140 = 320
        b[8] = (byte) 0xF0; b[9] = 0; // height = 0x00F0 = 240
        assertArrayEquals(new int[]{320, 240}, TerminalImage.imageDimensions(b, "image/gif"));
    }

    @Test
    public void unsupportedOrShortReturnsNull() {
        assertNull(TerminalImage.imageDimensions(new byte[]{1, 2}, "image/png"));
        assertNull(TerminalImage.imageDimensions(new byte[24], "image/tiff"));
        assertNull(TerminalImage.imageDimensions(null, "image/png"));
    }

    // ---- cell-box sizing ------------------------------------------------

    @Test
    public void cellSizeIsAspectPreservingAndClamped() {
        // A wide image constrained by columns.
        int[] wide = TerminalImage.cellSize(1000, 100, 40, 20);
        assertTrue(wide[0] >= 1 && wide[0] <= 40);
        assertTrue(wide[1] >= 1 && wide[1] <= 20);
        // Width-bound: it should hit the column ceiling, not the row ceiling.
        assertEquals(40, wide[0]);
        assertTrue("wide image should not fill rows", wide[1] < 20);

        // Null maxRows leaves height unbounded but never below 1.
        int[] tall = TerminalImage.cellSize(100, 1000, 40, null);
        assertTrue(tall[0] >= 1 && tall[0] <= 40);
        assertTrue(tall[1] >= 1);
    }

    // ---- escape encoding ------------------------------------------------

    @Test
    public void encodeKittySmallIsSingleSequence() {
        String s = TerminalImage.encodeKitty("QUJD", 10, 5);
        assertTrue(s.startsWith("\u001b_Ga=T,f=100,q=2,C=1,c=10,r=5;QUJD"));
        assertTrue(s.endsWith("\u001b\\"));
        // A single non-chunked payload carries no m= continuation markers.
        assertFalse(s.contains(",m=1;"));
        assertFalse(s.contains("_Gm="));
    }

    @Test
    public void encodeKittyLargePayloadIsChunked() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append('A');
        }
        String s = TerminalImage.encodeKitty(sb.toString(), 0, 0);
        assertTrue("first chunk opens with m=1", s.contains(",m=1;"));
        assertTrue("middle chunks continue", s.contains("\u001b_Gm=1;"));
        assertTrue("final chunk closes with m=0", s.contains("\u001b_Gm=0;"));
    }

    @Test
    public void encodeIterm2Format() {
        String s = TerminalImage.encodeIterm2("QUJD", 12);
        assertEquals("\u001b]1337;File=inline=1;width=12;height=auto;preserveAspectRatio=1:QUJD\u0007", s);
    }

    // ---- read / transcode (round-trips through the fs) ------------------

    @Test
    public void readBase64RoundTripsAndCaches() throws Exception {
        File f = File.createTempFile("timg-read", ".bin");
        f.deleteOnExit();
        byte[] payload = "hello lanterna".getBytes("US-ASCII");
        java.nio.file.Files.write(f.toPath(), payload);
        String a = TerminalImage.readBase64(f.getAbsolutePath());
        assertNotNull(a);
        assertArrayEquals(payload, Base64.getDecoder().decode(a));
        // Same path/mtime/size returns the identical cached string instance.
        assertEquals(a, TerminalImage.readBase64(f.getAbsolutePath()));
    }

    @Test
    public void transcodePngBase64DownscalesIntoBox() throws Exception {
        File f = File.createTempFile("timg-src", ".png");
        f.deleteOnExit();
        BufferedImage img = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(img, "png", f);

        String data = TerminalImage.transcodePngBase64(f.getAbsolutePath(), 10, 5);
        assertNotNull(data);
        BufferedImage out = ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(data)));
        assertNotNull(out);
        // 10 cols * 9px = 90, 5 rows * 18px = 90 box; source 400x200 must shrink.
        assertTrue("width fits box", out.getWidth() <= 90);
        assertTrue("height fits box", out.getHeight() <= 90);
        assertTrue("aspect preserved (wider than tall)", out.getWidth() >= out.getHeight());
    }
}
