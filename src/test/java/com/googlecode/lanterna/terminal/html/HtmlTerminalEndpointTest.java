package com.googlecode.lanterna.terminal.html;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyType;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class HtmlTerminalEndpointTest {
    @Test
    public void ownsTheFrameworkNeutralBrowserProtocol() throws Exception {
        try (HtmlTerminal terminal = terminal();
             HtmlTerminalEndpoint endpoint = new HtmlTerminalEndpoint(terminal)) {
            assertTrue(endpoint.renderPage("/terminal").contains("data-endpoint-prefix=\"/terminal\""));
            assertTrue(endpoint.renderBridge("frame-one").contains("data-bridge-id=\"frame-one\""));

            endpoint.submitInput(Map.of("kind", "key", "key", "Enter"));
            assertEquals(KeyType.Enter, terminal.readInput().getKeyType());
            assertEquals(new TerminalSize(33, 12), endpoint.resize(Map.of("cols", "33", "rows", "12")));

            long before = terminal.snapshot().version();
            terminal.putCharacter('N');
            terminal.flush();
            HtmlTerminalEndpoint.Event changed = endpoint.awaitEvent(before, 1_000);
            assertTrue(changed.changed());
            assertTrue(changed.body().startsWith("id: " + changed.version() + "\nevent: frame\n"));
            assertTrue(changed.body().contains("data: <div class=\"frame\""));

            HtmlTerminalEndpoint.Event idle = endpoint.awaitEvent(changed.version(), 0);
            assertFalse(idle.changed());
            assertEquals(": keepalive\n\n", idle.body());
        }
    }

    @Test
    public void validatesBridgeAndResizeFieldsBeforeTheyReachTheTerminal() throws Exception {
        try (HtmlTerminal terminal = terminal();
             HtmlTerminalEndpoint endpoint = new HtmlTerminalEndpoint(terminal)) {
            assertThrows(IllegalArgumentException.class, () -> endpoint.renderBridge(" "));
            assertThrows(IllegalArgumentException.class, () -> endpoint.resize(Map.of("cols", "wide", "rows", "12")));
            assertThrows(IllegalArgumentException.class, () -> endpoint.resize(Map.of("cols", "12")));
        }
    }

    private static HtmlTerminal terminal() {
        return HtmlTerminal.builder()
                .initialSize(new TerminalSize(10, 4))
                .columnRange(2, 80)
                .rowRange(2, 40)
                .build();
    }
}
