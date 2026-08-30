HTML terminal backend
---

The Blockether fork can run the same Lanterna application in a browser or export
its current cell buffer as one portable HTML document. The browser is a terminal
backend, not a second GUI implementation: `TerminalScreen` and GUI2 still perform
measurement, layout, focus handling and painting in integer terminal cells.

## Complete applications

Use `HtmlTerminal` anywhere the application would otherwise create a terminal:

```java
try (HtmlTerminal terminal = HtmlTerminal.builder()
        .initialSize(new TerminalSize(120, 40))
        .title("My terminal application")
        .build()) {
    TerminalScreen screen = new TerminalScreen(terminal);
    screen.startScreen();

    // The ordinary TerminalScreen or MultiWindowTextGUI application runs here.

    terminal.writeHtml(Path.of("current-view.html"));
    screen.close();
}
```

Every frame comes from the resolved `VirtualTerminal` cell buffer, including wide
characters, the cursor, ANSI, indexed and RGB colours, and every `SGR` modifier.
`renderHtml()` and `writeHtml(Path)` export the current frame with inline CSS,
cells and media. The resulting file needs no server or external asset.

## Host-owned HTTP transport

Lanterna deliberately contains no HTTP server, Jetty or servlet dependency and
opens no socket. The application plugs the terminal into the HTTP stack it already
owns:

```java
try (HtmlTerminal terminal = HtmlTerminal.builder().build()) {
    String firstResponse = terminal.renderLiveHtml("/terminal");

    long cursor = terminal.snapshot().version();
    HtmlTerminalRenderer.Frame frame = terminal.awaitFrame(cursor, 15_000);
    String eventFragment = HtmlTerminalRenderer.renderFrame(frame);

    terminal.submitBrowserInput(formFields);
    terminal.resizeFromBrowser(columns, rows);
}
```

The host serves `renderLiveHtml` on the route prefix, carries each newer
`renderFrame` result as an SSE `frame` event, and maps the browser's `/input` and
`/resize` forms to the two methods above. The host's existing server owns routing,
authorization, request limits, SSE keepalives and shutdown. No WebSocket or second
Java HTTP implementation is needed.

The live page is server-rendered: its first response already contains the resolved
cell and media markup. Later paints arrive as HTML fragments on a native
`EventSource`. The small browser script only swaps those fragments, measures the
viewport, and forwards input and resize forms; it does not receive a frame model or
construct terminal cells. Unchanged media elements are retained across paints so
active audio or video does not restart when unrelated cells change. A media item
whose rendered attributes or bytes change is replaced, so a new image, audio source
or video source takes effect.

## One component or painted view

`HtmlTerminalView` is the short path for component development:

```java
Panel grid = new Panel(new GridLayout(2));
grid.addComponent(new Label("Provider"));
grid.addComponent(new Label("openai"));

String html = HtmlTerminalView.render(
        grid, new TerminalSize(40, 8), "Provider view");

try (HtmlTerminalView view = HtmlTerminalView.start(
        grid, new TerminalSize(40, 8), "Provider view")) {
    HtmlTerminal terminal = view.getTerminal();
    // Expose terminal through the application's own transport. GUI2 buttons,
    // text fields, focus and callbacks remain live.
}
```

There is also a `render(TerminalSize, String, Consumer<TextGraphics>)` overload
for direct cell painters. Grid and linear layouts are calculated by GUI2 before
the browser sees them; CSS Grid only places already-resolved cell rectangles.

`TextGraphicsComponent` turns an existing cell painter into a real GUI2 component,
so it can be measured and positioned by `GridLayout` or `LinearLayout` without a
rewrite. `TextGUIGraphics.from(TextGraphics)` adapts an ordinary paint surface when
drawing such a paint-only component tree outside a `TextGUI`.

## Images, video and audio

Media is explicit terminal content so it can survive both live frames and static
exports:

```java
terminal.putMedia(HtmlMedia.image(Path.of("preview.png"))
        .position(new TerminalPosition(4, 8))
        .size(new TerminalSize(32, 12))
        .description("Preview")
        .build());

terminal.putMedia(HtmlMedia.audio(Path.of("answer.wav"))
        .position(new TerminalPosition(4, 22))
        .build());
```

`HtmlMedia.image`, `video` and `audio` copy the source bytes when built. Static
HTML therefore embeds `data:` URLs rather than retaining filesystem or network
references. Video and audio expose controls by default; autoplay, loop and mute
are builder options.

## Inspecting layout

Press `Ctrl+Shift+G` in a live or exported view to overlay exact cell boundaries.
This is useful for browser-based component review while keeping the terminal
buffer as the layout source of truth.
