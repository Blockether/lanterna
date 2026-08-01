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
package com.googlecode.lanterna.terminal.ansi;

import com.googlecode.lanterna.TerminalSize;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * Java 22+ (multi-release) implementation of {@link TTYDeviceControl}: termios and
 * {@code TIOCGWINSZ} through the {@code java.lang.foreign} FFM API (JEP 454), so lanterna drives
 * the TTY with plain libc calls instead of forking {@code /bin/stty} and instead of the ANSI
 * "park the cursor and ask where it landed" size probe.
 * <p>
 * Only two platforms are modelled — macOS/BSD and Linux — because those are the only ones where
 * {@code UnixTerminal} is used. The struct offsets and flag values below are the ABI, not
 * guesses: they are what {@code <termios.h>} lays out for each platform. Anything else reports
 * {@link #isSupported()} false and the caller keeps using stty.
 * <p>
 * NOTE for GraalVM native image: the downcall descriptors used here must be registered at build
 * time (reachability metadata {@code foreign.downcalls}, or {@code RuntimeForeignAccess}).
 * Without it the runtime raises {@code MissingForeignRegistrationError} — which this class turns
 * into a plain "unsupported", so the terminal still comes up on stty.
 */
public class TTYDeviceControl {

    /**
     * System property to force the stty implementation even on a JDK that could
     * do the native calls: {@code -Dcom.googlecode.lanterna.terminal.UnixTerminal.nativeTTY=false}.
     */
    public static final String NATIVE_TTY_PROPERTY = "com.googlecode.lanterna.terminal.UnixTerminal.nativeTTY";

    // ── platform ────────────────────────────────────────────────────────────────────────────
    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final boolean BSD = OS.contains("mac") || OS.contains("darwin") || OS.contains("bsd");
    private static final boolean LINUX = OS.contains("linux");

    // struct termios — BSD/macOS: 4 x tcflag_t(unsigned long) then cc_t c_cc[NCCS=20]
    //                  Linux:     4 x tcflag_t(unsigned int) + cc_t c_line then cc_t c_cc[NCCS=32]
    private static final int TERMIOS_BYTES = 128;              // both are <= 72; over-allocate
    private static final int LFLAG_OFFSET = BSD ? 24 : 12;
    private static final boolean LFLAG_IS_LONG = BSD;
    private static final int CC_OFFSET = BSD ? 32 : 17;
    private static final int V_INTR = BSD ? 8 : 0;
    private static final int V_MIN = BSD ? 16 : 6;
    private static final int V_TIME = BSD ? 17 : 5;

    private static final long ECHO = 0x00000008L;              // same value on both
    private static final long ICANON = BSD ? 0x00000100L : 0x00000002L;

    private static final byte VDISABLE = (byte) 0xff;          // _POSIX_VDISABLE
    private static final byte CTRL_C = 3;

    private static final int TCSANOW = 0;
    private static final long TIOCGWINSZ = BSD ? 0x40087468L : 0x5413L;
    private static final int O_RDWR = 2;
    private static final int O_NOCTTY = BSD ? 0x20000 : 0x100;

    // ── libc downcalls ──────────────────────────────────────────────────────────────────────
    private static final MethodHandle OPEN;
    private static final MethodHandle CLOSE;
    private static final MethodHandle TCGETATTR;
    private static final MethodHandle TCSETATTR;
    private static final MethodHandle IOCTL;
    private static final boolean SUPPORTED;

    static {
        MethodHandle open = null;
        MethodHandle close = null;
        MethodHandle tcgetattr = null;
        MethodHandle tcsetattr = null;
        MethodHandle ioctl = null;
        boolean supported = false;
        if (BSD || LINUX) {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup libc = linker.defaultLookup();
                open = linker.downcallHandle(
                        libc.find("open").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                close = linker.downcallHandle(
                        libc.find("close").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                tcgetattr = linker.downcallHandle(
                        libc.find("tcgetattr").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                tcsetattr = linker.downcallHandle(
                        libc.find("tcsetattr").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                // ioctl(int, unsigned long, ...) is variadic: on Apple silicon variadic arguments
                // go on the stack, so the third argument MUST be declared variadic or the kernel
                // reads garbage instead of the winsize pointer.
                ioctl = linker.downcallHandle(
                        libc.find("ioctl").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
                        Linker.Option.firstVariadicArg(2));
                supported = true;
            }
            catch (Throwable ignore) {
                // No FFM linker for this platform, a native image without foreign metadata, a
                // security manager, ... — stay unsupported and let the caller fork stty.
                supported = false;
            }
        }
        OPEN = open;
        CLOSE = close;
        TCGETATTR = tcgetattr;
        TCSETATTR = tcsetattr;
        IOCTL = ioctl;
        SUPPORTED = supported;
    }

    private final Arena arena;
    private final MemorySegment current;
    private final MemorySegment saved;
    private final MemorySegment winsize;
    private int fd;
    private boolean hasSaved;

    private TTYDeviceControl(Arena arena, int fd) {
        this.arena = arena;
        this.fd = fd;
        this.current = arena.allocate(TERMIOS_BYTES, 8);
        this.saved = arena.allocate(TERMIOS_BYTES, 8);
        this.winsize = arena.allocate(8, 2);
    }

    /**
     * @return true when this runtime can drive the TTY through native calls
     */
    public static boolean isSupported() {
        return SUPPORTED;
    }

    /**
     * Opens the given TTY device for direct control.
     *
     * @param ttyDev the TTY device file, or null for {@code /dev/tty}
     * @return an open control handle
     * @throws IOException if the platform is unsupported or the device cannot be opened
     */
    public static TTYDeviceControl open(File ttyDev) throws IOException {
        if (!SUPPORTED) {
            throw new IOException("Native TTY control is not supported on this platform/runtime");
        }
        String path = ttyDev != null ? ttyDev.getAbsolutePath() : "/dev/tty";
        Arena arena = Arena.ofShared();
        try {
            MemorySegment cPath = arena.allocateFrom(path);
            int fd = (int) OPEN.invokeExact(cPath, O_RDWR | O_NOCTTY);
            if (fd < 0) {
                throw new IOException("open(" + path + ") failed");
            }
            TTYDeviceControl control = new TTYDeviceControl(arena, fd);
            // Prove it really is a terminal before anyone relies on it.
            if (control.tcgetattr(control.current) != 0) {
                control.close();
                throw new IOException(path + " is not a terminal");
            }
            return control;
        }
        catch (IOException e) {
            arena.close();
            throw e;
        }
        catch (Throwable t) {
            arena.close();
            throw new IOException("Native TTY control failed: " + t, t);
        }
    }

    /** Remembers the current termios state so {@link #restoreSettings()} can put it back. */
    public synchronized void saveSettings() throws IOException {
        checkOpen();
        if (tcgetattr(saved) != 0) {
            throw new IOException("tcgetattr failed");
        }
        hasSaved = true;
    }

    /** Restores the termios state captured by {@link #saveSettings()}; a no-op if there is none. */
    public synchronized void restoreSettings() throws IOException {
        if (!hasSaved) {
            return;
        }
        checkOpen();
        if (tcsetattr(saved) != 0) {
            throw new IOException("tcsetattr failed");
        }
    }

    /** Turns terminal echo (termios {@code ECHO}) on or off. */
    public synchronized void setEcho(boolean enabled) throws IOException {
        updateLocalFlags(ECHO, enabled, false);
    }

    /**
     * Turns canonical (line) mode (termios {@code ICANON}) on or off. Switching it off also sets
     * {@code VMIN=1}/{@code VTIME=0}, mirroring {@code stty -icanon min 1}.
     */
    public synchronized void setCanonicalMode(boolean enabled) throws IOException {
        updateLocalFlags(ICANON, enabled, !enabled);
    }

    /**
     * Enables or disables the interrupt character, mirroring {@code stty intr ^C} /
     * {@code stty intr undef}: it rewrites {@code c_cc[VINTR]} only, so job control (^Z) is
     * untouched.
     */
    public synchronized void setInterruptCharacterEnabled(boolean enabled) throws IOException {
        checkOpen();
        if (tcgetattr(current) != 0) {
            throw new IOException("tcgetattr failed");
        }
        current.set(ValueLayout.JAVA_BYTE, CC_OFFSET + V_INTR, enabled ? CTRL_C : VDISABLE);
        if (tcsetattr(current) != 0) {
            throw new IOException("tcsetattr failed");
        }
    }

    /**
     * @return the terminal size straight from the kernel ({@code ioctl(TIOCGWINSZ)}), or null when
     * the kernel reports no size (typical for a pipe or a detached session)
     */
    public synchronized TerminalSize getSize() throws IOException {
        checkOpen();
        try {
            winsize.fill((byte) 0);
            int result = (int) IOCTL.invokeExact(fd, TIOCGWINSZ, winsize);
            if (result != 0) {
                throw new IOException("ioctl(TIOCGWINSZ) failed");
            }
        }
        catch (IOException e) {
            throw e;
        }
        catch (Throwable t) {
            throw new IOException("ioctl(TIOCGWINSZ) failed: " + t, t);
        }
        int rows = Short.toUnsignedInt(winsize.get(ValueLayout.JAVA_SHORT, 0));
        int columns = Short.toUnsignedInt(winsize.get(ValueLayout.JAVA_SHORT, 2));
        if (rows <= 0 || columns <= 0) {
            return null;
        }
        return new TerminalSize(columns, rows);
    }

    /** Closes the device; idempotent. Does NOT restore terminal settings. */
    public synchronized void close() {
        if (fd < 0) {
            return;
        }
        int toClose = fd;
        fd = -1;
        try {
            int ignored = (int) CLOSE.invokeExact(toClose);
        }
        catch (Throwable ignore) {
            // Nothing sensible to do while tearing down.
        }
        finally {
            arena.close();
        }
    }

    private void updateLocalFlags(long mask, boolean set, boolean rawReadTimings) throws IOException {
        checkOpen();
        if (tcgetattr(current) != 0) {
            throw new IOException("tcgetattr failed");
        }
        if (LFLAG_IS_LONG) {
            long flags = current.get(ValueLayout.JAVA_LONG, LFLAG_OFFSET);
            flags = set ? (flags | mask) : (flags & ~mask);
            current.set(ValueLayout.JAVA_LONG, LFLAG_OFFSET, flags);
        }
        else {
            int flags = current.get(ValueLayout.JAVA_INT, LFLAG_OFFSET);
            flags = set ? (int) (flags | mask) : (int) (flags & ~mask);
            current.set(ValueLayout.JAVA_INT, LFLAG_OFFSET, flags);
        }
        if (rawReadTimings) {
            current.set(ValueLayout.JAVA_BYTE, CC_OFFSET + V_MIN, (byte) 1);
            current.set(ValueLayout.JAVA_BYTE, CC_OFFSET + V_TIME, (byte) 0);
        }
        if (tcsetattr(current) != 0) {
            throw new IOException("tcsetattr failed");
        }
    }

    private int tcgetattr(MemorySegment target) throws IOException {
        try {
            return (int) TCGETATTR.invokeExact(fd, target);
        }
        catch (Throwable t) {
            throw new IOException("tcgetattr failed: " + t, t);
        }
    }

    private int tcsetattr(MemorySegment source) throws IOException {
        try {
            return (int) TCSETATTR.invokeExact(fd, TCSANOW, source);
        }
        catch (Throwable t) {
            throw new IOException("tcsetattr failed: " + t, t);
        }
    }

    private void checkOpen() throws IOException {
        if (fd < 0) {
            throw new IOException("TTY device is closed");
        }
    }
}
