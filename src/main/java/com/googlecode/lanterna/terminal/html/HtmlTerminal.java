/*
 * This file is part of lanterna (https://github.com/mabe02/lanterna).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.googlecode.lanterna.terminal.html;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import com.googlecode.lanterna.terminal.virtual.VirtualTerminalListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A transport-neutral interactive browser terminal backed by Lanterna's real
 * virtual terminal buffer.
 *
 * <p>Use this class anywhere a {@link Terminal} is accepted, including
 * {@code TerminalScreen} and GUI2. The owning application serves the rendered
 * document and SSE fragments through its own HTTP stack, then forwards browser
 * resize, keyboard, paste, mouse and wheel events through this terminal.
 * {@link #renderHtml()} exports the current frame as one portable HTML file.</p>
 */
public final class HtmlTerminal extends DefaultVirtualTerminal {
    private static final Map<String, KeyType> SPECIAL_KEYS = specialKeys();

    private final String title;
    private final TextColor defaultForeground;
    private final TextColor defaultBackground;
    private final int minColumns;
    private final int maxColumns;
    private final int minRows;
    private final int maxRows;
    private final boolean browserResize;
    private final AtomicLong version;
    private final Object frameMonitor;
    private final AtomicBoolean closed;
    private final Map<String, HtmlMedia> media;

    private HtmlTerminal(Builder builder) {
        super(builder.initialSize);
        title = builder.title;
        defaultForeground = builder.defaultForeground;
        defaultBackground = builder.defaultBackground;
        minColumns = builder.minColumns;
        maxColumns = builder.maxColumns;
        minRows = builder.minRows;
        maxRows = builder.maxRows;
        browserResize = builder.browserResize;
        version = new AtomicLong();
        frameMonitor = new Object();
        closed = new AtomicBoolean();
        media = Collections.synchronizedMap(new LinkedHashMap<>());
        addVirtualTerminalListener(new VirtualTerminalListener() {
            @Override
            public void onFlush() {
                changed();
            }

            @Override
            public void onBell() {
            }

            @Override
            public void onClose() {
            }

            @Override
            public void onResized(Terminal terminal, TerminalSize newSize) {
                changed();
            }
        });
    }

    public static Builder builder() {
        return new Builder();
    }


    public String getTitle() {
        return title;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public HtmlTerminalRenderer.Frame snapshot() {
        List<HtmlMedia> currentMedia;
        synchronized (media) {
            currentMedia = new ArrayList<>(media.values());
        }
        return HtmlTerminalRenderer.snapshot(
                this, version.get(), defaultForeground, defaultBackground, currentMedia);
    }

    /** Current frame as one file with inline CSS, JavaScript, cells and media. */
    public String renderHtml() {
        return HtmlTerminalRenderer.renderDocument(snapshot(), title);
    }

    /** Current cells and media as server-rendered HTML. */
    public String renderFrameHtml() {
        return HtmlTerminalRenderer.renderFrame(snapshot());
    }

    /** A live SSR document for an external HTTP transport such as an application gateway. */
    public String renderLiveHtml(String endpointPrefix) {
        return HtmlTerminalRenderer.renderLiveDocument(
                snapshot(), title, endpointPrefix, "", minColumns, maxColumns, minRows, maxRows, browserResize);
    }

    /** Wait for a newer frame, or return the current frame when the timeout expires. */
    public HtmlTerminalRenderer.Frame awaitFrame(long afterVersion, long timeoutMillis) {
        if (timeoutMillis < 0) throw new IllegalArgumentException("timeoutMillis must not be negative");
        if (version.get() == afterVersion && !closed.get() && timeoutMillis > 0) {
            synchronized (frameMonitor) {
                if (version.get() == afterVersion && !closed.get()) {
                    try {
                        frameMonitor.wait(timeoutMillis);
                    }
                    catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        return snapshot();
    }

    /** Submit browser form fields as ordinary Lanterna input. */
    public void submitBrowserInput(Map<String, String> form) {
        addBrowserInput(Objects.requireNonNull(form, "form"));
    }

    /** Clamp and apply a viewport-driven terminal size. */
    public TerminalSize resizeFromBrowser(int columns, int rows) {
        if (!browserResize) throw new IllegalStateException("Browser resize is disabled");
        TerminalSize size = new TerminalSize(
                clamp(columns, minColumns, maxColumns), clamp(rows, minRows, maxRows));
        if (!size.equals(getTerminalSize())) setTerminalSize(size);
        return size;
    }

    public void writeHtml(Path path) throws IOException {
        Files.writeString(path, renderHtml(), StandardCharsets.UTF_8);
    }

    public void putMedia(HtmlMedia value) {
        HtmlMedia item = Objects.requireNonNull(value, "value");
        media.put(item.getId(), item);
        changed();
    }

    public boolean removeMedia(String id) {
        boolean removed = media.remove(Objects.requireNonNull(id, "id")) != null;
        if (removed) changed();
        return removed;
    }

    public void clearMedia() {
        boolean hadMedia;
        synchronized (media) {
            hadMedia = !media.isEmpty();
            media.clear();
        }
        if (hadMedia) changed();
    }

    public List<HtmlMedia> getMedia() {
        synchronized (media) {
            return List.copyOf(media.values());
        }
    }

    /** Replace the complete media layer atomically, notifying live pages once. */
    public void replaceMedia(Collection<HtmlMedia> values) {
        Objects.requireNonNull(values, "values");
        Map<String, HtmlMedia> replacement = new LinkedHashMap<>();
        for (HtmlMedia value : values) {
            HtmlMedia item = Objects.requireNonNull(value, "values contains null");
            if (replacement.put(item.getId(), item) != null) {
                throw new IllegalArgumentException("Duplicate media id: " + item.getId());
            }
        }

        boolean changed;
        synchronized (media) {
            changed = !media.equals(replacement);
            if (changed) {
                media.clear();
                media.putAll(replacement);
            }
        }
        if (changed) changed();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            addInput(new KeyStroke(KeyType.EOF));
            super.close();
            synchronized (frameMonitor) {
                frameMonitor.notifyAll();
            }
        }
    }

    private void changed() {
        version.incrementAndGet();
        synchronized (frameMonitor) {
            frameMonitor.notifyAll();
        }
    }


    private void addBrowserInput(Map<String, String> form) {
        String kind = form.get("kind");
        if ("text".equals(kind)) {
            String text = form.getOrDefault("text", "");
            for (int index = 0; index < text.length(); index++) {
                addInput(new KeyStroke(text.charAt(index), false, false, false));
            }
        }
        else if ("key".equals(kind)) {
            KeyStroke keyStroke = browserKey(form);
            if (keyStroke != null) addInput(keyStroke);
        }
        else if ("mouse".equals(kind)) {
            try {
                MouseActionType type = MouseActionType.valueOf(form.getOrDefault("action", ""));
                int button = (int) parseLong(form.get("button"), 0);
                int column = (int) parseLong(form.get("col"), 0);
                int row = (int) parseLong(form.get("row"), 0);
                addInput(new MouseAction(type, button, new TerminalPosition(column, row)));
            }
            catch (IllegalArgumentException ignored) {
            }
        }
    }

    private static KeyStroke browserKey(Map<String, String> form) {
        String key = form.getOrDefault("key", "");
        boolean control = Boolean.parseBoolean(form.get("ctrl"));
        boolean alt = Boolean.parseBoolean(form.get("alt"));
        boolean shift = Boolean.parseBoolean(form.get("shift"));
        if ("Tab".equals(key)) {
            return new KeyStroke(shift ? KeyType.ReverseTab : KeyType.Tab, control, alt, shift);
        }
        KeyType type = SPECIAL_KEYS.get(key);
        if (type != null) return new KeyStroke(type, control, alt, shift);
        if (key.length() == 1) return new KeyStroke(key.charAt(0), control, alt, shift);
        return null;
    }

    private static Map<String, KeyType> specialKeys() {
        Map<String, KeyType> keys = new LinkedHashMap<>();
        keys.put("Escape", KeyType.Escape);
        keys.put("Enter", KeyType.Enter);
        keys.put("Backspace", KeyType.Backspace);
        keys.put("Delete", KeyType.Delete);
        keys.put("Insert", KeyType.Insert);
        keys.put("Home", KeyType.Home);
        keys.put("End", KeyType.End);
        keys.put("PageUp", KeyType.PageUp);
        keys.put("PageDown", KeyType.PageDown);
        keys.put("ArrowUp", KeyType.ArrowUp);
        keys.put("ArrowDown", KeyType.ArrowDown);
        keys.put("ArrowLeft", KeyType.ArrowLeft);
        keys.put("ArrowRight", KeyType.ArrowRight);
        keys.put("F1", KeyType.F1);
        keys.put("F2", KeyType.F2);
        keys.put("F3", KeyType.F3);
        keys.put("F4", KeyType.F4);
        keys.put("F5", KeyType.F5);
        keys.put("F6", KeyType.F6);
        keys.put("F7", KeyType.F7);
        keys.put("F8", KeyType.F8);
        keys.put("F9", KeyType.F9);
        keys.put("F10", KeyType.F10);
        keys.put("F11", KeyType.F11);
        keys.put("F12", KeyType.F12);
        return Map.copyOf(keys);
    }


    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        }
        catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }


    public static final class Builder {
        private TerminalSize initialSize = new TerminalSize(120, 40);
        private String title = "Lanterna terminal";
        private TextColor defaultForeground = HtmlTerminalRenderer.DEFAULT_FOREGROUND;
        private TextColor defaultBackground = HtmlTerminalRenderer.DEFAULT_BACKGROUND;
        private int minColumns = 20;
        private int maxColumns = 400;
        private int minRows = 8;
        private int maxRows = 200;
        private boolean browserResize = true;
        private Builder() {
        }

        public Builder initialSize(TerminalSize initialSize) {
            this.initialSize = Objects.requireNonNull(initialSize, "initialSize");
            return this;
        }


        public Builder title(String title) {
            String value = Objects.requireNonNull(title, "title").trim();
            if (value.isEmpty()) throw new IllegalArgumentException("title must not be empty");
            this.title = value;
            return this;
        }

        public Builder defaultForeground(TextColor color) {
            defaultForeground = Objects.requireNonNull(color, "color");
            return this;
        }

        public Builder defaultBackground(TextColor color) {
            defaultBackground = Objects.requireNonNull(color, "color");
            return this;
        }

        public Builder columnRange(int minimum, int maximum) {
            requireRange(minimum, maximum, "column");
            minColumns = minimum;
            maxColumns = maximum;
            return this;
        }

        public Builder rowRange(int minimum, int maximum) {
            requireRange(minimum, maximum, "row");
            minRows = minimum;
            maxRows = maximum;
            return this;
        }

        /** Enable or disable viewport-driven terminal resizing. */
        public Builder browserResize(boolean enabled) {
            browserResize = enabled;
            return this;
        }


        public HtmlTerminal build() {
            int columns = clamp(initialSize.getColumns(), minColumns, maxColumns);
            int rows = clamp(initialSize.getRows(), minRows, maxRows);
            initialSize = new TerminalSize(columns, rows);
            return new HtmlTerminal(this);
        }

        private static void requireRange(int minimum, int maximum, String name) {
            if (minimum < 1 || maximum < minimum) {
                throw new IllegalArgumentException(name + " range is invalid");
            }
        }
    }
}
