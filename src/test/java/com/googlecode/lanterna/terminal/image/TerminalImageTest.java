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
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
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
    public void encodeKittyNoCropMatchesPlain() {
        String plain = TerminalImage.encodeKitty("QUJD", 40, 20);
        String zero = TerminalImage.encodeKitty("QUJD", 40, 20, 0, 0, 360, 720);
        assertEquals(plain, zero);
    }

    @Test
    public void encodeKittyCropsToVisibleSourceRectangle() {
        // 360x720px image in a 40x20 cell box; crop 5 rows off the top.
        String top = TerminalImage.encodeKitty("QUJD", 40, 20, 5, 0, 360, 720);
        assertTrue(top.startsWith("\u001b_Ga=T,f=100,q=2,C=1,c=40,r=15,x=0,y=180,w=360,h=540;"));
        // Bottom crop shrinks the source height but keeps the y-origin at 0.
        String bottom = TerminalImage.encodeKitty("QUJD", 40, 20, 0, 8, 360, 720);
        assertTrue(bottom.contains(",r=12,x=0,y=0,w=360,h=432;"));
        // Missing pixel dimensions fall back to the plain (uncropped) sequence.
        String noDims = TerminalImage.encodeKitty("QUJD", 40, 20, 5, 0, 0, 0);
        assertEquals(TerminalImage.encodeKitty("QUJD", 40, 15), noDims);
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
// ---- video (ISO-BMFF/MP4) probing --------------------------------------

    private static byte[] u32(long v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    /** ISO-BMFF box: 32-bit size, 4-char type, payload. */
    private static byte[] box(String type, byte[] payload) {
        return cat(u32(8 + payload.length), type.getBytes(StandardCharsets.US_ASCII), payload);
    }

    /**
     * A version-0 {@code tkhd} payload declaring {@code w}x{@code h} in 16.16 fixed
     * point, with either the identity display matrix or a 90 degree rotation.
     */
    private static byte[] tkhd(int w, int h, boolean rotated) {
        byte[] p = new byte[84];
        // 0: version+flags, 4..24: creation/modification/id/reserved/duration,
        // 24..40: reserved/layer/alternate_group/volume/reserved.
        long one = 0x00010000L;
        long[] matrix = rotated
                ? new long[]{0, one, 0, 0xFFFF0000L, 0, 0, 0, 0, 0x40000000L}
                : new long[]{one, 0, 0, 0, one, 0, 0, 0, 0x40000000L};
        for (int i = 0; i < 9; i++) {
            System.arraycopy(u32(matrix[i]), 0, p, 40 + i * 4, 4);
        }
        System.arraycopy(u32((long) w << 16), 0, p, 76, 4);
        System.arraycopy(u32((long) h << 16), 0, p, 80, 4);
        return p;
    }

    private static byte[] ftyp() {
        return box("ftyp", "isomiso2avc1mp41".getBytes(StandardCharsets.US_ASCII));
    }

    /** `moov` carrying a silent audio track (all-zero tkhd) before the video track. */
    private static byte[] moov(int w, int h, boolean rotated) {
        byte[] audio = box("trak", box("tkhd", new byte[84]));
        byte[] video = box("trak", box("tkhd", tkhd(w, h, rotated)));
        return box("moov", cat(audio, video));
    }

    @Test
    public void mp4DimensionsComeFromTheVideoTrackHeader() {
        byte[] clip = cat(ftyp(), moov(1920, 1080, false));
        assertArrayEquals(new int[]{1920, 1080}, TerminalImage.imageDimensions(clip, "video/mp4"));
        assertArrayEquals(new int[]{1920, 1080}, TerminalImage.imageDimensions(clip, "video/quicktime"));
        // A still mime must never be answered by the box walk (pngDims is
        // signature-lenient, so it may invent numbers — it must not invent these).
        assertFalse(Arrays.equals(new int[]{1920, 1080}, TerminalImage.imageDimensions(clip, "image/png")));
        assertNull(TerminalImage.imageDimensions(clip, "image/svg+xml"));
    }

    @Test
    public void mp4RotationMatrixSwapsTheDisplaySize() {
        byte[] portrait = cat(ftyp(), moov(1920, 1080, true));
        assertArrayEquals(new int[]{1080, 1920}, TerminalImage.imageDimensions(portrait, "video/mp4"));
    }

    @Test
    public void videoHeadIsSniffedByContent() {
        byte[] clip = cat(ftyp(), moov(64, 48, false));
        assertTrue(TerminalImage.isVideoHead(clip));
        assertEquals("video/mp4", TerminalImage.videoMime(clip));
        assertFalse(TerminalImage.isVideoHead(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13}));
        assertNull(TerminalImage.videoMime(new byte[]{1, 2, 3}));
        assertTrue(TerminalImage.isVideoMime("video/mp4"));
        assertFalse(TerminalImage.isVideoMime("image/gif"));
    }

    @Test
    public void probesAVideoWhoseTrackHeadersFollowTheSamples() throws Exception {
        // Not faststart: 8 KiB of media data sit before `moov`, past any file head,
        // so only the seek walk can answer.
        byte[] mdat = box("mdat", new byte[8192]);
        byte[] clip = cat(ftyp(), mdat, moov(640, 360, false));
        File f = File.createTempFile("lanterna-probe", ".mp4");
        f.deleteOnExit();
        java.nio.file.Files.write(f.toPath(), clip);
        String path = f.getAbsolutePath();
        assertArrayEquals(new int[]{640, 360}, TerminalImage.probeVideoDimensions(path));
        assertArrayEquals(new int[]{640, 360}, TerminalImage.probeDimensions(path, "video/mp4"));
        // Mime-less probing falls back to the content sniff.
        assertArrayEquals(new int[]{640, 360}, TerminalImage.probeDimensions(path, null));
        assertEquals("video/mp4", TerminalImage.probeVideoMime(path));
        assertNull(TerminalImage.probeVideoDimensions(path + ".missing"));
    }

    @Test
    public void probesAFaststartVideoFromItsHeadAlone() throws Exception {
        byte[] clip = cat(ftyp(), moov(320, 240, false), box("mdat", new byte[64]));
        File f = File.createTempFile("lanterna-faststart", ".mp4");
        f.deleteOnExit();
        java.nio.file.Files.write(f.toPath(), clip);
        assertArrayEquals(new int[]{320, 240}, TerminalImage.probeVideoDimensions(f.getAbsolutePath()));
    }

    // ---- raw-payload escape encoding ---------------------------------------

    private static byte[] payload(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 31 + (i >> 3));
        }
        return b;
    }

    @Test
    public void rawKittyEncodingMatchesTheBase64StringPath() {
        for (int n : new int[]{1, 3, 1000, 3072, 3073, 4096, 100_000}) {
            byte[] raw = payload(n);
            String b64 = Base64.getEncoder().encodeToString(raw);
            assertEquals("n=" + n,
                    TerminalImage.encodeKitty(b64, 40, 12),
                    TerminalImage.encodeKitty(raw, 40, 12));
            assertEquals("cropped n=" + n,
                    TerminalImage.encodeKitty(b64, 40, 12, 2, 3, 640, 480),
                    TerminalImage.encodeKitty(raw, 40, 12, 2, 3, 640, 480));
            assertEquals("iterm2 n=" + n,
                    TerminalImage.encodeIterm2(b64, 40),
                    TerminalImage.encodeIterm2(raw, 40));
        }
    }

    @Test
    public void transmitOnlySequenceChunksLikeTheDisplayPath() {
        // Bigger than one chunk, so the m=1 continuation path is exercised.
        byte[] raw = new byte[9000];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i * 31);
        }
        String b64 = Base64.getEncoder().encodeToString(raw);
        String fromBytes = TerminalImage.transmitKitty(raw, 9901);
        assertEquals(TerminalImage.transmitKitty(b64, 9901), fromBytes);
        assertTrue(fromBytes.startsWith("\u001b_Ga=t,i=9901,f=100,q=2,m=1;"));
        assertTrue(fromBytes.contains("\u001b_Gm=0;"));
        assertTrue(fromBytes.endsWith("\u001b\\"));
        // Transmit never displays: no placement keys ride along.
        assertFalse(fromBytes.contains("a=T"));
        assertFalse(fromBytes.contains(",c="));
    }

    // ---- cell-size reports --------------------------------------------------

    @Test
    public void parsesTheDirectCellSizeReply() {
        assertArrayEquals(new int[]{9, 18}, TerminalImage.parseCellSizeReport("\u001b[6;18;9t"));
    }

    @Test
    public void derivesTheCellFromTextAreaPixelsAndCells() {
        // 1600x900 px of text area over 100 cols x 30 rows => 16x30 px cells.
        assertArrayEquals(new int[]{16, 30},
                TerminalImage.parseCellSizeReport("\u001b[4;900;1600t\u001b[8;30;100t"));
        // Any order, interleaved with whatever else the tty had queued.
        assertArrayEquals(new int[]{8, 17},
                TerminalImage.parseCellSizeReport("junk\u001b[8;40;120t\u001b[4;680;960tmore"));
    }

    @Test
    public void aSilentOrUnparseableTerminalReportsNothing() {
        assertNull(TerminalImage.parseCellSizeReport(null));
        assertNull(TerminalImage.parseCellSizeReport(""));
        assertNull(TerminalImage.parseCellSizeReport("random noise"));
        assertNull(TerminalImage.parseCellSizeReport("\u001b[6;0;0t"));
        // Truncated replies (no terminator, one parameter) are not half-believed.
        assertNull(TerminalImage.parseCellSizeReport("\u001b[6;18;9"));
        assertNull(TerminalImage.parseCellSizeReport("\u001b[6;18t"));
    }

    @Test
    public void applyingAReportInstallsTheLiveCellMetrics() {
        try {
            assertTrue(TerminalImage.applyCellSizeReport("\u001b[6;20;10t"));
            assertEquals(10, TerminalImage.cellWidth());
            assertEquals(20, TerminalImage.cellHeight());
            assertFalse(TerminalImage.applyCellSizeReport("nothing here"));
            assertEquals(10, TerminalImage.cellWidth());
        } finally {
            TerminalImage.setCellDimensions(9, 18);
        }
    }

    @Test
    public void boxPixelsIsTheLongEdgeOfTheCellBox() {
        try {
            TerminalImage.setCellDimensions(10, 20);
            assertEquals(800, TerminalImage.boxPixels(80, 24));
            // A tall box wins on height.
            assertEquals(1000, TerminalImage.boxPixels(20, 50));
            // No row bound: width alone.
            assertEquals(200, TerminalImage.boxPixels(20, 0));
            // Never asks for a degenerate picture.
            assertEquals(800, TerminalImage.boxPixels(0, 0));
        } finally {
            TerminalImage.setCellDimensions(9, 18);
        }
    }

    // ---- placement of an already-transmitted image --------------------------

    @Test
    public void aFullPlacementReferencesTheImageIdAndCellBox() {
        assertEquals("\u001b_Ga=p,i=5,p=1,C=1,q=2,c=10,r=6\u001b\\",
                TerminalImage.placeKitty(5, 10, 6));
    }

    @Test
    public void aCroppedPlacementUsesTheSameSourceRectangleAsTheDisplayPath() {
        // crop-top 2 of 6 rows over a 120px-tall image => y=40; 3 visible rows => h=60.
        assertEquals("\u001b_Ga=p,i=5,p=1,C=1,q=2,c=10,r=3,x=0,y=40,w=100,h=60\u001b\\",
                TerminalImage.placeKitty(5, 10, 6, 2, 1, 100, 120));
        // The transmit+display header computes the identical rectangle.
        assertTrue(TerminalImage.encodeKitty("", 10, 6, 2, 1, 100, 120)
                .contains(",r=3,x=0,y=40,w=100,h=60"));
    }

    @Test
    public void deletingAPlacementKeepsTheDataAndFreeingDropsIt() {
        assertEquals("\u001b_Ga=d,d=i,i=5,q=2\u001b\\", TerminalImage.deleteKittyPlacement(5));
        assertEquals("\u001b_Ga=d,d=I,i=5,q=2\u001b\\", TerminalImage.freeKittyImage(5));
    }

    // ---- still images wearing a movie's container ---------------------------

    @Test
    public void heifAndAvifAreNotVideosDespiteTheFtypBox() {
        assertTrue(TerminalImage.isVideoHead(head("isom")));
        assertTrue(TerminalImage.isVideoHead(head("qt  ")));
        assertNull(TerminalImage.videoMime(head("heic")));
        assertNull(TerminalImage.videoMime(head("HEIC")));
        assertNull(TerminalImage.videoMime(head("avif")));
        assertNull(TerminalImage.videoMime(head("mif1")));
        assertFalse(TerminalImage.isVideoHead(null));
        assertFalse(TerminalImage.isVideoHead(new byte[3]));
    }

    /** A 16-byte ISO-BMFF head carrying major brand {@code brand}. */
    private static byte[] head(String brand) {
        byte[] b = new byte[16];
        b[3] = 16;
        byte[] ftyp = "ftyp".getBytes(StandardCharsets.US_ASCII);
        byte[] br = brand.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ftyp, 0, b, 4, 4);
        System.arraycopy(br, 0, b, 8, Math.min(4, br.length));
        return b;
    }

    @Test
    public void aMissingPathIsNeverAVideoSource() {
        assertFalse(TerminalImage.isVideoFile("/nope/missing.mp4"));
        assertFalse(TerminalImage.isVideoFile(null));
        // A known mime still answers without touching the disk.
        assertTrue(TerminalImage.isVideoSource("/nope/missing.mp4", "video/mp4"));
        assertFalse(TerminalImage.isVideoSource("/nope/missing.png", "image/png"));
    }

    // ---- dropped paths ------------------------------------------------------

    @Test
    public void aDroppedPathIsUnquotedUnescapedAndDeUrled() {
        assertEquals("/tmp/a clip.mp4", TerminalImage.pastedVideoPath("  '/tmp/a clip.mp4'  "));
        assertEquals("/tmp/a clip.mp4", TerminalImage.pastedVideoPath("/tmp/a\\ clip.mp4"));
        assertEquals("/tmp/clip.MOV", TerminalImage.pastedVideoPath("file:///tmp/clip.MOV"));
        assertEquals(System.getProperty("user.home") + "/clip.m4v",
                TerminalImage.pastedVideoPath("~/clip.m4v"));
    }

    @Test
    public void proseAndNonClipsAreNotDrops() {
        assertNull(TerminalImage.pastedVideoPath(null));
        assertNull(TerminalImage.pastedVideoPath("   "));
        assertNull(TerminalImage.pastedVideoPath("see /tmp/clip.mp4 for details"));
        assertNull(TerminalImage.pastedVideoPath("/tmp/a.mp4\n/tmp/b.mp4"));
        assertNull(TerminalImage.pastedVideoPath("/tmp/photo.png"));
        assertEquals("/tmp/photo.png", TerminalImage.pastedFilePath("/tmp/photo.png"));
    }

    @Test
    public void everyKnownMimeRoundTripsToItsOwnExtension() {
        assertEquals(".png", TerminalImage.extensionForMime("image/png"));
        assertEquals(".jpg", TerminalImage.extensionForMime("image/jpeg"));
        // A clip keeps ITS extension: a .png-named MP4 is neither playable nor probeable.
        assertEquals(".mp4", TerminalImage.extensionForMime("video/mp4"));
        assertEquals(".mov", TerminalImage.extensionForMime("video/quicktime"));
        assertNull(TerminalImage.extensionForMime("application/pdf"));
        assertNull(TerminalImage.extensionForMime(null));
    }
}
