/*
 * This file is part of lanterna (https://github.com/mabe02/lanterna).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.googlecode.lanterna.terminal.html;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class HtmlTerminalRendererTest {
    @Test
    public void snapshotPreservesEveryGraphicRenditionAndResolvesReverseColors() {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(4, 2));
        terminal.setForegroundColor(new TextColor.RGB(1, 2, 3));
        terminal.setBackgroundColor(new TextColor.RGB(4, 5, 6));
        for (SGR sgr : SGR.values()) {
            terminal.enableSGR(sgr);
        }
        terminal.putCharacter('A');

        HtmlTerminalRenderer.Frame frame = HtmlTerminalRenderer.snapshot(terminal, 7);
        assertEquals(7, frame.version());
        assertEquals(4, frame.columns());
        assertEquals(2, frame.rows());
        assertEquals(1, frame.runs().size());

        HtmlTerminalRenderer.Run run = frame.runs().get(0);
        assertEquals(0, run.x());
        assertEquals(0, run.y());
        assertEquals(1, run.width());
        assertEquals("A", run.text());
        HtmlTerminalRenderer.Style style = run.style();
        assertEquals("#040506", style.foreground());
        assertEquals("#010203", style.background());
        assertTrue(style.bold());
        assertTrue(style.italic());
        assertTrue(style.underline());
        assertTrue(style.strike());
        assertTrue(style.blink());
        assertTrue(style.bordered());
        assertTrue(style.fraktur());
        assertTrue(style.circled());
    }

    @Test
    public void snapshotUsesRgbAnsiIndexedAndConfiguredDefaultColors() {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(4, 1));
        terminal.setForegroundColor(TextColor.ANSI.DEFAULT);
        terminal.setBackgroundColor(TextColor.ANSI.DEFAULT);
        terminal.putCharacter('D');
        terminal.setForegroundColor(TextColor.ANSI.RED_BRIGHT);
        terminal.setBackgroundColor(new TextColor.Indexed(202));
        terminal.putCharacter('I');

        TextColor foreground = new TextColor.RGB(9, 10, 11);
        TextColor background = new TextColor.RGB(12, 13, 14);
        HtmlTerminalRenderer.Frame frame = HtmlTerminalRenderer.snapshot(
                terminal, 0, foreground, background, List.of());

        assertEquals("#090a0b", frame.defaultForeground());
        assertEquals("#0c0d0e", frame.defaultBackground());
        assertEquals(2, frame.runs().size());
        assertEquals("#090a0b", frame.runs().get(0).style().foreground());
        assertEquals("#0c0d0e", frame.runs().get(0).style().background());
        assertEquals("#ff5555", frame.runs().get(1).style().foreground());
        TextColor.Indexed indexed = new TextColor.Indexed(202);
        assertEquals(String.format("#%02x%02x%02x", indexed.getRed(), indexed.getGreen(), indexed.getBlue()),
                frame.runs().get(1).style().background());
    }

    @Test
    public void wideCharactersOccupyTwoCellsWithoutDuplicatingTheGlyph() {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(5, 1));
        terminal.putCharacter('界');
        terminal.putCharacter('B');

        HtmlTerminalRenderer.Frame frame = HtmlTerminalRenderer.snapshot(terminal, 0);
        assertEquals(2, frame.runs().size());
        assertEquals(new HtmlTerminalRenderer.Run(
                        0, 0, 2, "界", frame.runs().get(0).style()),
                frame.runs().get(0));
        assertEquals(2, frame.runs().get(1).x());
        assertEquals(1, frame.runs().get(1).width());
        assertEquals("B", frame.runs().get(1).text());
    }

    @Test
    public void styledBlankCellsAreMergedButUnstyledBlankCellsAreOmitted() {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(8, 1));
        terminal.setCursorPosition(2, 0);
        terminal.setBackgroundColor(TextColor.ANSI.BLUE);
        terminal.putCharacter(' ');
        terminal.putCharacter(' ');
        terminal.putCharacter('X');

        HtmlTerminalRenderer.Frame frame = HtmlTerminalRenderer.snapshot(terminal, 0);
        assertEquals(1, frame.runs().size());
        assertEquals(2, frame.runs().get(0).x());
        assertEquals(3, frame.runs().get(0).width());
        assertEquals("  X", frame.runs().get(0).text());
    }

    @Test
    public void documentsAndFramesAreServerRenderedWithoutClientFrameConstruction() {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(12, 6));
        terminal.putCharacter('<');
        terminal.setCursorVisible(true);
        terminal.setCursorPosition(3, 2);
        List<HtmlMedia> media = List.of(
                media(HtmlMedia.Kind.IMAGE, "image/png", "image", 0),
                media(HtmlMedia.Kind.VIDEO, "video/mp4", "video", 1),
                media(HtmlMedia.Kind.AUDIO, "audio/mpeg", "</script>&audio", 2));
        HtmlTerminalRenderer.Frame frame = HtmlTerminalRenderer.snapshot(
                terminal,
                4,
                HtmlTerminalRenderer.DEFAULT_FOREGROUND,
                HtmlTerminalRenderer.DEFAULT_BACKGROUND,
                media);

        String fragment = HtmlTerminalRenderer.renderFrame(frame);
        assertTrue(fragment.startsWith("<div class=\"frame\""));
        assertTrue(fragment.contains("&lt;"));
        assertTrue(fragment.contains("<img class=\"media\""));
        assertTrue(fragment.contains("<video class=\"media\""));
        assertTrue(fragment.contains("<audio class=\"media\""));
        assertTrue(fragment.contains("data:image/png;base64,"));
        assertTrue(fragment.contains("data:video/mp4;base64,"));
        assertTrue(fragment.contains("data:audio/mpeg;base64,"));
        assertFalse(fragment.contains("</script>&audio"));

        String html = HtmlTerminalRenderer.renderDocument(frame, "<review & verify>");
        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("&lt;review &amp; verify&gt;"));
        assertTrue(html.contains(fragment));
        assertTrue(html.contains("data-lanterna-terminal=\"true\""));
        assertTrue(html.contains("data-transport=\"static\""));
        assertTrue(html.contains("data-live=\"false\""));
        assertFalse(html.contains("application/json"));
        assertFalse(html.contains("response.json()"));
        assertFalse(html.contains("document.createElement('span')"));
        assertFalse(html.contains("__LANTERNA_"));
    }

    @Test
    public void liveDocumentStreamsServerRenderedFragmentsFromConfiguredEndpoints() {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(24, 8));
        terminal.putCharacter('S');
        HtmlTerminalRenderer.Frame frame = HtmlTerminalRenderer.snapshot(terminal, 7);

        String html = HtmlTerminalRenderer.renderLiveDocument(
                frame, "Stream", "/tui", "", 20, 400, 8, 200, true);

        assertTrue(html.contains("data-endpoint-prefix=\"/tui\""));
        assertTrue(html.contains("const eventUrl = endpoint('/events')"));
        assertTrue(html.contains("after=${frame.dataset.version}"));
        assertTrue(html.contains("post('/input'"));
        assertTrue(html.contains("post('/resize'"));
        assertTrue(html.contains("template.innerHTML = html"));
        // Regression, issue b30f87ac-f20e-4d7f-9fd2-416788d10527: two differently sized
        // viewers reasserted their dimensions after every frame and created a resize storm.
        assertFalse(html.contains("apply(next);\n      resizeToViewport();"));
        assertTrue(html.contains("stream.addEventListener('open', () => {\n      requestResize();"));
        assertTrue(html.contains(
                "if (current && current.outerHTML === replacement.outerHTML) replacement.replaceWith(current);"));
        assertFalse(html.contains("/frame"));
    }

    @Test
    public void parentDocumentUsesTheSameInputAndFrameLogicWithoutOwningHttp() {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(24, 8));
        terminal.putCharacter('B');
        HtmlTerminalRenderer.Frame frame = HtmlTerminalRenderer.snapshot(terminal, 7);

        String html = HtmlTerminalRenderer.renderBridgeDocument(
                frame, "Bridge", "phone-review", 20, 400, 8, 200, true);

        assertTrue(html.contains("data-lanterna-terminal=\"true\""));
        assertTrue(html.contains("data-transport=\"parent\""));
        assertTrue(html.contains("data-bridge-id=\"phone-review\""));
        assertTrue(html.contains("window.parent.postMessage"));
        assertTrue(html.contains("event.source !== window.parent"));
        assertTrue(html.contains("lanterna.terminal.ready"));
        assertTrue(html.contains("lanterna.terminal.post"));
        assertTrue(html.contains("lanterna.terminal.frame"));
        assertTrue(html.contains("lanterna.terminal.resync"));
        assertTrue(html.contains("post('/input'"));
        assertTrue(html.contains("post('/resize'"));
        assertTrue(html.contains("try { terminal.setPointerCapture(event.pointerId); } catch (_) {}"));
        assertTrue(html.contains("touch-action: manipulation"));
        assertTrue(html.contains("if (transport === 'parent')"));
        assertTrue(html.contains("if (transport === 'http')"));
        assertTrue(html.contains("data-endpoint-prefix=\"\""));
    }

    @Test
    public void templateMarkersInsideTerminalContentRemainOrdinaryData() {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(24, 1));
        for (char character : "__LANTERNA_TITLE__".toCharArray()) {
            terminal.putCharacter(character);
        }
        HtmlTerminalRenderer.Frame frame = HtmlTerminalRenderer.snapshot(terminal, 0);

        String html = HtmlTerminalRenderer.renderDocument(frame, "__LANTERNA_BODY__");

        assertTrue(html.contains("<title>__LANTERNA_BODY__</title>"));
        assertTrue(html.contains("__LANTERNA_TITLE__"));
        assertEquals(1, occurrences(html, "<div class=\"frame\""));
        assertEquals(1, occurrences(html, "<!doctype html>"));
    }

    @Test
    public void browserGeometryUsesResolvedGridTracksForResizeAndPointerMath() {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(24, 8));
        String html = HtmlTerminalRenderer.renderDocument(
                HtmlTerminalRenderer.snapshot(terminal, 0), "Resolved tracks");

        assertTrue(html.contains(
                "cellWidth = terminal.getBoundingClientRect().width / Math.max(1, columns);"));
        assertTrue(html.contains("const columnWidth = box.width / Math.max(1, columns);"));
        assertTrue(html.contains("const lineHeight = box.height / Math.max(1, rows);"));
    }

    @Test
    public void pathFactoriesPreferBrowserCompatibleMediaTypes() throws Exception {
        Path wav = Files.createTempFile("lanterna-html-", ".wav");
        try {
            Files.write(wav, new byte[] {1, 2, 3});
            assertEquals("audio/wav", HtmlMedia.audio(wav).build().getMimeType());
        }
        finally {
            Files.deleteIfExists(wav);
        }
    }

    @Test
    public void mediaOwnsItsBytesAndValidatesItsKind() {
        byte[] source = "owned".getBytes(StandardCharsets.UTF_8);
        HtmlMedia media = HtmlMedia.builder(HtmlMedia.Kind.IMAGE, "image/png", source)
                .id("preview")
                .position(new TerminalPosition(2, 3))
                .size(new TerminalSize(4, 5))
                .description("Preview")
                .build();
        source[0] = 'X';
        byte[] returned = media.getData();
        returned[0] = 'Y';

        assertArrayEquals("owned".getBytes(StandardCharsets.UTF_8), media.getData());
        assertEquals("preview", media.getId());
        assertEquals(new TerminalPosition(2, 3), media.getPosition());
        assertEquals(new TerminalSize(4, 5), media.getSize());
        assertThrows(IllegalArgumentException.class,
                () -> HtmlMedia.builder(HtmlMedia.Kind.AUDIO, "image/png", new byte[1]));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(needle, index)) >= 0; index += needle.length()) {
            count++;
        }
        return count;
    }

    private static HtmlMedia media(HtmlMedia.Kind kind, String mime, String description, int row) {
        return HtmlMedia.builder(kind, mime, new byte[] {(byte) (row + 1), 2, 3})
                .id(kind.name().toLowerCase())
                .position(new TerminalPosition(1, row))
                .size(new TerminalSize(3, 2))
                .description(description)
                .controls(kind != HtmlMedia.Kind.IMAGE)
                .autoplay(kind == HtmlMedia.Kind.VIDEO)
                .loop(true)
                .muted(true)
                .build();
    }
}
