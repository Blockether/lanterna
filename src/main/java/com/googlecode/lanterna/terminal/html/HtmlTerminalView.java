/*
 * This file is part of lanterna (https://github.com/mabe02/lanterna).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * lanterna is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.googlecode.lanterna.terminal.html;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.gui2.AsynchronousTextGUIThread;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.SeparateTextGUIThread;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Renders one GUI2 component or one direct {@link TextGraphics} painter as
 * portable HTML, and can run a component against a transport-neutral live
 * browser terminal.
 *
 * <p>This is a convenience layer over {@link HtmlTerminal}; complete terminal
 * applications can use {@code HtmlTerminal} directly anywhere they currently
 * create a Lanterna terminal. A view uses the same GUI2 component, layout pass,
 * integer cell buffer and input events as the complete application. The owning
 * application exposes the terminal through its HTTP stack.</p>
 */
public final class HtmlTerminalView implements AutoCloseable {
    private static final TerminalSize DEFAULT_SIZE = new TerminalSize(120, 40);
    private static final String DEFAULT_TITLE = "Lanterna view";

    private final HtmlTerminal terminal;
    private final TerminalScreen screen;
    private final MultiWindowTextGUI textGUI;
    private final BasicWindow window;
    private final AsynchronousTextGUIThread guiThread;
    private final AtomicBoolean closed;

    private HtmlTerminalView(HtmlTerminal terminal, Component component) throws IOException {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(component, "component");
        screen = new TerminalScreen(terminal);
        window = fullScreenWindow(component);
        textGUI = new MultiWindowTextGUI(new SeparateTextGUIThread.Factory(), screen);
        guiThread = (AsynchronousTextGUIThread) textGUI.getGUIThread();
        closed = new AtomicBoolean();

        boolean started = false;
        try {
            screen.startScreen();
            started = true;
            textGUI.addWindow(window);
            textGUI.updateScreen();
            guiThread.start();
        }
        catch (IOException | RuntimeException exception) {
            if (started) {
                try {
                    screen.stopScreen(false);
                }
                catch (IOException suppressed) {
                    exception.addSuppressed(suppressed);
                }
            }
            terminal.close();
            throw exception;
        }
    }

    /** Start a component against a 120 by 40 cell browser terminal. */
    public static HtmlTerminalView start(Component component) throws IOException {
        return start(component, DEFAULT_SIZE, DEFAULT_TITLE);
    }

    /** Start a component against a configured browser terminal. */
    public static HtmlTerminalView start(Component component, TerminalSize size, String title) throws IOException {
        TerminalSize viewSize = Objects.requireNonNull(size, "size");
        HtmlTerminal terminal = HtmlTerminal.builder()
                .initialSize(viewSize)
                .columnRange(viewSize.getColumns(), viewSize.getColumns())
                .rowRange(viewSize.getRows(), viewSize.getRows())
                .browserResize(false)
                .title(Objects.requireNonNull(title, "title"))
                .build();
        return start(component, terminal);
    }

    /**
     * Start a component using a configured terminal. The returned view owns the
     * terminal and closes it when the view is closed.
     */
    public static HtmlTerminalView start(Component component, HtmlTerminal terminal) throws IOException {
        return new HtmlTerminalView(terminal, component);
    }

    /** Render one GUI2 component to a self-contained HTML document. */
    public static String render(Component component, TerminalSize size, String title) throws IOException {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(title, "title");
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(size);
        TerminalScreen screen = new TerminalScreen(terminal);
        try {
            screen.startScreen();
            MultiWindowTextGUI textGUI = new MultiWindowTextGUI(screen);
            textGUI.addWindow(fullScreenWindow(component));
            textGUI.updateScreen();
            return HtmlTerminalRenderer.renderDocument(terminal, title);
        }
        finally {
            screen.stopScreen(false);
            terminal.close();
        }
    }

    /** Render one GUI2 component using a 120 by 40 cell viewport. */
    public static String render(Component component) throws IOException {
        return render(component, DEFAULT_SIZE, DEFAULT_TITLE);
    }

    /** Render direct cell painting to a self-contained HTML document. */
    public static String render(
            TerminalSize size,
            String title,
            Consumer<TextGraphics> painter) throws IOException {
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(painter, "painter");
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(size);
        TerminalScreen screen = new TerminalScreen(terminal);
        try {
            screen.startScreen();
            painter.accept(screen.newTextGraphics());
            screen.refresh();
            return HtmlTerminalRenderer.renderDocument(terminal, title);
        }
        finally {
            screen.stopScreen(false);
            terminal.close();
        }
    }

    public HtmlTerminal getTerminal() {
        return terminal;
    }

    public MultiWindowTextGUI getTextGUI() {
        return textGUI;
    }

    public Window getWindow() {
        return window;
    }


    public String renderHtml() {
        return terminal.renderHtml();
    }

    public void writeHtml(Path path) throws IOException {
        terminal.writeHtml(path);
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        guiThread.stop();
        terminal.addInput(new KeyStroke(KeyType.EOF));
        try {
            guiThread.waitForStop(2, TimeUnit.SECONDS);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }

        IOException failure = null;
        try {
            screen.stopScreen(false);
        }
        catch (IOException exception) {
            failure = exception;
        }
        finally {
            terminal.close();
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static BasicWindow fullScreenWindow(Component component) {
        BasicWindow window = new BasicWindow();
        window.setHints(List.of(
                Window.Hint.FULL_SCREEN,
                Window.Hint.NO_DECORATIONS,
                Window.Hint.NO_POST_RENDERING));
        window.setComponent(component);
        return window;
    }
}
