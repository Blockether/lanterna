package com.googlecode.lanterna.terminal.html;

import com.googlecode.lanterna.TerminalSize;
import java.net.URI;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class HtmlTerminalPreviewTest {
    private static int request(URI uri, String body, String origin) throws Exception {
        try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
            socket.setSoTimeout(2000);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            String headers = (body == null ? "GET " : "POST ") + uri.getRawPath() + " HTTP/1.1\r\n"
                    + "Host: " + uri.getAuthority() + "\r\nConnection: close\r\n"
                    + (origin == null ? "" : "Origin: " + origin + "\r\n")
                    + "Content-Length: " + bytes.length + "\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
            BufferedReader response = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return Integer.parseInt(response.readLine().split(" ")[1]);
        }
    }

    @Test
    public void previewOwnsOnlyItsServerAndRejectsCrossOriginInput() throws Exception {
        try (HtmlTerminal terminal = HtmlTerminal.builder().initialSize(new TerminalSize(40, 12)).build()) {
            try (HtmlTerminalPreview preview = HtmlTerminalPreview.start(terminal)) {
                URI uri = preview.getUri();
                String origin = uri.toString().replaceAll("/$", "");
                assertEquals("127.0.0.1", uri.getHost());
                assertEquals(200, request(uri, null, null));
                assertEquals(403, request(uri.resolve("resize"), "cols=50&rows=15", "https://example.com"));
                assertEquals(403, request(uri.resolve("resize"), "cols=50&rows=15", null));
                assertEquals(204, request(uri.resolve("resize"), "cols=50&rows=15", origin));
                assertEquals(50, terminal.getTerminalSize().getColumns());
                assertEquals(400, request(uri.resolve("resize"), "cols=bad&rows=15", origin));
                assertEquals(413, request(uri.resolve("input"), "x".repeat(16385), origin));
                assertEquals(404, request(uri.resolve("missing"), null, null));
            }
            assertFalse(terminal.isClosed());
        }
    }
}
