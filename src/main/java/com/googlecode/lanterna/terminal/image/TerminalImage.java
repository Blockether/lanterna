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

    // =========================================================================
    // Video (ISO-BMFF / MP4) — display size without decoding a frame
    // =========================================================================

    /**
     * Largest {@code moov} box pulled into memory while looking for a track
     * header. A track header is a few hundred bytes; anything past this is a
     * pathological (or hostile) file and is simply not probed.
     */
    private static final int MAX_MOOV = 32 * 1024 * 1024;

    private static long u64be(byte[] b, int i) {
        return (u32be(b, i) << 32) + u32be(b, i + 4);
    }

    /** True when {@code head} starts an ISO-BMFF container (a {@code ftyp} box at byte 4). */
    public static boolean isVideoHead(byte[] head) {
        return head != null && head.length >= 12 && "ftyp".equals(ascii(head, 4, 4));
    }

    /**
     * Mime for an ISO-BMFF head, from its major brand: {@code qt  } is QuickTime,
     * every other brand ({@code isom}, {@code mp42}, {@code avc1}, {@code M4V }, …)
     * is {@code video/mp4}. {@code null} when the bytes are not a container.
     */
    public static String videoMime(byte[] head) {
        if (!isVideoHead(head)) {
            return null;
        }
        return "qt  ".equals(ascii(head, 8, 4)) ? "video/quicktime" : "video/mp4";
    }

    /** Content-sniffed mime of {@code path} when it is a video container, else {@code null}. */
    public static String probeVideoMime(String path) {
        try {
            return videoMime(readHead(new File(path), 12));
        } catch (Throwable t) {
            return null;
        }
    }

    /** True for the mimes {@link #imageDimensions} resolves through the MP4 box walk. */
    public static boolean isVideoMime(String mime) {
        return "video/mp4".equals(mime)
                || "video/quicktime".equals(mime)
                || "video/x-m4v".equals(mime)
                || "video/mpeg4".equals(mime);
    }

    /**
     * {@code [w, h]} from a {@code tkhd} payload — the track's DISPLAY size as
     * 16.16 fixed point, honouring a 90°/270° display matrix (what a phone writes
     * for a portrait capture). {@code null} for a non-visual track, whose header
     * carries zeros.
     */
    private static int[] tkhdDims(byte[] b, int off, int end) {
        if (end - off < 4) {
            return null;
        }
        // version+flags, then v0 {creation, modification, id, reserved, duration}
        // as 32-bit fields (20 bytes) or v1 the same with 64-bit times (32 bytes).
        int base = off + 4 + (u8(b, off) == 1 ? 32 : 20);
        int matrix = base + 16;
        int dims = matrix + 36;
        if (dims + 8 > end) {
            return null;
        }
        int w = (int) (u32be(b, dims) >> 16);
        int h = (int) (u32be(b, dims + 4) >> 16);
        if (w <= 0 || h <= 0) {
            return null;
        }
        // Matrix {a b u / c d v / x y w}: a pure 90°/270° rotation zeroes a and d,
        // so the picture the viewer sees is the track box turned on its side.
        if (u32be(b, matrix) == 0 && u32be(b, matrix + 16) == 0
                && u32be(b, matrix + 4) != 0 && u32be(b, matrix + 12) != 0) {
            return new int[]{h, w};
        }
        return new int[]{w, h};
    }

    /**
     * Walk ISO-BMFF boxes in {@code b[off, end)}, descending {@code moov}/{@code trak}
     * to the first visual {@code tkhd}. Tolerates a TRUNCATED buffer (a file head):
     * a box that runs past {@code end} is parsed as far as it was read and then ends
     * the walk, so a faststart clip answers from its first few kilobytes.
     */
    private static int[] boxDims(byte[] b, int off, int end) {
        while (off + 8 <= end) {
            long size = u32be(b, off);
            String type = ascii(b, off + 4, 4);
            int header = 8;
            if (size == 1) {
                if (off + 16 > end) {
                    return null;
                }
                size = u64be(b, off + 8);
                header = 16;
            } else if (size == 0) {
                size = end - off;
            }
            if (size < header) {
                return null;
            }
            long boxEnd = off + size;
            int stop = (int) Math.min(boxEnd, end);
            if ("moov".equals(type) || "trak".equals(type)) {
                int[] d = boxDims(b, off + header, stop);
                if (d != null) {
                    return d;
                }
            } else if ("tkhd".equals(type)) {
                int[] d = tkhdDims(b, off + header, stop);
                if (d != null) {
                    return d;
                }
            }
            if (boxEnd > end) {
                return null;
            }
            off = (int) boxEnd;
        }
        return null;
    }

    /**
     * {@code [w, h]} of the first visual track in {@code f}, by SEEKING over the
     * top-level boxes to {@code moov} — a clip whose media data sits before its
     * track headers costs a handful of seeks, never a read of the samples.
     */
    private static int[] mp4Dims(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long pos = 0;
            byte[] hdr = new byte[16];
            while (pos + 8 <= len) {
                raf.seek(pos);
                raf.readFully(hdr, 0, 8);
                long size = u32be(hdr, 0);
                String type = ascii(hdr, 4, 4);
                int header = 8;
                if (size == 1) {
                    if (pos + 16 > len) {
                        return null;
                    }
                    raf.readFully(hdr, 8, 8);
                    size = u64be(hdr, 8);
                    header = 16;
                } else if (size == 0) {
                    size = len - pos;
                }
                if (size < header) {
                    return null;
                }
                if ("moov".equals(type)) {
                    long payload = size - header;
                    if (payload <= 0 || payload > MAX_MOOV) {
                        return null;
                    }
                    byte[] buf = new byte[(int) payload];
                    raf.seek(pos + header);
                    raf.readFully(buf);
                    return boxDims(buf, 0, buf.length);
                }
                pos += size;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Display {@code [w, h]} of the video at {@code path} without decoding a
     * frame — the head first (a faststart clip answers there), then a seek walk
     * to {@code moov}. {@code null} when it is not a container this understands.
     */
    public static int[] probeVideoDimensions(String path) {
        try {
            File f = new File(path);
            byte[] head = readHead(f, 4100);
            int[] fromHead = boxDims(head, 0, head.length);
            return fromHead != null ? fromHead : mp4Dims(f);
        } catch (Throwable t) {
            return null;
        }
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
                    return isVideoMime(mime) ? boxDims(b, 0, b.length) : null;
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
            File f = new File(path);
            byte[] head = readHead(f, 4100);
            if (isVideoMime(mime) || (mime == null && isVideoHead(head))) {
                int[] fromHead = boxDims(head, 0, head.length);
                return fromHead != null ? fromHead : mp4Dims(f);
            }
            return imageDimensions(head, mime);
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
        return emitKitty(kittyHead(cols, rows), data);
    }

    /** Transmit+display header for a whole image in a {@code cols}×{@code rows} box. */
    private static String kittyHead(int cols, int rows) {
        StringBuilder head = new StringBuilder("a=T,f=100,q=2,C=1");
        if (cols > 0) {
            head.append(",c=").append(cols);
        }
        if (rows > 0) {
            head.append(",r=").append(rows);
        }
        return head.toString();
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
        return emitKitty(croppedKittyHead(cols, rows, cropTopRows, cropBottomRows, imgW, imgH), data);
    }

    /** Transmit+display header for the visible vertical slice of a cropped image. */
    private static String croppedKittyHead(int cols, int rows,
                                           int cropTopRows, int cropBottomRows,
                                           int imgW, int imgH) {
        int ct = Math.max(0, cropTopRows);
        int cb = Math.max(0, cropBottomRows);
        int visRows = rows - ct - cb;
        if (visRows < 1) {
            visRows = 1;
        }
        if ((ct == 0 && cb == 0) || rows <= 0 || imgH <= 0) {
            return kittyHead(cols, visRows);
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
        return head.toString();
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

    // ---- byte[] payloads: base64 straight into the escape ------------------
    //
    // A video is a STREAM of stills: every frame is encoded, base64'd and chunked
    // again. Handing these methods the raw bytes keeps the full-size base64 String
    // (1.33x the payload, live until the escape is built) off the heap entirely —
    // the payload is encoded one 3 KiB slice at a time, straight into the escape's
    // own buffer, which is pre-sized so it never grows.

    private static final Base64.Encoder B64 = Base64.getEncoder();

    /** Raw bytes whose base64 is exactly {@link #KITTY_CHUNK} characters. */
    private static final int KITTY_RAW_CHUNK = KITTY_CHUNK / 4 * 3;

    /** Base64 length of {@code n} bytes. */
    private static int b64Length(int n) {
        return 4 * ((n + 2) / 3);
    }

    /** Append the base64 of {@code data[off, off + len)} to {@code acc} without an intermediate copy. */
    private static void appendBase64(StringBuilder acc, byte[] data, int off, int len) {
        java.nio.ByteBuffer out = B64.encode(java.nio.ByteBuffer.wrap(data, off, len));
        acc.append(new String(out.array(), out.arrayOffset() + out.position(), out.remaining(),
                StandardCharsets.ISO_8859_1));
    }

    /** Chunk the base64 of {@code data} into a Kitty {@code _G} transmit sequence for header {@code h}. */
    private static String emitKitty(String h, byte[] data) {
        int n = data.length;
        int b64 = b64Length(n);
        if (b64 <= KITTY_CHUNK) {
            StringBuilder acc = new StringBuilder(b64 + h.length() + 6);
            acc.append(ESC).append("_G").append(h).append(';');
            appendBase64(acc, data, 0, n);
            return acc.append(ESC).append('\\').toString();
        }
        int chunks = (n + KITTY_RAW_CHUNK - 1) / KITTY_RAW_CHUNK;
        StringBuilder acc = new StringBuilder(b64 + h.length() + 10 + chunks * 12);
        int off = 0;
        boolean first = true;
        while (off < n) {
            int len = Math.min(KITTY_RAW_CHUNK, n - off);
            boolean last = off + len >= n;
            if (first) {
                acc.append(ESC).append("_G").append(h).append(",m=1;");
            } else {
                acc.append(ESC).append(last ? "_Gm=0;" : "_Gm=1;");
            }
            appendBase64(acc, data, off, len);
            acc.append(ESC).append('\\');
            off += len;
            first = false;
        }
        return acc.toString();
    }

    /**
     * {@link #encodeKitty(String,int,int)} for a RAW payload (PNG bytes), base64'd
     * as it is chunked. Byte-identical output, without materialising the base64.
     */
    public static String encodeKitty(byte[] data, int cols, int rows) {
        return emitKitty(kittyHead(cols, rows), data);
    }

    /**
     * {@link #encodeKitty(String,int,int,int,int,int,int)} for a RAW payload — the
     * per-frame path for video playback, where the payload changes every frame and
     * nothing can be cached.
     */
    public static String encodeKitty(byte[] data, int cols, int rows,
                                     int cropTopRows, int cropBottomRows,
                                     int imgW, int imgH) {
        String h = croppedKittyHead(cols, rows, cropTopRows, cropBottomRows, imgW, imgH);
        return emitKitty(h, data);
    }

    /** iTerm2 inline-image sequence for a RAW payload. */
    public static String encodeIterm2(byte[] data, int cols) {
        StringBuilder acc = new StringBuilder(b64Length(data.length) + 64);
        acc.append(ESC).append("]1337;File=inline=1;width=").append(cols)
                .append(";height=auto;preserveAspectRatio=1:");
        appendBase64(acc, data, 0, data.length);
        return acc.append('\u0007').toString();
    }

    /**
     * Kitty {@code a=t} transmit-ONLY sequence: upload {@code data} under client
     * image id {@code id} WITHOUT displaying it, chunked exactly like
     * {@link #encodeKitty(String,int,int)}. A later {@code a=p} placement draws it
     * with no re-upload, which is what makes scrolling an image flicker-free.
     */
    public static String transmitKitty(String data, int id) {
        return emitKitty(transmitHead(id), data);
    }

    /** {@link #transmitKitty(String,int)} for a RAW payload (PNG bytes). */
    public static String transmitKitty(byte[] data, int id) {
        return emitKitty(transmitHead(id), data);
    }

    private static String transmitHead(int id) {
        return "a=t,i=" + id + ",f=100,q=2";
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
