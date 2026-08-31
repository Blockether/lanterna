/*
 * This file is part of lanterna (https://github.com/mabe02/lanterna).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.googlecode.lanterna.terminal.html;

import com.googlecode.lanterna.TerminalSize;

import java.util.Map;
import java.util.Objects;

/**
 * Framework-neutral endpoint protocol for hosting an {@link HtmlTerminal}.
 *
 * <p>The host mounts these operations in its own HTTP stack and keeps authentication, cookies,
 * lifecycle and streaming I/O. Lanterna owns browser pages, form semantics, frame waiting and SSE
 * event encoding so every host speaks the same terminal protocol without embedding an HTTP server.</p>
 */
public final class HtmlTerminalEndpoint implements AutoCloseable {
    /** One changed frame event or an idle keepalive. */
    public record Event(long version, boolean changed, String body) {
        public Event {
            Objects.requireNonNull(body, "body");
        }
    }

    private final HtmlTerminal terminal;

    public HtmlTerminalEndpoint(HtmlTerminal terminal) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
    }

    public String renderPage(String endpointPrefix) {
        return terminal.renderLiveHtml(Objects.requireNonNull(endpointPrefix, "endpointPrefix"));
    }

    public String renderBridge(String bridgeId) {
        if (bridgeId == null || bridgeId.isBlank() || bridgeId.length() > 128) {
            throw new IllegalArgumentException("bridgeId must be non-blank and at most 128 characters");
        }
        return terminal.renderBridgeHtml(bridgeId);
    }

    public void submitInput(Map<String, String> fields) {
        terminal.submitBrowserInput(Objects.requireNonNull(fields, "fields"));
    }

    public TerminalSize resize(Map<String, String> fields) {
        Objects.requireNonNull(fields, "fields");
        return terminal.resizeFromBrowser(
                requiredInteger(fields, "cols"), requiredInteger(fields, "rows"));
    }

    public Event awaitEvent(long afterVersion, long timeoutMillis) {
        HtmlTerminalRenderer.Frame frame = terminal.awaitFrame(afterVersion, timeoutMillis);
        if (frame.version() == afterVersion) {
            return new Event(frame.version(), false, ": keepalive\n\n");
        }
        return new Event(frame.version(), true, frameEvent(frame));
    }

    public boolean isClosed() {
        return terminal.isClosed();
    }

    @Override
    public void close() {
        terminal.close();
    }

    private static int requiredInteger(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer", exception);
        }
    }

    private static String frameEvent(HtmlTerminalRenderer.Frame frame) {
        StringBuilder event = new StringBuilder()
                .append("id: ").append(frame.version()).append('\n')
                .append("event: frame\n");
        String[] lines = HtmlTerminalRenderer.renderFrame(frame).split("\\R", -1);
        for (String line : lines) event.append("data: ").append(line).append('\n');
        return event.append('\n').toString();
    }
}
