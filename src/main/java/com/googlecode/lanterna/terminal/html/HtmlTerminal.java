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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A complete, interactive browser terminal backed by Lanterna's real virtual
 * terminal buffer.
 *
 * <p>Use this class anywhere a {@link Terminal} is accepted, including
 * {@code TerminalScreen} and GUI2. The loopback page displays the exact cells
 * Lanterna resolved and sends resize, keyboard, paste, mouse and wheel events
 * back as ordinary Lanterna input. Closing the terminal stops the HTTP server.
 * {@link #renderHtml()} exports the current frame as one portable HTML file.</p>
 */
public final class HtmlTerminal extends DefaultVirtualTerminal {
    private static final int MAX_REQUEST_BYTES = 65_536;
    private static final Map<String, KeyType> SPECIAL_KEYS = specialKeys();

    private final String title;
    private final TextColor defaultForeground;
    private final TextColor defaultBackground;
    private final int minColumns;
    private final int maxColumns;
    private final int minRows;
    private final int maxRows;
    private final boolean browserResize;
    private final String token;
    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicLong version;
    private final Object frameMonitor;
    private final AtomicBoolean closed;
    private final AtomicBoolean serverStopped;
    private final Map<String, HtmlMedia> media;
    private final URI uri;

    private HtmlTerminal(Builder builder) throws IOException {
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
        serverStopped = new AtomicBoolean();
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
                stopServer();
            }

            @Override
            public void onResized(Terminal terminal, TerminalSize newSize) {
                changed();
            }
        });
        if (builder.embeddedServer) {
            token = UUID.randomUUID().toString();
            executor = Executors.newCachedThreadPool(new DaemonThreadFactory("lanterna-html"));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", builder.port), 0);
            server.setExecutor(executor);
            server.createContext("/", new TerminalHandler());
            server.start();
            uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/?token=" + token);
        }
        else {
            token = "";
            executor = null;
            server = null;
            uri = null;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean hasEmbeddedServer() {
        return server != null;
    }

    public URI getUri() {
        requireEmbeddedServer();
        return uri;
    }

    public String getUrl() {
        return getUri().toString();
    }

    public int getPort() {
        requireEmbeddedServer();
        return server.getAddress().getPort();
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
            stopServer();
        }
    }

    private void stopServer() {
        if (serverStopped.compareAndSet(false, true)) {
            if (server != null) server.stop(0);
            if (executor != null) executor.shutdownNow();
            synchronized (frameMonitor) {
                frameMonitor.notifyAll();
            }
        }
    }

    private void requireEmbeddedServer() {
        if (server == null) throw new IllegalStateException("Embedded HTML server is disabled");
    }

    private void changed() {
        version.incrementAndGet();
        synchronized (frameMonitor) {
            frameMonitor.notifyAll();
        }
    }

    private final class TerminalHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                route(exchange);
            }
            catch (RequestException exception) {
                send(exchange, exception.status, "text/plain; charset=utf-8", exception.getMessage());
            }
            catch (RuntimeException exception) {
                send(exchange, 500, "text/plain; charset=utf-8", "HTML terminal request failed");
            }
            finally {
                exchange.close();
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        Map<String, String> query = decodeForm(exchange.getRequestURI().getRawQuery());
        if (!token.equals(query.get("token"))) {
            throw new RequestException(403, "Forbidden");
        }
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(method) && "/".equals(path)) {
            String page = HtmlTerminalRenderer.renderLiveDocument(
                    snapshot(),
                    title,
                    "",
                    "?token=" + token,
                    minColumns,
                    maxColumns,
                    minRows,
                    maxRows,
                    browserResize);
            send(exchange, 200, "text/html; charset=utf-8", page);
            return;
        }
        if ("GET".equals(method) && "/healthz".equals(path)) {
            send(exchange, 200, "text/plain; charset=utf-8", "ok");
            return;
        }
        if ("GET".equals(method) && "/events".equals(path)) {
            long after = parseLong(exchange.getRequestHeaders().getFirst("Last-Event-ID"),
                    parseLong(query.get("after"), -1));
            streamEvents(exchange, after);
            return;
        }
        if ("POST".equals(method) && "/resize".equals(path)) {
            Map<String, String> form = decodeForm(readBody(exchange));
            try {
                resizeFromBrowser(
                        (int) parseLong(form.get("cols"), minColumns),
                        (int) parseLong(form.get("rows"), minRows));
            }
            catch (IllegalStateException exception) {
                throw new RequestException(409, exception.getMessage());
            }
            send(exchange, 204, "text/plain; charset=utf-8", "");
            return;
        }
        if ("POST".equals(method) && "/input".equals(path)) {
            submitBrowserInput(decodeForm(readBody(exchange)));
            send(exchange, 204, "text/plain; charset=utf-8", "");
            return;
        }
        throw new RequestException(404, "Not found");
    }

    private void streamEvents(HttpExchange exchange, long afterVersion) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-transform");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            long after = afterVersion;
            while (!closed.get()) {
                HtmlTerminalRenderer.Frame frame = awaitFrame(after, 15_000);
                String event;
                if (frame.version() == after) event = ": keepalive\n\n";
                else {
                    StringBuilder text = new StringBuilder();
                    text.append("id: ").append(frame.version()).append('\n');
                    text.append("event: frame\n");
                    for (String line : HtmlTerminalRenderer.renderFrame(frame).split("\\R", -1)) {
                        text.append("data: ").append(line).append('\n');
                    }
                    event = text.append('\n').toString();
                    after = frame.version();
                }
                output.write(event.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
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

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            byte[] bytes = input.readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) throw new RequestException(413, "Request is too large");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> decodeForm(String encoded) {
        Map<String, String> form = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) return form;
        for (String part : encoded.split("&")) {
            if (part.isEmpty()) continue;
            String[] pair = part.split("=", 2);
            String name = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8);
            form.put(name, value);
        }
        return form;
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

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        if (contentType.startsWith("text/html")) {
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'none'; connect-src 'self'; img-src data:; media-src data:; "
                            + "style-src 'unsafe-inline'; script-src 'unsafe-inline'; "
                            + "base-uri 'none'; frame-ancestors 'none'");
        }
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class RequestException extends RuntimeException {
        private final int status;

        private RequestException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicLong counter = new AtomicLong();

        private DaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    public static final class Builder {
        private TerminalSize initialSize = new TerminalSize(120, 40);
        private int port;
        private String title = "Lanterna terminal";
        private TextColor defaultForeground = HtmlTerminalRenderer.DEFAULT_FOREGROUND;
        private TextColor defaultBackground = HtmlTerminalRenderer.DEFAULT_BACKGROUND;
        private int minColumns = 20;
        private int maxColumns = 400;
        private int minRows = 8;
        private int maxRows = 200;
        private boolean browserResize = true;
        private boolean embeddedServer = true;

        private Builder() {
        }

        public Builder initialSize(TerminalSize initialSize) {
            this.initialSize = Objects.requireNonNull(initialSize, "initialSize");
            return this;
        }

        public Builder port(int port) {
            if (port < 0 || port > 65_535) throw new IllegalArgumentException("port is out of range");
            this.port = port;
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

        /** Disable the loopback server when another HTTP transport owns the terminal. */
        public Builder embeddedServer(boolean enabled) {
            embeddedServer = enabled;
            return this;
        }

        public HtmlTerminal build() throws IOException {
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
