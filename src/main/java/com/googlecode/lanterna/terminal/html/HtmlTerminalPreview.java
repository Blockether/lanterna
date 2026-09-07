package com.googlecode.lanterna.terminal.html;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local development preview for an existing terminal. Not a production transport.
 * Binds only 127.0.0.1 on an ephemeral port, owns its server and executor, and leaves
 * the caller's terminal open. GUI2 views already consume input and repaint; direct
 * terminal callers retain their normal input/render loop.
 */
public final class HtmlTerminalPreview implements AutoCloseable {
    private final HtmlTerminalEndpoint endpoint;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final URI uri;

    private HtmlTerminalPreview(HtmlTerminal terminal) throws IOException {
        endpoint = new HtmlTerminalEndpoint(Objects.requireNonNull(terminal, "terminal"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public static HtmlTerminalPreview start(HtmlTerminal terminal) throws IOException {
        return new HtmlTerminalPreview(terminal);
    }

    public URI getUri() {
        return uri;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            // Reject alternate Host names and foreign browser origins, including rebinding.
            if (!uri.getAuthority().equals(exchange.getRequestHeaders().getFirst("Host"))) {
                reply(exchange, 403, "text/plain", "Forbidden");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && "/".equals(path)) {
                reply(exchange, 200, "text/html", endpoint.renderPage(""));
            } else if ("GET".equals(method) && "/events".equals(path)) {
                long after = Long.parseLong(fields(exchange.getRequestURI().getRawQuery()).getOrDefault("after", "-1"));
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, 0);
                while (!closed.get() && !endpoint.isClosed()) {
                    HtmlTerminalEndpoint.Event event = endpoint.awaitEvent(after, 1000);
                    exchange.getResponseBody().write(event.body().getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    after = event.version();
                }
            } else if ("POST".equals(method) && ("/input".equals(path) || "/resize".equals(path))) {
                if (!uri.toString().replaceAll("/$", "").equals(exchange.getRequestHeaders().getFirst("Origin"))) {
                    reply(exchange, 403, "text/plain", "Forbidden");
                    return;
                }
                byte[] bytes = exchange.getRequestBody().readNBytes(16385);
                if (bytes.length > 16384) {
                    reply(exchange, 413, "text/plain", "Input too large");
                    return;
                }
                Map<String, String> values = fields(new String(bytes, StandardCharsets.UTF_8));
                if ("/input".equals(path)) endpoint.submitInput(values);
                else endpoint.resize(values);
                exchange.sendResponseHeaders(204, -1);
            } else {
                reply(exchange, 404, "text/plain", "Not found");
            }
        } catch (IllegalArgumentException exception) {
            reply(exchange, 400, "text/plain", "Invalid request");
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> fields(String encoded) {
        Map<String, String> result = new HashMap<>();
        if (encoded == null || encoded.isEmpty()) return result;
        for (String field : encoded.split("&")) {
            String[] pair = field.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8));
        }
        return result;
    }

    private static void reply(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + ";charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        server.stop(0);
        executor.shutdownNow();
    }
}
