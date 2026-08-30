/*
 * This file is part of lanterna (https://github.com/mabe02/lanterna).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.googlecode.lanterna.terminal.html;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.*;

public class HtmlTerminalTest {
    @Test
    public void servesTokenProtectedPageAndFramesOnLoopback() throws Exception {
        try (HtmlTerminal terminal = terminal(new TerminalSize(10, 4))) {
            assertEquals("127.0.0.1", terminal.getUri().getHost());
            assertTrue(terminal.getPort() > 0);

            Response forbidden = request("GET", withoutToken(terminal, "/"), null);
            assertEquals(403, forbidden.status());

            Response page = request("GET", endpoint(terminal, "/"), null);
            assertEquals(200, page.status());
            assertTrue(page.contentType().startsWith("text/html"));
            assertEquals("no-store", page.headers().get("cache-control"));
            assertEquals("no-referrer", page.headers().get("referrer-policy"));
            assertTrue(page.headers().get("content-security-policy").contains("media-src data:"));
            assertTrue(page.body().contains("\"live\":true"));
            assertFalse(page.body().contains("__LANTERNA_"));

            terminal.putCharacter('Z');
            terminal.flush();
            Response frame = request("GET", endpoint(terminal, "/frame", "after=-1"), null);
            assertEquals(200, frame.status());
            assertTrue(frame.body().contains("\"text\":\"Z\""));

            Response health = request("GET", endpoint(terminal, "/healthz"), null);
            assertEquals(200, health.status());
            assertEquals("ok", health.body());
        }
    }

    @Test
    public void browserTextKeysMouseAndResizeBecomeOrdinaryTerminalEvents() throws Exception {
        try (HtmlTerminal terminal = terminal(new TerminalSize(10, 4))) {
            assertEquals(204, post(terminal, "/input", form(
                    "kind", "text", "text", "Zaż")).status());
            assertEquals(Character.valueOf('Z'), terminal.readInput().getCharacter());
            assertEquals(Character.valueOf('a'), terminal.readInput().getCharacter());
            assertEquals(Character.valueOf('ż'), terminal.readInput().getCharacter());

            assertEquals(204, post(terminal, "/input", form(
                    "kind", "key",
                    "key", "ArrowDown",
                    "ctrl", "true",
                    "alt", "false",
                    "shift", "true")).status());
            KeyStroke arrow = terminal.readInput();
            assertEquals(KeyType.ArrowDown, arrow.getKeyType());
            assertTrue(arrow.isCtrlDown());
            assertFalse(arrow.isAltDown());
            assertTrue(arrow.isShiftDown());

            assertEquals(204, post(terminal, "/input", form(
                    "kind", "key", "key", "Tab", "shift", "true")).status());
            assertEquals(KeyType.ReverseTab, terminal.readInput().getKeyType());

            assertEquals(204, post(terminal, "/input", form(
                    "kind", "mouse",
                    "action", "CLICK_DOWN",
                    "button", "1",
                    "col", "7",
                    "row", "2")).status());
            MouseAction mouse = (MouseAction) terminal.readInput();
            assertEquals(MouseActionType.CLICK_DOWN, mouse.getActionType());
            assertEquals(1, mouse.getButton());
            assertEquals(new TerminalPosition(7, 2), mouse.getPosition());

            assertEquals(204, post(terminal, "/resize", form("cols", "33", "rows", "12")).status());
            assertEquals(new TerminalSize(33, 12), terminal.getTerminalSize());
            assertEquals(204, post(terminal, "/resize", form("cols", "9999", "rows", "0")).status());
            assertEquals(new TerminalSize(80, 2), terminal.getTerminalSize());
        }
    }

    @Test
    public void mediaLifecycleIsVisibleInLiveAndPortableFrames() throws Exception {
        try (HtmlTerminal terminal = terminal(new TerminalSize(10, 4))) {
            HtmlMedia audio = HtmlMedia.builder(
                            HtmlMedia.Kind.AUDIO, "audio/wav", new byte[] {1, 2, 3, 4})
                    .id("audio")
                    .position(new TerminalPosition(1, 1))
                    .size(new TerminalSize(6, 2))
                    .description("Sample")
                    .controls(true)
                    .build();
            terminal.putMedia(audio);
            assertEquals(1, terminal.getMedia().size());
            assertTrue(terminal.renderHtml().contains("data:audio/wav;base64,AQIDBA=="));

            Response frame = request("GET", endpoint(terminal, "/frame", "after=-1"), null);
            assertTrue(frame.body().contains("data:audio/wav;base64,AQIDBA=="));
            assertTrue(terminal.removeMedia("audio"));
            assertFalse(terminal.removeMedia("audio"));
            assertTrue(terminal.getMedia().isEmpty());
            terminal.putMedia(audio);
            terminal.clearMedia();
            assertTrue(terminal.getMedia().isEmpty());
        }
    }

    @Test
    public void mediaLayerReplacementIsAtomicAndRejectsDuplicateIds() throws Exception {
        try (HtmlTerminal terminal = terminal(new TerminalSize(10, 4))) {
            HtmlMedia first = HtmlMedia.builder(HtmlMedia.Kind.IMAGE, "image/png", new byte[] {1})
                    .id("first")
                    .build();
            HtmlMedia second = HtmlMedia.builder(HtmlMedia.Kind.AUDIO, "audio/wav", new byte[] {2})
                    .id("second")
                    .build();

            long before = terminal.snapshot().version();
            terminal.replaceMedia(List.of(first, second));
            assertEquals(List.of(first, second), terminal.getMedia());
            assertEquals(before + 1, terminal.snapshot().version());

            terminal.replaceMedia(List.of(first, second));
            assertEquals(before + 1, terminal.snapshot().version());
            assertThrows(IllegalArgumentException.class, () -> terminal.replaceMedia(List.of(first, first)));
            assertEquals(List.of(first, second), terminal.getMedia());
        }
    }

    @Test
    public void requestBodiesAreBoundedAndUnknownRoutesAreRejected() throws Exception {
        try (HtmlTerminal terminal = terminal(new TerminalSize(10, 4))) {
            String oversized = "x".repeat(65_537);
            assertEquals(413, request("POST", endpoint(terminal, "/input"), oversized).status());
            assertEquals(404, request("GET", endpoint(terminal, "/missing"), null).status());
            assertEquals(404, request("DELETE", endpoint(terminal, "/"), null).status());
        }
    }

    @Test
    public void builderClampsInitialAndBrowserSizesToConfiguredRanges() throws Exception {
        try (HtmlTerminal terminal = HtmlTerminal.builder()
                .initialSize(new TerminalSize(2, 2))
                .columnRange(5, 8)
                .rowRange(3, 6)
                .title("Range")
                .build()) {
            assertEquals(new TerminalSize(5, 3), terminal.getTerminalSize());
            assertEquals("Range", terminal.getTitle());
            assertEquals(204, post(terminal, "/resize", form("cols", "100", "rows", "100")).status());
            assertEquals(new TerminalSize(8, 6), terminal.getTerminalSize());
        }
    }

    @Test
    public void closeIsIdempotentAndQueuesEndOfFile() throws Exception {
        HtmlTerminal terminal = terminal(new TerminalSize(10, 4));
        terminal.close();
        terminal.close();
        assertTrue(terminal.isClosed());
        assertEquals(KeyType.EOF, terminal.readInput().getKeyType());
    }

    private static HtmlTerminal terminal(TerminalSize size) throws IOException {
        return HtmlTerminal.builder()
                .initialSize(size)
                .columnRange(2, 80)
                .rowRange(2, 40)
                .title("HTML test")
                .build();
    }

    private static Response post(HtmlTerminal terminal, String path, String body) throws IOException {
        return request("POST", endpoint(terminal, path), body);
    }

    private static URI endpoint(HtmlTerminal terminal, String path, String... extraQuery) {
        String query = terminal.getUri().getRawQuery();
        if (extraQuery.length > 0 && !extraQuery[0].isEmpty()) query += "&" + extraQuery[0];
        return URI.create("http://127.0.0.1:" + terminal.getPort() + path + "?" + query);
    }

    private static URI withoutToken(HtmlTerminal terminal, String path) {
        return URI.create("http://127.0.0.1:" + terminal.getPort() + path);
    }

    private static String form(String... values) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.length; index += 2) {
            if (index > 0) result.append('&');
            result.append(URLEncoder.encode(values[index], StandardCharsets.UTF_8));
            result.append('=');
            result.append(URLEncoder.encode(values[index + 1], StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    private static Response request(String method, URI uri, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(3_000);
        connection.setRequestMethod(method);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
            connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        connection.getHeaderFields().forEach((name, values) -> {
            if (name != null && values != null && !values.isEmpty()) {
                headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
            }
        });
        String contentType = connection.getContentType() == null ? "" : connection.getContentType();
        connection.disconnect();
        return new Response(status, contentType, responseBody, headers);
    }

    private record Response(int status, String contentType, String body, Map<String, String> headers) {
    }
}
