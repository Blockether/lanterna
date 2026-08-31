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

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class HtmlTerminalTest {
    @Test
    public void rendersSsrDocumentsAndFramesWithoutOwningHttpTransport() throws Exception {
        try (HtmlTerminal terminal = terminal(new TerminalSize(10, 4))) {
            terminal.putCharacter('Z');
            terminal.flush();

            String page = terminal.renderLiveHtml("/terminal");
            assertTrue(page.contains("data-endpoint-prefix=\"/terminal\""));
            assertTrue(page.contains("data-live=\"true\""));
            assertTrue(page.contains("data-transport=\"http\""));
            assertTrue(page.contains("data-resizable=\"true\""));
            assertTrue(page.contains(">Z</span>"));
            assertFalse(page.contains("application/json"));
            assertFalse(page.contains("__LANTERNA_"));

            String bridge = terminal.renderBridgeHtml("companion-frame");
            assertTrue(bridge.contains("data-bridge-id=\"companion-frame\""));
            assertTrue(bridge.contains("data-transport=\"parent\""));
            assertTrue(bridge.contains(">Z</span>"));

            String frame = terminal.renderFrameHtml();
            assertTrue(frame.startsWith("<div class=\"frame\""));
            assertTrue(frame.contains(">Z</span>"));
        }
    }

    @Test
    public void browserTextKeysMouseAndResizeBecomeOrdinaryTerminalEvents() throws Exception {
        try (HtmlTerminal terminal = terminal(new TerminalSize(10, 4))) {
            terminal.submitBrowserInput(Map.of("kind", "text", "text", "Zaż"));
            assertEquals(Character.valueOf('Z'), terminal.readInput().getCharacter());
            assertEquals(Character.valueOf('a'), terminal.readInput().getCharacter());
            assertEquals(Character.valueOf('ż'), terminal.readInput().getCharacter());

            terminal.submitBrowserInput(Map.of(
                    "kind", "key",
                    "key", "ArrowDown",
                    "ctrl", "true",
                    "alt", "false",
                    "shift", "true"));
            KeyStroke arrow = terminal.readInput();
            assertEquals(KeyType.ArrowDown, arrow.getKeyType());
            assertTrue(arrow.isCtrlDown());
            assertFalse(arrow.isAltDown());
            assertTrue(arrow.isShiftDown());

            terminal.submitBrowserInput(Map.of("kind", "key", "key", "Tab", "shift", "true"));
            assertEquals(KeyType.ReverseTab, terminal.readInput().getKeyType());

            terminal.submitBrowserInput(Map.of(
                    "kind", "mouse",
                    "action", "CLICK_DOWN",
                    "button", "1",
                    "col", "7",
                    "row", "2"));
            MouseAction mouse = (MouseAction) terminal.readInput();
            assertEquals(MouseActionType.CLICK_DOWN, mouse.getActionType());
            assertEquals(1, mouse.getButton());
            assertEquals(new TerminalPosition(7, 2), mouse.getPosition());

            assertEquals(new TerminalSize(33, 12), terminal.resizeFromBrowser(33, 12));
            assertEquals(new TerminalSize(80, 2), terminal.resizeFromBrowser(9999, 0));
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
            assertTrue(terminal.renderFrameHtml().contains("data:audio/wav;base64,AQIDBA=="));
            assertTrue(terminal.removeMedia("audio"));
            assertFalse(terminal.removeMedia("audio"));
            assertTrue(terminal.getMedia().isEmpty());
            terminal.putMedia(audio);
            terminal.clearMedia();
            assertTrue(terminal.getMedia().isEmpty());
        }
    }

    @Test
    public void mediaLayerReplacementIsAtomicAndChangedMediaChangesMarkup() throws Exception {
        try (HtmlTerminal terminal = terminal(new TerminalSize(10, 4))) {
            HtmlMedia first = HtmlMedia.builder(HtmlMedia.Kind.VIDEO, "video/mp4", new byte[] {1})
                    .id("media")
                    .build();
            HtmlMedia changed = HtmlMedia.builder(HtmlMedia.Kind.VIDEO, "video/mp4", new byte[] {2})
                    .id("media")
                    .build();

            long before = terminal.snapshot().version();
            terminal.replaceMedia(List.of(first));
            assertEquals(List.of(first), terminal.getMedia());
            assertEquals(before + 1, terminal.snapshot().version());
            String firstMarkup = terminal.renderFrameHtml();

            terminal.replaceMedia(List.of(first));
            assertEquals(before + 1, terminal.snapshot().version());
            terminal.replaceMedia(List.of(changed));
            assertEquals(before + 2, terminal.snapshot().version());
            assertNotEquals(firstMarkup, terminal.renderFrameHtml());
            assertTrue(terminal.renderFrameHtml().contains("data:video/mp4;base64,Ag=="));
            assertThrows(IllegalArgumentException.class, () -> terminal.replaceMedia(List.of(first, first)));
            assertEquals(List.of(changed), terminal.getMedia());
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
            assertEquals(new TerminalSize(8, 6), terminal.resizeFromBrowser(100, 100));
        }
    }

    @Test
    public void fixedBrowserViewRejectsResizeRequests() throws Exception {
        TerminalSize fixedSize = new TerminalSize(7, 2);
        try (HtmlTerminal terminal = HtmlTerminal.builder()
                .initialSize(fixedSize)
                .columnRange(7, 7)
                .rowRange(2, 2)
                .browserResize(false)
                .build()) {
            assertTrue(terminal.renderLiveHtml("/terminal").contains("data-resizable=\"false\""));
            assertThrows(IllegalStateException.class, () -> terminal.resizeFromBrowser(50, 20));
            assertEquals(fixedSize, terminal.getTerminalSize());
        }
    }

    @Test
    public void hostTransportUsesSsrInputResizeAndChangePrimitives() throws Exception {
        try (HtmlTerminal terminal = terminal(new TerminalSize(10, 4))) {
            long before = terminal.snapshot().version();
            terminal.putCharacter('N');
            terminal.flush();
            HtmlTerminalRenderer.Frame frame = terminal.awaitFrame(before, 1_000);
            assertTrue(frame.version() > before);
            assertTrue(HtmlTerminalRenderer.renderFrame(frame).contains(">N</span>"));

            terminal.submitBrowserInput(Map.of("kind", "key", "key", "Enter"));
            assertEquals(KeyType.Enter, terminal.readInput().getKeyType());
            assertEquals(new TerminalSize(33, 12), terminal.resizeFromBrowser(33, 12));
        }
    }

    @Test
    public void containsNoHttpServerOrLegacyUrlApi() {
        List<String> removedMethods = List.of(
                "getUrl", "getUri", "getPort", "hasEmbeddedServer", "embeddedServer", "port");
        for (java.lang.reflect.Method method : HtmlTerminal.class.getMethods()) {
            assertFalse(method.getName(), removedMethods.contains(method.getName()));
        }
        for (java.lang.reflect.Method method : HtmlTerminal.Builder.class.getMethods()) {
            assertFalse(method.getName(), removedMethods.contains(method.getName()));
        }
        for (java.lang.reflect.Method method : HtmlTerminalView.class.getMethods()) {
            assertFalse(method.getName(), List.of("serve", "getUrl").contains(method.getName()));
        }
        for (java.lang.reflect.Field field : HtmlTerminal.class.getDeclaredFields()) {
            assertFalse(field.getType().getName(), field.getType().getName().startsWith("com.sun.net.httpserver"));
        }
    }

    @Test
    public void closeIsIdempotentQueuesEndOfFileAndWakesFrameWaiters() throws Exception {
        HtmlTerminal terminal = terminal(new TerminalSize(10, 4));
        long version = terminal.snapshot().version();
        Thread waiter = Thread.ofVirtual().start(() -> terminal.awaitFrame(version, 30_000));
        terminal.close();
        terminal.close();
        waiter.join(1_000);
        assertFalse(waiter.isAlive());
        assertTrue(terminal.isClosed());
        assertEquals(KeyType.EOF, terminal.readInput().getKeyType());
    }

    private static HtmlTerminal terminal(TerminalSize size) {
        return HtmlTerminal.builder()
                .initialSize(size)
                .columnRange(2, 80)
                .rowRange(2, 40)
                .title("HTML test")
                .build();
    }
}
