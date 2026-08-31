package com.googlecode.lanterna.terminal.ansi;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class UnixLikeTTYTerminalTest {
    private static final List<List<String>> COMMANDS = new ArrayList<>();

    @Test
    public void terminalAcquisitionOwnsInteractiveInputFlags() throws Exception {
        String property = TTYDeviceControl.NATIVE_TTY_PROPERTY;
        String previous = System.getProperty(property);
        COMMANDS.clear();
        System.setProperty(property, "false");
        try (RecordingTerminal ignored = new RecordingTerminal()) {
            assertTrue(COMMANDS.contains(List.of("-icanon", "min", "1", "-iexten", "-ixon")));
        }
        finally {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    private static final class RecordingTerminal extends UnixLikeTTYTerminal {
        private RecordingTerminal() throws IOException {
            super(null,
                    new ByteArrayInputStream(new byte[0]),
                    new ByteArrayOutputStream(),
                    StandardCharsets.UTF_8,
                    CtrlCBehaviour.TRAP);
        }

        @Override
        protected void registerTerminalResizeListener(Runnable onResize) {
        }

        @Override
        protected String runSTTYCommand(String... parameters) {
            COMMANDS.add(Arrays.asList(parameters));
            return parameters.length == 1 && "-g".equals(parameters[0]) ? "saved" : "";
        }
    }
}
