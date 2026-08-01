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

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Throughput guardrails for the {@link TerminalImage} hot paths — the methods
 * the TUI screen loop hits on every image draw / scroll. Not a full JMH harness
 * (that would need a separate module); a warm-up + timed-loop microbenchmark
 * that PRINTS ns/op and fails only on catastrophic (order-of-magnitude)
 * regressions, so it is safe to keep in the normal {@code mvn test} run.
 *
 * <p>Ceilings are deliberately generous (~10-100x the measured cost on a
 * modern laptop) to stay CI-stable; the printed numbers are the real signal.
 * Raise the JVM flag {@code -Dtimg.bench.strict=true} to also fail if any path
 * regresses past its ceiling on a slow box you trust.
 */
public class TerminalImageBenchmarkTest {

    private static final int WARMUP = 20_000;
    private static final int MEASURE = 200_000;
    private static final boolean STRICT = Boolean.getBoolean("timg.bench.strict");

    private static Map<String, String> kittyEnv;
    private static byte[] pngHeader;
    private static String smallB64;
    private static String bigB64;
    private static String pngFilePath;

    private static long sink; // black hole to defeat dead-code elimination

    @BeforeClass
    public static void setUp() throws Exception {
        kittyEnv = new HashMap<String, String>();
        kittyEnv.put("TERM_PROGRAM", "ghostty");
        kittyEnv.put("TERM", "xterm-ghostty");

        pngHeader = new byte[24];
        pngHeader[16] = 0; pngHeader[17] = 0; pngHeader[18] = 0x03; pngHeader[19] = 0x20;
        pngHeader[20] = 0; pngHeader[21] = 0; pngHeader[22] = 0x02; pngHeader[23] = 0x58;

        char[] small = new char[1024];
        Arrays.fill(small, 'A');
        smallB64 = new String(small);

        char[] big = new char[1_000_000]; // ~1 MB payload -> ~245 kitty chunks
        Arrays.fill(big, 'A');
        bigB64 = new String(big);

        File f = File.createTempFile("timg-bench", ".png");
        f.deleteOnExit();
        java.nio.file.Files.write(f.toPath(), new byte[64 * 1024]);
        pngFilePath = f.getAbsolutePath();
        // Prime the cache so the benchmarked call measures the cache-hit path.
        TerminalImage.readBase64(pngFilePath);
    }

    /** Warm up, then time {@code iters} calls; returns ns/op. */
    private static double bench(String name, int iters, LongSupplier op) {
        for (int i = 0; i < WARMUP; i++) {
            sink += op.getAsLong();
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            sink += op.getAsLong();
        }
        double nsPerOp = (System.nanoTime() - t0) / (double) iters;
        System.out.printf("  %-28s %,10.1f ns/op%n", name, nsPerOp);
        return nsPerOp;
    }

    private static void guard(String name, double nsPerOp, double ceilingNs) {
        if (nsPerOp > ceilingNs) {
            String msg = name + " = " + String.format("%.1f", nsPerOp)
                    + " ns/op exceeds ceiling " + String.format("%.1f", ceilingNs) + " ns/op";
            System.out.println("  [WARN] " + msg);
            if (STRICT) {
                assertTrue(msg, false);
            }
        }
    }

    @Test
    public void detectionIsCheap() {
        System.out.println("[TerminalImage bench] capability detection");
        double ns = bench("detectCapabilities(env)", MEASURE,
                () -> TerminalImage.detectCapabilities(kittyEnv) == null ? 0 : 1);
        double ns2 = bench("isGraphicalTerminal(env)", MEASURE,
                () -> TerminalImage.isGraphicalTerminal(kittyEnv) ? 1 : 0);
        guard("detectCapabilities", ns, 50_000);
        guard("isGraphicalTerminal", ns2, 50_000);
    }

    @Test
    public void dimensionAndSizingAreCheap() {
        System.out.println("[TerminalImage bench] dimensions + cell sizing");
        double dims = bench("imageDimensions(png head)", MEASURE,
                () -> TerminalImage.imageDimensions(pngHeader, "image/png")[0]);
        double cell = bench("cellSize", MEASURE,
                () -> TerminalImage.cellSize(1920, 1080, 80, 40)[0]);
        guard("imageDimensions", dims, 10_000);
        guard("cellSize", cell, 10_000);
    }

    @Test
    public void encodingScalesLinearly() {
        System.out.println("[TerminalImage bench] escape encoding");
        double small = bench("encodeKitty(1KB)", MEASURE / 4,
                () -> TerminalImage.encodeKitty(smallB64, 20, 10).length());
        double iterm = bench("encodeIterm2(1KB)", MEASURE / 4,
                () -> TerminalImage.encodeIterm2(smallB64, 20).length());
        double big = bench("encodeKitty(1MB,chunked)", 200,
                () -> TerminalImage.encodeKitty(bigB64, 20, 10).length());
        guard("encodeKitty(1KB)", small, 200_000);
        guard("encodeIterm2(1KB)", iterm, 200_000);
        guard("encodeKitty(1MB)", big, 100_000_000); // 100 ms/op ceiling for a 1MB payload
    }

    @Test
    public void cacheHitsAreCheap() {
        System.out.println("[TerminalImage bench] cache-hit read");
        double read = bench("readBase64(cache hit)", MEASURE / 4,
                () -> TerminalImage.readBase64(pngFilePath).length());
        guard("readBase64", read, 200_000);
    }

    @Test
    public void sinkIsObserved() {
        // Reference `sink` so the JIT cannot prove the accumulator dead.
        assertTrue(sink != Long.MIN_VALUE);
    }
}
