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

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.terminal.virtual.VirtualTerminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Converts a resolved {@link VirtualTerminal} cell buffer to a CSS-grid frame
 * or a self-contained HTML document.
 *
 * <p>Lanterna remains the only layout engine: this class serializes integer
 * cell positions after the application has painted. The browser never
 * measures components or recomputes terminal geometry.</p>
 */
public final class HtmlTerminalRenderer {
    public static final TextColor DEFAULT_FOREGROUND = new TextColor.RGB(232, 232, 232);
    public static final TextColor DEFAULT_BACKGROUND = new TextColor.RGB(17, 17, 17);

    private static final String TEMPLATE_RESOURCE =
            "/com/googlecode/lanterna/terminal/html/terminal.html";
    private static final String TITLE_MARKER = "__LANTERNA_TITLE__";
    private static final String BODY_MARKER = "__LANTERNA_BODY__";
    private static final String TEMPLATE = loadTemplate();

    private HtmlTerminalRenderer() {
    }

    public record Style(
            String foreground,
            String background,
            boolean bold,
            boolean italic,
            boolean underline,
            boolean strike,
            boolean blink,
            boolean bordered,
            boolean fraktur,
            boolean circled) {
    }

    public record Run(int x, int y, int width, String text, Style style) {
    }

    public record Cursor(int column, int row) {
    }

    public record Frame(
            long version,
            int columns,
            int rows,
            String defaultForeground,
            String defaultBackground,
            boolean cursorVisible,
            Cursor cursor,
            List<Run> runs,
            List<HtmlMedia> media) {
        public Frame {
            runs = List.copyOf(runs);
            media = List.copyOf(media);
        }
    }

    /** Resolve one atomic frame from the visible virtual-terminal viewport. */
    public static Frame snapshot(
            VirtualTerminal terminal,
            long version,
            TextColor defaultForeground,
            TextColor defaultBackground,
            Collection<HtmlMedia> media) {
        Objects.requireNonNull(terminal, "terminal");
        TextColor foreground = Objects.requireNonNull(defaultForeground, "defaultForeground");
        TextColor background = Objects.requireNonNull(defaultBackground, "defaultBackground");
        Collection<HtmlMedia> mediaValues = media == null ? List.of() : media;

        synchronized (terminal) {
            TerminalSize size = terminal.getTerminalSize();
            int columns = size.getColumns();
            int rows = size.getRows();
            Style defaultStyle = style(TextCharacter.DEFAULT_CHARACTER, foreground, background);
            List<Run> runs = new ArrayList<>();
            for (int row = 0; row < rows; row++) {
                appendRow(terminal, row, columns, foreground, background, defaultStyle, runs);
            }
            TerminalPosition position = terminal.getCursorPosition();
            Cursor cursor = position != null
                    && position.getColumn() >= 0 && position.getColumn() < columns
                    && position.getRow() >= 0 && position.getRow() < rows
                    ? new Cursor(position.getColumn(), position.getRow())
                    : null;
            return new Frame(
                    version,
                    columns,
                    rows,
                    cssColor(foreground, foreground),
                    cssColor(background, background),
                    terminal.isCursorVisible(),
                    cursor,
                    runs,
                    new ArrayList<>(mediaValues));
        }
    }

    public static Frame snapshot(VirtualTerminal terminal, long version) {
        return snapshot(terminal, version, DEFAULT_FOREGROUND, DEFAULT_BACKGROUND, List.of());
    }

    private static void appendRow(
            VirtualTerminal terminal,
            int row,
            int columns,
            TextColor defaultForeground,
            TextColor defaultBackground,
            Style defaultStyle,
            List<Run> output) {
        Run pending = null;
        boolean pendingIsSingleWidth = false;
        for (int column = 0; column < columns;) {
            TextCharacter cell = terminal.getCharacter(column, row);
            int width = Math.min(columns - column, cell.isDoubleWidth() ? 2 : 1);
            Style style = style(cell, defaultForeground, defaultBackground);
            String text = cell.getCharacterString();
            boolean defaultBlank = text.codePoints().allMatch(value -> value == ' ')
                    && style.equals(defaultStyle);

            if (defaultBlank) {
                if (pending != null) output.add(pending);
                pending = null;
                pendingIsSingleWidth = false;
            }
            else if (width == 1
                    && pending != null
                    && pendingIsSingleWidth
                    && pending.x() + pending.width() == column
                    && pending.style().equals(style)) {
                pending = new Run(
                        pending.x(), row, pending.width() + 1, pending.text() + text, style);
            }
            else {
                if (pending != null) output.add(pending);
                pending = new Run(column, row, width, text, style);
                pendingIsSingleWidth = width == 1;
            }
            column += width;
        }
        if (pending != null) output.add(pending);
    }

    private static Style style(
            TextCharacter character,
            TextColor defaultForeground,
            TextColor defaultBackground) {
        String foreground = cssColor(character.getForegroundColor(), defaultForeground);
        String background = cssColor(character.getBackgroundColor(), defaultBackground);
        if (character.isReversed()) {
            String swap = foreground;
            foreground = background;
            background = swap;
        }
        EnumSet<SGR> modifiers = character.getModifiers();
        return new Style(
                foreground,
                background,
                modifiers.contains(SGR.BOLD),
                modifiers.contains(SGR.ITALIC),
                modifiers.contains(SGR.UNDERLINE),
                modifiers.contains(SGR.CROSSED_OUT),
                modifiers.contains(SGR.BLINK),
                modifiers.contains(SGR.BORDERED),
                modifiers.contains(SGR.FRAKTUR),
                modifiers.contains(SGR.CIRCLED));
    }

    private static String cssColor(TextColor color, TextColor fallback) {
        TextColor resolved = color == TextColor.ANSI.DEFAULT ? fallback : color;
        return String.format(Locale.ROOT, "#%02x%02x%02x",
                resolved.getRed(), resolved.getGreen(), resolved.getBlue());
    }

    /** Render one complete server-owned frame fragment. */
    public static String renderFrame(Frame frame) {
        Objects.requireNonNull(frame, "frame");
        StringBuilder html = new StringBuilder(Math.max(256, frame.runs().size() * 160));
        html.append("<div class=\"frame\" data-version=\"").append(frame.version())
                .append("\" data-cols=\"").append(frame.columns())
                .append("\" data-rows=\"").append(frame.rows())
                .append("\" data-ink=\"").append(escapeHtml(frame.defaultForeground()))
                .append("\" data-paper=\"").append(escapeHtml(frame.defaultBackground()))
                .append("\"><div id=\"cells\">");
        for (Run run : frame.runs()) appendRun(html, run);
        html.append("</div><div id=\"media-layer\">");
        for (HtmlMedia item : frame.media()) appendMedia(html, item);
        html.append("</div><span id=\"cursor\" class=\"cursor\" aria-hidden=\"true\"");
        if (!frame.cursorVisible() || frame.cursor() == null) html.append(" hidden");
        if (frame.cursor() != null) {
            html.append(" style=\"grid-column:").append(frame.cursor().column() + 1)
                    .append(";grid-row:").append(frame.cursor().row() + 1)
                    .append(";color:").append(escapeHtml(frame.defaultForeground())).append("\"");
        }
        return html.append("></span></div>").toString();
    }

    private static void appendRun(StringBuilder html, Run run) {
        Style style = run.style();
        html.append("<span class=\"cell");
        appendClass(html, style.bold(), "bold");
        appendClass(html, style.italic(), "italic");
        appendClass(html, style.underline(), "underline");
        appendClass(html, style.strike(), "strike");
        appendClass(html, style.blink(), "blink");
        appendClass(html, style.bordered(), "bordered");
        appendClass(html, style.fraktur(), "fraktur");
        appendClass(html, style.circled(), "circled");
        html.append("\" style=\"grid-column:").append(run.x() + 1)
                .append(" / span ").append(run.width())
                .append(";grid-row:").append(run.y() + 1)
                .append(";color:").append(escapeHtml(style.foreground()))
                .append(";background-color:").append(escapeHtml(style.background()))
                .append("\">").append(escapeHtml(run.text())).append("</span>");
    }

    private static void appendClass(StringBuilder html, boolean enabled, String name) {
        if (enabled) html.append(' ').append(name);
    }

    private static void appendMedia(StringBuilder html, HtmlMedia media) {
        String element = switch (media.getKind()) {
            case IMAGE -> "img";
            case VIDEO -> "video";
            case AUDIO -> "audio";
        };
        String source = "data:" + media.getMimeType() + ";base64,"
                + Base64.getEncoder().encodeToString(media.dataUnsafe());
        html.append('<').append(element)
                .append(" class=\"media\" data-media-id=\"").append(escapeHtml(media.getId()))
                .append("\" src=\"").append(escapeHtml(source))
                .append("\" style=\"grid-column:").append(media.getPosition().getColumn() + 1)
                .append(" / span ").append(media.getSize().getColumns())
                .append(";grid-row:").append(media.getPosition().getRow() + 1)
                .append(" / span ").append(media.getSize().getRows()).append("\"");
        if (media.getKind() == HtmlMedia.Kind.IMAGE) {
            html.append(" alt=\"").append(escapeHtml(media.getDescription())).append("\">");
            return;
        }
        html.append(" aria-label=\"").append(escapeHtml(media.getDescription()))
                .append("\" preload=\"metadata\"");
        if (media.hasControls()) html.append(" controls");
        if (media.isAutoplay()) html.append(" autoplay");
        if (media.isLoop()) html.append(" loop");
        if (media.isMuted()) html.append(" muted");
        html.append("></").append(element).append('>');
    }

    /** Build a static, portable HTML document containing this exact frame. */
    public static String renderDocument(Frame frame, String title) {
        return renderTemplate(frame, title, false, "", "", 1, 1000, 1, 1000, false);
    }

    public static String renderDocument(VirtualTerminal terminal, String title) {
        return renderDocument(snapshot(terminal, 0), title);
    }

    static String renderLiveDocument(
            Frame frame,
            String title,
            String endpointPrefix,
            String endpointQuery,
            int minColumns,
            int maxColumns,
            int minRows,
            int maxRows,
            boolean browserResize) {
        return renderTemplate(
                frame,
                title,
                true,
                normalizePrefix(endpointPrefix),
                endpointQuery == null ? "" : endpointQuery,
                minColumns,
                maxColumns,
                minRows,
                maxRows,
                browserResize);
    }

    private static String renderTemplate(
            Frame frame,
            String title,
            boolean live,
            String endpointPrefix,
            String endpointQuery,
            int minColumns,
            int maxColumns,
            int minRows,
            int maxRows,
            boolean browserResize) {
        Objects.requireNonNull(frame, "frame");
        String safeTitle = title == null || title.isBlank() ? "Lanterna terminal" : title;
        StringBuilder body = new StringBuilder(Math.max(512, frame.runs().size() * 160));
        body.append("<body data-live=\"").append(live)
                .append("\" data-endpoint-prefix=\"").append(escapeHtml(endpointPrefix))
                .append("\" data-endpoint-query=\"").append(escapeHtml(endpointQuery))
                .append("\" data-resizable=\"").append(browserResize)
                .append("\" data-min-cols=\"").append(minColumns)
                .append("\" data-max-cols=\"").append(maxColumns)
                .append("\" data-min-rows=\"").append(minRows)
                .append("\" data-max-rows=\"").append(maxRows)
                .append("\" style=\"--ink:").append(escapeHtml(frame.defaultForeground()))
                .append(";--paper:").append(escapeHtml(frame.defaultBackground())).append("\">")
                .append("<div id=\"viewport\"><div id=\"terminal\" role=\"application\" ")
                .append("aria-label=\"Terminal\" tabindex=\"-1\" style=\"grid-template-columns:repeat(")
                .append(frame.columns()).append(",var(--cell-w));grid-template-rows:repeat(")
                .append(frame.rows()).append(",var(--row-h))\">")
                .append(renderFrame(frame))
                .append("</div></div>")
                .append("<textarea id=\"input\" aria-label=\"Terminal keyboard input\" autocomplete=\"off\" ")
                .append("autocapitalize=\"off\" autocorrect=\"off\" spellcheck=\"false\"></textarea>")
                .append("<span id=\"probe\" aria-hidden=\"true\">")
                .append("00000000000000000000000000000000000000000000000000</span>")
                .append("<template id=\"patch\"></template>");
        return fillTemplate(escapeHtml(safeTitle), body.toString());
    }

    private static String normalizePrefix(String value) {
        String prefix = value == null ? "" : value.trim();
        if (!prefix.isEmpty() && !prefix.startsWith("/")) {
            throw new IllegalArgumentException("endpoint prefix must be empty or start with /");
        }
        while (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        return prefix;
    }

    private static String fillTemplate(String title, String body) {
        int titleAt = TEMPLATE.indexOf(TITLE_MARKER);
        int bodyAt = TEMPLATE.indexOf(BODY_MARKER);
        if (titleAt < 0
                || bodyAt < titleAt + TITLE_MARKER.length()
                || TEMPLATE.indexOf(TITLE_MARKER, titleAt + TITLE_MARKER.length()) >= 0
                || TEMPLATE.indexOf(BODY_MARKER, bodyAt + BODY_MARKER.length()) >= 0) {
            throw new IllegalStateException("HTML terminal template markers are invalid");
        }
        return TEMPLATE.substring(0, titleAt)
                + title
                + TEMPLATE.substring(titleAt + TITLE_MARKER.length(), bodyAt)
                + body
                + TEMPLATE.substring(bodyAt + BODY_MARKER.length());
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String loadTemplate() {
        try (InputStream stream = HtmlTerminalRenderer.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (stream == null) throw new IOException("Missing HTML terminal template " + TEMPLATE_RESOURCE);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
