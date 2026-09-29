package com.googlecode.lanterna.terminal.ansi;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.terminal.MouseCaptureMode;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ANSITerminalTest {
    @Test
    public void privateModeOwnsBracketedPasteAndSgrMouseLifecycle() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ANSITerminal terminal = new ANSITerminal(
                new ByteArrayInputStream(new byte[0]), output, StandardCharsets.UTF_8) {};
        terminal.setMouseCaptureMode(MouseCaptureMode.CLICK_RELEASE_DRAG_MOVE);

        terminal.enterPrivateMode();
        terminal.exitPrivateMode();

        String control = output.toString(StandardCharsets.UTF_8);
        assertTrue(control.contains("\u001b[?2004h"));
        assertTrue(control.contains("\u001b[?1006h"));
        assertTrue(control.contains("\u001b[?1006l"));
        assertTrue(control.contains("\u001b[?2004l"));
    }

    @Test
    public void extendedKeyReportingIsOffUnlessRequested() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ANSITerminal terminal = terminal(output);

        terminal.enterPrivateMode();
        terminal.exitPrivateMode();

        String control = output.toString(StandardCharsets.UTF_8);
        assertFalse(control.contains("\u001b[>"));
        assertFalse(control.contains("\u001b[<u"));
        assertFalse(control.contains("\u001b[?9001"));
    }

    @Test
    public void privateModeRequestsAndRestoresExtendedKeyReporting() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ANSITerminal terminal = terminal(output);
        terminal.setModifyOtherKeys(true);
        terminal.setKittyKeyboardProtocol(true);
        assertEquals("", output.toString(StandardCharsets.UTF_8));

        terminal.enterPrivateMode();
        String entered = output.toString(StandardCharsets.UTF_8);
        int alternateScreen = entered.indexOf("\u001b[?1049h");
        // kitty keeps one keyboard mode stack per screen, so push on the alternate screen
        assertTrue(alternateScreen >= 0);
        assertTrue(entered.indexOf("\u001b[>4;1m") > alternateScreen);
        assertTrue(entered.indexOf("\u001b[>5u") > alternateScreen);

        output.reset();
        terminal.exitPrivateMode();
        String exited = output.toString(StandardCharsets.UTF_8);
        int mainScreen = exited.indexOf("\u001b[?1049l");
        int pop = exited.indexOf("\u001b[<u");
        int reset = exited.indexOf("\u001b[>4m");
        assertTrue(pop >= 0 && pop < mainScreen);
        assertTrue(reset >= 0 && reset < mainScreen);

        output.reset();
        terminal.enterPrivateMode();
        String reentered = output.toString(StandardCharsets.UTF_8);
        assertTrue(reentered.contains("\u001b[>4;1m"));
        assertTrue(reentered.contains("\u001b[>5u"));
    }

    @Test
    public void extendedKeyReportingTogglesInPrivateMode() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ANSITerminal terminal = terminal(output);
        terminal.enterPrivateMode();

        output.reset();
        terminal.setKittyKeyboardProtocol(true);
        terminal.setModifyOtherKeys(true);
        terminal.setModifyOtherKeys(true);
        terminal.setWin32InputMode(true);
        assertEquals("\u001b[>5u\u001b[>4;1m\u001b[?9001h", output.toString(StandardCharsets.UTF_8));

        output.reset();
        terminal.setKittyKeyboardProtocol(false);
        terminal.setModifyOtherKeys(false);
        terminal.setWin32InputMode(false);
        assertEquals("\u001b[<u\u001b[>4m\u001b[?9001l", output.toString(StandardCharsets.UTF_8));

        output.reset();
        terminal.exitPrivateMode();
        String exited = output.toString(StandardCharsets.UTF_8);
        assertFalse(exited.contains("\u001b[<u"));
        assertFalse(exited.contains("\u001b[>4m"));
        assertFalse(exited.contains("\u001b[?9001l"));
    }

    @Test
    public void leavingWin32InputModeConsumesTheReportsSentBeforeIt() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // Enter and Ctrl released after the key that quit, a key typed ahead, the console host's
        // answer to the cursor position request, and input that arrives after it.
        ANSITerminal terminal = terminal("\u001b[13;28;13;0;0;1_\u001b[17;29;0;0;0;1_x\u001b[5;10Ry", output);
        terminal.setWin32InputMode(true);
        assertEquals("", output.toString(StandardCharsets.UTF_8));

        terminal.enterPrivateMode();
        String entered = output.toString(StandardCharsets.UTF_8);
        assertTrue(entered.indexOf("\u001b[?9001h") > entered.indexOf("\u001b[?1049h"));

        output.reset();
        terminal.exitPrivateMode();
        String exited = output.toString(StandardCharsets.UTF_8);
        int off = exited.indexOf("\u001b[?9001l");
        assertTrue(off >= 0 && off < exited.indexOf("\u001b[?1049l"));
        // the answer to this request arrives after every report sent while the mode was on
        assertTrue(exited.indexOf("\u001b[6n") > off);
        assertEquals(new KeyStroke('x', false, false), terminal.pollInput());
        assertEquals(new KeyStroke('y', false, false), terminal.pollInput());
    }

    private static ANSITerminal terminal(ByteArrayOutputStream output) {
        return terminal("", output);
    }

    private static ANSITerminal terminal(String input, ByteArrayOutputStream output) {
        return new ANSITerminal(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, StandardCharsets.UTF_8) {};
    }
}
