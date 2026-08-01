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

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inline terminal image rendering — Kitty graphics protocol / iTerm2 inline
 * images, ported from vis's {@code terminal-image.clj} (itself a port of pi's
 * {@code packages/tui/src/terminal-image.ts}).
 * <p>
 * Two independent concerns live here:
 * <ol>
 *   <li>Capability detection — which graphics protocol (if any) the host
 *       terminal speaks, sniffed from the environment ({@code TERM_PROGRAM},
 *       {@code KITTY_WINDOW_ID}, {@code ITERM_SESSION_ID}, …). tmux/screen are
 *       treated as image-incapable because they mangle the pass-through.</li>
 *   <li>Pixel-free image probing + escape encoding — read a file head, sniff
 *       its intrinsic pixel dimensions (png/jpeg/gif/webp/bmp), and encode the
 *       base64 payload as a Kitty {@code \u001b_G…} or iTerm2
 *       {@code \u001b]1337;File=…} sequence sized to a cell box.</li>
 * </ol>
 * The escape strings are emitted DIRECTLY to the tty AFTER lanterna's delta
 * refresh (the screen loop owns that), placed over rows the renderer reserved
 * as blanks. Lanterna never sees the graphics bytes, so its cell diff stays
 * intact and the image survives subsequent delta frames.
 * <p>
 * All methods are static and thread-safe. Instances cannot be created.
 */
public final class TerminalImage {

    private TerminalImage() {
    }

    /** Inline-image protocol spoken by the host terminal. */
    public enum Protocol {
        KITTY,
        ITERM2
    }

    // =========================================================================
    // Capability detection (pi terminal-image.ts parity)
    // =========================================================================

    /**
     * Sniff which inline-image protocol the host terminal speaks from the
     * process environment. Returns {@code null} when none is available.
     */
    public static Protocol detectCapabilities() {
        return detectCapabilities(System.getenv());
    }

    /**
     * Sniff which inline-image protocol the host terminal speaks from
     * {@code env}. Returns {@link Protocol#KITTY}, {@link Protocol#ITERM2}, or
     * {@code null}. tmux and screen return {@code null} — they don't reliably
     * forward graphics.
     */
    public static Protocol detectCapabilities(Map<String, String> env) {
        String termProg = lower(env.get("TERM_PROGRAM"));
        String term = lower(env.get("TERM"));
        if (env.get("TMUX") != null || term.startsWith("tmux")) {
            return null;
        }
        if (term.startsWith("screen")) {
            return null;
        }
        if (env.get("KITTY_WINDOW_ID") != null || termProg.equals("kitty")) {
            return Protocol.KITTY;
        }
        if (termProg.equals("ghostty") || term.contains("ghostty") || env.get("GHOSTTY_RESOURCES_DIR") != null) {
            return Protocol.KITTY;
        }
        if (env.get("WEZTERM_PANE") != null || termProg.equals("wezterm")) {
            return Protocol.KITTY;
        }
        if (termProg.equals("warpterminal")
                || env.get("WARP_SESSION_ID") != null
                || env.get("WARP_TERMINAL_SESSION_UUID") != null) {
            return Protocol.KITTY;
        }
        if (env.get("ITERM_SESSION_ID") != null || termProg.equals("iterm.app")) {
            return Protocol.ITERM2;
        }
        return null;
    }

    /**
     * Whether the host terminal is <em>graphical</em> — i.e. speaks an
     * inline-image protocol ({@link Protocol#KITTY} or {@link Protocol#ITERM2}).
     * A {@code false} return means a plain text terminal (or tmux/screen, which
     * mangle graphics pass-through): callers should fall back to a text card.
     */
    public static boolean isGraphicalTerminal() {
        return detectCapabilities() != null;
    }

    /**
     * {@link #isGraphicalTerminal()} against an explicit {@code env} map.
     */
    public static boolean isGraphicalTerminal(Map<String, String> env) {
        return detectCapabilities(env) != null;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }

    // Terminal cell pixel size. The real values come from a terminal query at
    // startup; 9x18 matches pi's default and is close enough for box sizing.
    private static volatile int cellW = 9;
    private static volatile int cellH = 18;

    /** Set the terminal's cell pixel size (ignored unless both are positive). */
    public static void setCellDimensions(int w, int h) {
        if (w > 0 && h > 0) {
            cellW = w;
            cellH = h;
        }
    }

    /** The terminal's cell pixel width (see {@link #setCellDimensions}). */
    public static int cellWidth() {
        return cellW;
    }

    /** The terminal's cell pixel height (see {@link #setCellDimensions}). */
    public static int cellHeight() {
        return cellH;
    }

    // =========================================================================
    // Intrinsic pixel-dimension sniffing (pi getImageDimensions parity)
    // =========================================================================

    private static int u8(byte[] b, int i) {
        return b[i] & 0xff;
    }

    private static int u16be(byte[] b, int i) {
        return (u8(b, i) << 8) + u8(b, i + 1);
    }

    private static int u16le(byte[] b, int i) {
        return u8(b, i) + (u8(b, i + 1) << 8);
    }

    private static long u32be(byte[] b, int i) {
        return ((long) u8(b, i) << 24)
                + (u8(b, i + 1) << 16)
                + (u8(b, i + 2) << 8)
                + u8(b, i + 3);
    }

    private static long u32le(byte[] b, int i) {
        return u8(b, i)
                + (u8(b, i + 1) << 8)
                + (u8(b, i + 2) << 16)
                + ((long) u8(b, i + 3) << 24);
    }

    private static String ascii(byte[] b, int off, int n) {
        return new String(b, off, n, StandardCharsets.US_ASCII);
    }

    private static int[] pngDims(byte[] b) {
        return b.length >= 24 ? new int[]{(int) u32be(b, 16), (int) u32be(b, 20)} : null;
    }

    private static int[] jpegDims(byte[] b) {
        if (b.length < 4) {
            return null;
        }
        int off = 2;
        while (off < b.length - 9) {
            if (u8(b, off) != 0xff) {
                off++;
                continue;
            }
            int marker = u8(b, off + 1);
            if (marker >= 0xc0 && marker <= 0xc2) {
                return new int[]{u16be(b, off + 7), u16be(b, off + 5)};
            }
            int len = u16be(b, off + 2);
            if (len < 2) {
                return null;
            }
            off = off + 2 + len;
        }
        return null;
    }

    private static int[] gifDims(byte[] b) {
        return b.length >= 10 ? new int[]{u16le(b, 6), u16le(b, 8)} : null;
    }

    private static int[] webpDims(byte[] b) {
        if (b.length < 30) {
            return null;
        }
        String chunk = ascii(b, 12, 4);
        if (chunk.equals("VP8 ")) {
            return new int[]{u16le(b, 26) & 0x3fff, u16le(b, 28) & 0x3fff};
        }
        if (chunk.equals("VP8L")) {
            long bits = u32le(b, 21);
            int w = (int) (bits & 0x3fff) + 1;
            int h = (int) ((bits >> 14) & 0x3fff) + 1;
            return new int[]{w, h};
        }
        if (chunk.equals("VP8X")) {
            int w = 1 + (u8(b, 24) + (u8(b, 25) << 8) + (u8(b, 26) << 16));
            int h = 1 + (u8(b, 27) + (u8(b, 28) << 8) + (u8(b, 29) << 16));
            return new int[]{w, h};
        }
        return null;
    }

    private static int[] bmpDims(byte[] b) {
        return b.length >= 26 ? new int[]{(int) u32le(b, 18), Math.abs((int) u32le(b, 22))} : null;
    }

    /**
     * Intrinsic {@code [w, h]} pixel size from the leading bytes of an image, or
     * {@code null} when the mime is unsupported / the bytes are too short.
     */
    public static int[] imageDimensions(byte[] b, String mime) {
        if (b == null || mime == null) {
            return null;
        }
        try {
            switch (mime) {
                case "image/png":
                    return pngDims(b);
                case "image/jpeg":
                    return jpegDims(b);
                case "image/gif":
                    return gifDims(b);
                case "image/webp":
                    return webpDims(b);
                case "image/bmp":
                    return bmpDims(b);
                default:
                    return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readHead(File f, long n) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            int len = (int) Math.min(raf.length(), n);
            byte[] buf = new byte[len];
            raf.readFully(buf);
            return buf;
        }
    }

    /**
     * Read {@code path}'s head and sniff its {@code [w, h]} pixel dimensions,
     * or {@code null} on failure.
     */
    public static int[] probeDimensions(String path, String mime) {
        try {
            return imageDimensions(readHead(new File(path), 4100), mime);
        } catch (Throwable t) {
            return null;
        }
    }

    // =========================================================================
    // Cell-box sizing (pi calculateImageCellSize parity)
    // =========================================================================

    /**
     * Fit an image of {@code w}×{@code h} px into {@code maxCols}×{@code maxRows}
     * cells, aspect-preserving. {@code maxRows} may be {@code null} to leave the
     * height unbounded. Returns {@code [cols, rows]} (each &gt;= 1).
     */
    public static int[] cellSize(int w, int h, int maxCols, Integer maxRows) {
        int cw = cellW;
        int ch = cellH;
        long mc = Math.max(1L, (long) maxCols);
        Long mr = maxRows == null ? null : Math.max(1L, (long) maxRows);
        long iw = Math.max(1L, (long) w);
        long ih = Math.max(1L, (long) h);
        double wScale = (double) (mc * cw) / iw;
        double hScale = mr != null ? (double) (mr * ch) / ih : wScale;
        double scale = Math.min(wScale, hScale);
        long cols = (long) Math.ceil((iw * scale) / (double) cw);
        long rows = (long) Math.ceil((ih * scale) / (double) ch);
        long fcols = Math.max(1L, Math.min(mc, cols));
        long frows = Math.max(1L, mr != null ? Math.min(mr, rows) : rows);
        return new int[]{(int) fcols, (int) frows};
    }

    // =========================================================================
    // Escape encoding (pi encodeKitty / encodeITerm2 parity)
    // =========================================================================

    private static final int KITTY_CHUNK = 4096;
    private static final String ESC = "\u001b";

    /**
     * Kitty graphics {@code \u001b_G} transmit+display sequence for base64
     * {@code data}, sized to {@code cols}×{@code rows} cells (each appended only
     * when positive). {@code C=1} keeps the cursor put after placement.
     */
    public static String encodeKitty(String data, int cols, int rows) {
        StringBuilder head = new StringBuilder("a=T,f=100,q=2,C=1");
        if (cols > 0) {
            head.append(",c=").append(cols);
        }
        if (rows > 0) {
            head.append(",r=").append(rows);
        }
        return emitKitty(head.toString(), data);
    }

    /**
     * Kitty transmit+display sequence that shows only a VERTICAL SLICE of the
     * image — the cell rows {@code [cropTopRows, rows - cropBottomRows)} of a
     * {@code cols}×{@code rows} box — at native scale, via the protocol's source
     * rectangle ({@code x,y,w,h} in pixels). This lets an image scrolled partly
     * past the top or bottom edge of the transcript band render the visible part
     * instead of vanishing. {@code imgW}×{@code imgH} are the TRANSMITTED image's
     * pixel dimensions (the original file for a PNG pass-through, the scaled PNG
     * for a transcode) — the crop rectangle is derived from them. With no crop
     * (or missing dimensions) it is identical to {@link #encodeKitty(String,int,int)}.
     */
    public static String encodeKitty(String data, int cols, int rows,
                                     int cropTopRows, int cropBottomRows,
                                     int imgW, int imgH) {
        int ct = Math.max(0, cropTopRows);
        int cb = Math.max(0, cropBottomRows);
        int visRows = rows - ct - cb;
        if (visRows < 1) {
            visRows = 1;
        }
        if ((ct == 0 && cb == 0) || rows <= 0 || imgH <= 0) {
            return encodeKitty(data, cols, visRows);
        }
        long y = Math.round((double) imgH * ct / rows);
        long h = Math.round((double) imgH * visRows / rows);
        if (h < 1) {
            h = 1;
        }
        if (y + h > imgH) {
            h = imgH - y;
        }
        StringBuilder head = new StringBuilder("a=T,f=100,q=2,C=1");
        if (cols > 0) {
            head.append(",c=").append(cols);
        }
        head.append(",r=").append(visRows);
        head.append(",x=0,y=").append(y);
        if (imgW > 0) {
            head.append(",w=").append(imgW);
        }
        head.append(",h=").append(h);
        return emitKitty(head.toString(), data);
    }

    /** Chunk {@code data} into a Kitty {@code _G} transmit sequence for header {@code h}. */
    private static String emitKitty(String h, String data) {
        if (data.length() <= KITTY_CHUNK) {
            return ESC + "_G" + h + ";" + data + ESC + "\\";
        }
        int n = data.length();
        StringBuilder acc = new StringBuilder();
        int off = 0;
        boolean first = true;
        while (off < n) {
            int end = Math.min(n, off + KITTY_CHUNK);
            String chunk = data.substring(off, end);
            boolean last = end >= n;
            if (first) {
                acc.append(ESC).append("_G").append(h).append(",m=1;").append(chunk).append(ESC).append("\\");
            } else if (last) {
                acc.append(ESC).append("_Gm=0;").append(chunk).append(ESC).append("\\");
            } else {
                acc.append(ESC).append("_Gm=1;").append(chunk).append(ESC).append("\\");
            }
            off = end;
            first = false;
        }
        return acc.toString();
    }

    /** iTerm2 {@code \u001b]1337;File=} inline-image sequence for base64 {@code data}. */
    public static String encodeIterm2(String data, int cols) {
        return ESC + "]1337;File=inline=1;width=" + cols + ";height=auto;preserveAspectRatio=1:" + data + "\u0007";
    }

    // path -> {mtime, size, data}. Images are re-emitted on every scroll that
    // moves them; caching keeps a 5MB file off the read+encode path each time.
    private static final Map<String, Object[]> base64Cache = new ConcurrentHashMap<>();

    /**
     * Read {@code path} and base64-encode its bytes, or {@code null} on failure.
     * Cached by path + mtime + size so an unchanged file is encoded at most once.
     */
    public static String readBase64(String path) {
        try {
            File f = new File(path);
            long mtime = f.lastModified();
            long size = f.length();
            Object[] cached = base64Cache.get(path);
            if (cached != null
                    && ((Long) cached[0]).longValue() == mtime
                    && ((Long) cached[1]).longValue() == size) {
                return (String) cached[2];
            }
            String data = Base64.getEncoder().encodeToString(readHead(f, size));
            base64Cache.put(path, new Object[]{mtime, size, data});
            return data;
        } catch (Throwable t) {
            return null;
        }
    }
}
