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
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

public class HtmlTerminalViewTest {
    @Test
    public void rendersDirectTextGraphicsAsPortableHtml() throws Exception {
        String html = HtmlTerminalView.render(
                new TerminalSize(20, 4),
                "Direct painter",
                graphics -> {
                    graphics.setForegroundColor(new TextColor.RGB(12, 34, 56));
                    graphics.enableModifiers(SGR.BOLD, SGR.UNDERLINE);
                    graphics.putString(2, 1, "Painted");
                });

        assertTrue(html.contains("Direct painter"));
        assertTrue(html.contains("Painted"));
        assertTrue(html.contains("#0c2238"));
        assertTrue(html.contains("class=\"cell bold underline\""));
        assertTrue(html.contains("data-live=\"false\""));
        assertFalse(html.contains("application/json"));
    }

    @Test
    public void rendersTheRealGui2GridRatherThanRecreatingLayoutInCss() throws Exception {
        Panel grid = new Panel(new GridLayout(2));
        grid.addComponent(new Label("Provider"));
        grid.addComponent(new Label("Model"));
        grid.addComponent(new Label("openai"));
        grid.addComponent(new Label("gpt"));

        String html = HtmlTerminalView.render(grid, new TerminalSize(24, 6), "Grid view");
        assertTrue(html.contains("Provider"));
        assertTrue(html.contains("Model"));
        assertTrue(html.contains("openai"));
        assertTrue(html.contains("gpt"));
        assertTrue(html.contains("style=\"grid-column:"));
        assertFalse(html.contains("document.createElement('span')"));
        assertFalse(html.contains("grid-template-areas"));
    }

    @Test
    public void servesOneInteractiveGui2ViewAndExportsItsCurrentState() throws Exception {
        Label state = new Label("OFF");
        Button toggle = new Button("Toggle", () -> state.setText("ON"));
        Panel grid = new Panel(new GridLayout(2));
        grid.addComponent(new Label("State"));
        grid.addComponent(state);
        grid.addComponent(new Label("Action"));
        grid.addComponent(toggle);

        try (HtmlTerminalView view = HtmlTerminalView.serve(
                grid, new TerminalSize(30, 8), "Interactive grid")) {
            assertTrue(view.getUrl().startsWith("http://127.0.0.1:"));
            assertEquals(new TerminalSize(30, 8), view.getTerminal().getTerminalSize());
            assertTrue(view.renderHtml().contains("OFF"));
            assertTrue(rowText(view.getTerminal().snapshot(), 1).contains("<Toggle>"));

            view.getTerminal().addInput(new KeyStroke(KeyType.Enter));
            await(() -> view.renderHtml().contains("ON"));
            assertEquals("ON", state.getText());

            Path htmlFile = Files.createTempFile("lanterna-view-", ".html");
            try {
                view.writeHtml(htmlFile);
                String exported = Files.readString(htmlFile);
                assertTrue(exported.contains("Interactive grid"));
                assertTrue(exported.contains("ON"));
                assertTrue(exported.contains("data-live=\"false\""));
            }
            finally {
                Files.deleteIfExists(htmlFile);
            }
        }
    }

    @Test
    public void closingAViewStopsItsGuiThreadAndTerminal() throws Exception {
        HtmlTerminalView view = HtmlTerminalView.serve(new Label("Close"));
        HtmlTerminal terminal = view.getTerminal();
        view.close();
        view.close();
        assertTrue(terminal.isClosed());
    }

    private static String rowText(HtmlTerminalRenderer.Frame frame, int row) {
        char[] cells = new char[frame.columns()];
        Arrays.fill(cells, ' ');
        for (HtmlTerminalRenderer.Run run : frame.runs()) {
            if (run.y() != row) continue;
            for (int index = 0; index < run.text().length() && run.x() + index < cells.length; index++) {
                cells[run.x() + index] = run.text().charAt(index);
            }
        }
        return new String(cells);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("condition was not met before the deadline", condition.getAsBoolean());
    }
}
