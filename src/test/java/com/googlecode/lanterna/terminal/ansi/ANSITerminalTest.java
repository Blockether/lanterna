package com.googlecode.lanterna.terminal.ansi;

import com.googlecode.lanterna.terminal.MouseCaptureMode;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

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
}
