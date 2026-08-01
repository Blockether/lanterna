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
package com.googlecode.lanterna.terminal;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.ansi.CygwinTerminal;
import com.googlecode.lanterna.terminal.ansi.TelnetTerminal;
import com.googlecode.lanterna.terminal.ansi.TelnetTerminalServer;
import com.googlecode.lanterna.terminal.ansi.UnixLikeTTYTerminal;
import com.googlecode.lanterna.terminal.ansi.UnixTerminal;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;

/**
 * This TerminalFactory implementation uses a simple auto-detection mechanism for figuring out which terminal
 * implementation to create based on characteristics of the system the program is running on.
 * <p>
 * This fork has no graphical terminal emulator: the {@code com.googlecode.lanterna.terminal.swing} package
 * (SwingTerminalFrame / AWTTerminalFrame and friends) was removed, so the factory only ever produces
 * text terminals - UnixTerminal, CygwinTerminal, WindowsTerminal or TelnetTerminal. The
 * "prefer a terminal emulator window" switches are kept as no-ops so existing call sites still compile.
 * @author martin
 */
public class DefaultTerminalFactory implements TerminalFactory {
    private static final OutputStream DEFAULT_OUTPUT_STREAM = System.out;
    private static final InputStream DEFAULT_INPUT_STREAM = System.in;
    private static final Charset DEFAULT_CHARSET = Charset.defaultCharset();

    private final OutputStream outputStream;
    private final InputStream inputStream;
    private final Charset charset;

    private TerminalSize initialTerminalSize;
    private int telnetPort;
    private int inputTimeout;
    private String title;
    private MouseCaptureMode mouseCaptureMode;
    private UnixTerminal.CtrlCBehaviour unixTerminalCtrlCBehaviour;

    /**
     * Creates a new DefaultTerminalFactory with all properties set to their defaults
     */
    public DefaultTerminalFactory() {
        this(DEFAULT_OUTPUT_STREAM, DEFAULT_INPUT_STREAM, DEFAULT_CHARSET);
    }

    /**
     * Creates a new DefaultTerminalFactory with I/O and character set options customisable.
     * @param outputStream Output stream to use for text-based Terminal implementations
     * @param inputStream Input stream to use for text-based Terminal implementations
     * @param charset Character set to assume the client is using
     */
    @SuppressWarnings({"SameParameterValue", "WeakerAccess"})
    public DefaultTerminalFactory(OutputStream outputStream, InputStream inputStream, Charset charset) {
        this.outputStream = outputStream;
        this.inputStream = inputStream;
        this.charset = charset;

        this.telnetPort = -1;
        this.inputTimeout = -1;
        this.title = null;
        this.mouseCaptureMode = null;
        this.unixTerminalCtrlCBehaviour = UnixTerminal.CtrlCBehaviour.CTRL_C_KILLS_APPLICATION;
    }

    @Override
    public Terminal createTerminal() throws IOException {
        return createHeadlessTerminal();
    }

    /**
     * Instantiates a text-based Terminal according to the factory configuration. Since this fork ships no
     * graphical terminal emulator this is what {@link #createTerminal()} does as well; the method is kept
     * because callers that specifically wanted to avoid AWT/Swing code paths (Graal native-image) use it.
     * @return Terminal implementation
     * @throws IOException If there was an I/O error with the underlying input/output system
     */
    public Terminal createHeadlessTerminal() throws IOException {
        // if tty but have no tty, but do have a port, then go telnet:
        if( telnetPort > 0 && !hasTerminal()) {
            return createTelnetTerminal();
        }
        if(isOperatingSystemWindows()) {
            return createWindowsTerminal();
        }

        return createUnixTerminal(outputStream, inputStream, charset);
    }

    /**
     * Creates a new TelnetTerminal
     *
     * Note: a telnetPort should have been set with setTelnetPort(),
     * otherwise creation of TelnetTerminal will most likely fail.
     *
     * @return New terminal emulator exposed as a {@link Terminal} interface
     */
    public TelnetTerminal createTelnetTerminal() {
        try {
            System.err.print("Waiting for incoming telnet connection on port "+telnetPort+" ... ");
            System.err.flush();

            TelnetTerminalServer tts = new TelnetTerminalServer(telnetPort);
            TelnetTerminal rawTerminal = tts.acceptConnection();
            tts.close(); // Just for single-shot: free up the port!

            System.err.println("Ok, got it!");

            if(mouseCaptureMode != null) {
                rawTerminal.setMouseCaptureMode(mouseCaptureMode);
            }
            if(inputTimeout >= 0) {
                rawTerminal.getInputDecoder().setTimeoutUnits(inputTimeout);
            }
            return rawTerminal;
        } catch(IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    /**
     * Sets a hint to the TerminalFactory of what size to use when creating the terminal. Only terminals that are
     * created rather than attached to an existing tty can honour this.
     * @param initialTerminalSize Size (in rows and columns) of the newly created terminal
     * @return Reference to itself, so multiple .set-calls can be chained
     */
    public DefaultTerminalFactory setInitialTerminalSize(TerminalSize initialTerminalSize) {
        this.initialTerminalSize = initialTerminalSize;
        return this;
    }

    /**
     * No-op in this fork, which always creates a text-based Terminal.
     * @param forceTextTerminal Ignored
     * @return Reference to itself, so multiple .set-calls can be chained
     */
    public DefaultTerminalFactory setForceTextTerminal(boolean forceTextTerminal) {
        return this;
    }

    /**
     * No-op in this fork: there is no graphical terminal emulator to prefer.
     * @param preferTerminalEmulator Ignored
     * @return Reference to itself, so multiple .set-calls can be chained
     */
    public DefaultTerminalFactory setPreferTerminalEmulator(boolean preferTerminalEmulator) {
        return this;
    }

    /**
     * Sets the default CTRL-C behavior to use for all {@link UnixTerminal} objects created by this factory. You can
     * use this to tell Lanterna to trap CTRL-C instead of exiting the application. Non-UNIX terminals are not affected
     * by this.
     * @param unixTerminalCtrlCBehaviour CTRL-C behavior to use for {@link UnixTerminal}:s
     * @return Reference to itself, so multiple .set-calls can be chained
     */
    public DefaultTerminalFactory setUnixTerminalCtrlCBehaviour(UnixTerminal.CtrlCBehaviour unixTerminalCtrlCBehaviour) {
        this.unixTerminalCtrlCBehaviour = unixTerminalCtrlCBehaviour;
        return this;
    }

    /**
     * Primarily for debugging applications with mouse interactions:
     * If no Console is available (e.g. from within an IDE), then fall
     * back to TelnetTerminal on specified port.
     *
     * @param telnetPort the TCP/IP port on which to eventually wait for a connection.
     *         A value less or equal 0 disables creation of a TelnetTerminal.
     *         Note, that ports less than 1024 typically require system
     *         privileges to listen on.
     * @return Reference to itself, so multiple .set-calls can be chained
     */
    public DefaultTerminalFactory setTelnetPort(int telnetPort) {
        this.telnetPort = telnetPort;
        return this;
    }

    /**
     * Only for StreamBasedTerminals: After seeing e.g. an Escape (but nothing
     *         else yet), wait up to the specified number of time units for more
     *         bytes to make up a complete sequence. This may be necessary on
     *         slow channels, or if some client terminal sends each byte of a
     *         sequence in its own TCP packet.
     *
     * @param inputTimeout how long to wait for possible completions of sequences.
     *         units are of a 1/4 second, so e.g. 12 would wait up to 3 seconds.
     * @return Reference to itself, so multiple .set-calls can be chained
     */
    public DefaultTerminalFactory setInputTimeout(int inputTimeout) {
        this.inputTimeout = inputTimeout;
        return this;
    }

    /**
     * No-op in this fork: there is no terminal emulator window to open.
     * @param autoOpenTerminalFrame Ignored
     * @return Itself
     */
    public DefaultTerminalFactory setAutoOpenTerminalEmulatorWindow(boolean autoOpenTerminalFrame) {
        return this;
    }

    /**
     * Records a title for the terminal. Kept for source compatibility; no window is created by this fork.
     * @param title Title to remember
     * @return Reference to itself, so multiple .set-calls can be chained
     */
    public DefaultTerminalFactory setTerminalEmulatorTitle(String title) {
        this.title = title;
        return this;
    }

    /**
     * Sets the mouse capture mode the terminal should use. Please note that this is an extension which isn't widely
     * supported!
     *
     * @param mouseCaptureMode Capture mode for mouse interactions
     * @return Itself
     */
    public DefaultTerminalFactory setMouseCaptureMode(MouseCaptureMode mouseCaptureMode) {
        this.mouseCaptureMode = mouseCaptureMode;
        return this;
    }

    /**
     * Create a {@link Terminal} and immediately wrap it up in a {@link TerminalScreen}
     * @return New {@link TerminalScreen} created with a terminal from {@link #createTerminal()}
     * @throws IOException In case there was an I/O error
     */
    public TerminalScreen createScreen() throws IOException {
        return new TerminalScreen(createTerminal());
    }

    private Terminal createWindowsTerminal() throws IOException {
        try {
            Class<?> nativeImplementation = Class.forName("com.googlecode.lanterna.terminal.WindowsTerminal");
            Constructor<?> constructor = nativeImplementation.getConstructor(InputStream.class, OutputStream.class, Charset.class, UnixLikeTTYTerminal.CtrlCBehaviour.class);
            return (Terminal)constructor.newInstance(inputStream, outputStream, charset, UnixLikeTTYTerminal.CtrlCBehaviour.CTRL_C_KILLS_APPLICATION);
        }
        catch(Exception ignore) {
            try {
                return createCygwinTerminal(outputStream, inputStream, charset);
            } catch(IOException e) {
                throw new IOException("To start java on Windows, use javaw! (see https://github.com/mabe02/lanterna/issues/335 )", e);
            }
        }
    }

    private Terminal createCygwinTerminal(OutputStream outputStream, InputStream inputStream, Charset charset) throws IOException {
        CygwinTerminal cygTerminal = new CygwinTerminal(inputStream, outputStream, charset);
        if(inputTimeout >= 0) {
            cygTerminal.getInputDecoder().setTimeoutUnits(inputTimeout);
        }
        return cygTerminal;
    }

    private Terminal createUnixTerminal(OutputStream outputStream, InputStream inputStream, Charset charset) throws IOException {
        UnixTerminal unixTerminal;
        try {
            Class<?> nativeImplementation = Class.forName("com.googlecode.lanterna.terminal.NativeGNULinuxTerminal");
            Constructor<?> constructor = nativeImplementation.getConstructor(InputStream.class, OutputStream.class, Charset.class, UnixLikeTTYTerminal.CtrlCBehaviour.class);
            unixTerminal = (UnixTerminal)constructor.newInstance(inputStream, outputStream, charset, unixTerminalCtrlCBehaviour);
        }
        catch(Exception ignore) {
            unixTerminal = new UnixTerminal(inputStream, outputStream, charset, unixTerminalCtrlCBehaviour);
        }
        if(mouseCaptureMode != null) {
            unixTerminal.setMouseCaptureMode(mouseCaptureMode);
        }
        if(inputTimeout >= 0) {
            unixTerminal.getInputDecoder().setTimeoutUnits(inputTimeout);
        }
        return unixTerminal;
    }

    /**
     * Detects whether the running platform is Windows* by looking at the
     * operating system name system property
     */
    private static boolean isOperatingSystemWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    private static boolean hasTerminal() {
        // Prior to Java 22, the test was System.console() != null but now things have changed:
        // https://www.oracle.com/java/technologies/javase/22-relnote-issues.html#JDK-8308591
        // We need to check if the Console.isTerminal() method is available and rely on that if so
        Console console = System.console();
        return console != null && isTerminalCheckJDK22(console);
    }

    private static boolean isTerminalCheckJDK22(Console console) {
        try {
            // Don't want to require Java 22 so we need to check this by reflection
            Method isTerminal = Console.class.getMethod("isTerminal");
            return (Boolean)isTerminal.invoke(console);
        } catch (NoSuchMethodException e) {
            return true;  // This is normal and expected for pre-22 JVM
        } catch (InvocationTargetException | IllegalAccessException e) {
            return true;  // This is unexpected, but return true here too, just in case
        }
    }
}
