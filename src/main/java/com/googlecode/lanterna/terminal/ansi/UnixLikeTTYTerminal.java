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

import java.io.*;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * UnixLikeTerminal extends from ANSITerminal and defines functionality that is common to
 *  {@code UnixTerminal} and {@code CygwinTerminal}, like setting tty modes; echo, cbreak
 *  and minimum characters for reading as well as a shutdown hook to set the tty back to
 *  original state at the end.
 * <p>
 *  If requested, it handles Control-C input to terminate the program, and hooks
 *  into Unix WINCH signal to detect when the user has resized the terminal,
 *  if supported by the JVM.
 *
 * @author Andreas
 * @author Martin
 */
public abstract class UnixLikeTTYTerminal extends UnixLikeTerminal {

    private final File ttyDev;
    private String sttyStatusToRestore;

    /**
     * Direct termios/ioctl control of {@link #ttyDev}, or null when this runtime cannot do it (a
     * pre-Java-22 JDK, an unsupported platform, a native image built without the foreign downcall
     * metadata, or {@code -Dcom.googlecode.lanterna.terminal.UnixTerminal.nativeTTY=false}).
     * <p>
     * Every use is "try native, else stty": the field is cleared the first time a native call
     * fails, so a half-working device can never wedge the terminal.
     */
    private TTYDeviceControl nativeControl;

    /**
     * Creates a UnixTerminal using a specified input stream, output stream and character set, with a custom size
     * querier instead of using the default one. This way you can override size detection (if you want to force the
     * terminal to a fixed size, for example). You also choose how you want ctrl+c key strokes to be handled.
     *
     * @param ttyDev TTY device file that is representing this terminal session, will be used when calling stty to make
     *               it operate on this session
     * @param terminalInput Input stream to read terminal input from
     * @param terminalOutput Output stream to write terminal output to
     * @param terminalCharset Character set to use when converting characters to bytes
     * @param terminalCtrlCBehaviour Special settings on how the terminal will behave, see {@code UnixTerminalMode} for
     *                               more details
     * @throws IOException If there was an I/O error while setting up the terminal
     */
    protected UnixLikeTTYTerminal(
            File ttyDev,
            InputStream terminalInput,
            OutputStream terminalOutput,
            Charset terminalCharset,
            CtrlCBehaviour terminalCtrlCBehaviour) throws IOException {

        super(terminalInput,
                terminalOutput,
                terminalCharset,
                terminalCtrlCBehaviour);

        this.ttyDev = ttyDev;
        this.nativeControl = openNativeControl(ttyDev);

        // Take ownership of the terminal
        realAcquire();
    }

    @Override
    protected void acquire() throws IOException {
        // Hack!
    }

    private void realAcquire() throws IOException {
        super.acquire();
    }

    @Override
    protected void registerTerminalResizeListener(final Runnable onResize) throws IOException {
        try {
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            for(Method m : signalClass.getDeclaredMethods()) {
                if("handle".equals(m.getName())) {
                    Object windowResizeHandler = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Class.forName("sun.misc.SignalHandler")}, (proxy, method, args) -> {
                        if("handle".equals(method.getName())) {
                            onResize.run();
                        }
                        return null;
                    });
                    m.invoke(null, signalClass.getConstructor(String.class).newInstance("WINCH"), windowResizeHandler);
                }
            }
        }
        catch(Throwable ignore) {
            // We're probably running on a non-Sun JVM and there's no way to catch signals without resorting to native
            // code integration
        }
    }

    /**
     * Opens direct (termios/ioctl) control of the TTY, or returns null if this runtime cannot do
     * it. NEVER throws: no reason to lose the terminal because the fast path is unavailable.
     */
    private static TTYDeviceControl openNativeControl(File ttyDev) {
        try {
            if ("false".equals(String.valueOf(System.getProperty(TTYDeviceControl.NATIVE_TTY_PROPERTY, "")).trim().toLowerCase())) {
                return null;
            }
            if (!TTYDeviceControl.isSupported()) {
                return null;
            }
            return TTYDeviceControl.open(ttyDev);
        }
        catch (Throwable ignore) {
            // Unsupported JDK/platform, no /dev/tty, or a native image without the foreign
            // downcall metadata (MissingForeignRegistrationError) — fall back to /bin/stty.
            return null;
        }
    }

    /**
     * @return the native control if it is still usable, else null (and permanently disabled)
     */
    private TTYDeviceControl nativeControl() {
        return nativeControl;
    }

    /** One native call failed; drop to stty for the rest of this terminal's life. */
    private void abandonNativeControl() {
        TTYDeviceControl control = nativeControl;
        nativeControl = null;
        if(control != null) {
            control.close();
        }
    }

    @Override
    protected void saveTerminalSettings() throws IOException {
        TTYDeviceControl control = nativeControl();
        if(control != null) {
            try {
                control.saveSettings();
                return;
            }
            catch(Throwable ignore) {
                abandonNativeControl();
            }
        }
        sttyStatusToRestore = runSTTYCommand("-g").trim();
    }

    @Override
    protected void restoreTerminalSettings() throws IOException {
        TTYDeviceControl control = nativeControl();
        if(control != null) {
            try {
                control.restoreSettings();
                return;
            }
            catch(Throwable ignore) {
                abandonNativeControl();
            }
        }
        if(sttyStatusToRestore != null) {
            runSTTYCommand(sttyStatusToRestore);
        }
    }

    @Override
    protected void keyEchoEnabled(boolean enabled) throws IOException {
        TTYDeviceControl control = nativeControl();
        if(control != null) {
            try {
                control.setEcho(enabled);
                return;
            }
            catch(Throwable ignore) {
                abandonNativeControl();
            }
        }
        runSTTYCommand(enabled ? "echo" : "-echo");
    }

    @Override
    protected void canonicalMode(boolean enabled) throws IOException {
        TTYDeviceControl control = nativeControl();
        if(control != null) {
            try {
                control.setCanonicalMode(enabled);
                return;
            }
            catch(Throwable ignore) {
                abandonNativeControl();
            }
        }
        runSTTYCommand(enabled ? "icanon" : "-icanon");
        if(!enabled) {
            runSTTYCommand("min", "1");
        }
    }

    @Override
    protected void keyStrokeSignalsEnabled(boolean enabled) throws IOException {
        TTYDeviceControl control = nativeControl();
        if(control != null) {
            try {
                control.setInterruptCharacterEnabled(enabled);
                return;
            }
            catch(Throwable ignore) {
                abandonNativeControl();
            }
        }
        if(enabled) {
            runSTTYCommand("intr", "^C");
        }
        else {
            runSTTYCommand("intr", "undef");
        }
    }

    /**
     * Asks the kernel for the window size ({@code ioctl(TIOCGWINSZ)}) when direct TTY control is
     * available. That is one syscall and it is exact; the inherited ANSI implementation instead
     * parks the cursor at 5000,5000, asks the terminal to report it back and parses the reply,
     * which costs a round-trip and silently returns 80x24 when the reply never arrives.
     */
    @Override
    protected TerminalSize findTerminalSize() throws IOException {
        TTYDeviceControl control = nativeControl();
        if(control != null) {
            try {
                TerminalSize size = control.getSize();
                if(size != null) {
                    return size;
                }
            }
            catch(Throwable ignore) {
                abandonNativeControl();
            }
        }
        return super.findTerminalSize();
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        }
        finally {
            abandonNativeControl();
        }
    }

    protected String runSTTYCommand(String... parameters) throws IOException {
        List<String> commandLine = new ArrayList<>(Collections.singletonList(
                getSTTYCommand()));
        commandLine.addAll(Arrays.asList(parameters));
        return exec(commandLine.toArray(new String[0]));
    }

    protected String exec(String... cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (ttyDev != null) {
            pb.redirectInput(ProcessBuilder.Redirect.from(ttyDev));
        }
        Process process = pb.start();
        ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        InputStream stdout = process.getInputStream();
        int readByte = stdout.read();
        while(readByte >= 0) {
            stdoutBuffer.write(readByte);
            readByte = stdout.read();
        }
        ByteArrayInputStream stdoutBufferInputStream = new ByteArrayInputStream(stdoutBuffer.toByteArray());
        BufferedReader reader = new BufferedReader(new InputStreamReader(stdoutBufferInputStream));
        StringBuilder builder = new StringBuilder();
        String line;
        while((line = reader.readLine()) != null) {
            builder.append(line);
        }
        reader.close();
        return builder.toString();
    }

    private String getSTTYCommand() {
        return System.getProperty("com.googlecode.lanterna.terminal.UnixTerminal.sttyCommand",
                    "/bin/stty");
    }
}