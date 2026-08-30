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
    private static final String BOOTSTRAP_MARKER = "__LANTERNA_BOOTSTRAP__";
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

    /** Serialize a frame without a JSON dependency. */
    public static String toJson(Frame frame) {
        Objects.requireNonNull(frame, "frame");
        StringBuilder json = new StringBuilder(Math.max(256, frame.runs().size() * 128));
        json.append('{');
        field(json, "version", frame.version()).append(',');
        field(json, "cols", frame.columns()).append(',');
        field(json, "rows", frame.rows()).append(',');
        field(json, "default_fg", frame.defaultForeground()).append(',');
        field(json, "default_bg", frame.defaultBackground()).append(',');
        field(json, "cursor_visible", frame.cursorVisible()).append(',');
        quote(json, "cursor").append(':');
        if (frame.cursor() == null) json.append("null");
        else {
            json.append('{');
            field(json, "col", frame.cursor().column()).append(',');
            field(json, "row", frame.cursor().row());
            json.append('}');
        }
        json.append(',');
        quote(json, "runs").append(':').append('[');
        for (int index = 0; index < frame.runs().size(); index++) {
            if (index > 0) json.append(',');
            appendRun(json, frame.runs().get(index));
        }
        json.append(']').append(',');
        quote(json, "media").append(':').append('[');
        for (int index = 0; index < frame.media().size(); index++) {
            if (index > 0) json.append(',');
            appendMedia(json, frame.media().get(index));
        }
        return json.append(']').append('}').toString();
    }

    private static void appendRun(StringBuilder json, Run run) {
        Style style = run.style();
        json.append('{');
        field(json, "x", run.x()).append(',');
        field(json, "y", run.y()).append(',');
        field(json, "width", run.width()).append(',');
        field(json, "text", run.text()).append(',');
        field(json, "fg", style.foreground()).append(',');
        field(json, "bg", style.background()).append(',');
        field(json, "bold", style.bold()).append(',');
        field(json, "italic", style.italic()).append(',');
        field(json, "underline", style.underline()).append(',');
        field(json, "strike", style.strike()).append(',');
        field(json, "blink", style.blink()).append(',');
        field(json, "bordered", style.bordered()).append(',');
        field(json, "fraktur", style.fraktur()).append(',');
        field(json, "circled", style.circled());
        json.append('}');
    }

    private static void appendMedia(StringBuilder json, HtmlMedia media) {
        String element = switch (media.getKind()) {
            case IMAGE -> "img";
            case VIDEO -> "video";
            case AUDIO -> "audio";
        };
        String encoded = Base64.getEncoder().encodeToString(media.dataUnsafe());
        json.append('{');
        field(json, "id", media.getId()).append(',');
        field(json, "kind", element).append(',');
        field(json, "src", "data:" + media.getMimeType() + ";base64," + encoded).append(',');
        field(json, "description", media.getDescription()).append(',');
        field(json, "x", media.getPosition().getColumn()).append(',');
        field(json, "y", media.getPosition().getRow()).append(',');
        field(json, "width", media.getSize().getColumns()).append(',');
        field(json, "height", media.getSize().getRows()).append(',');
        field(json, "controls", media.hasControls()).append(',');
        field(json, "autoplay", media.isAutoplay()).append(',');
        field(json, "loop", media.isLoop()).append(',');
        field(json, "muted", media.isMuted());
        json.append('}');
    }

    /** Build a static, portable HTML document containing this exact frame. */
    public static String renderDocument(Frame frame, String title) {
        return renderTemplate(frame, title, false, "", 1, 1000, 1, 1000, false);
    }

    public static String renderDocument(VirtualTerminal terminal, String title) {
        return renderDocument(snapshot(terminal, 0), title);
    }

    static String renderLiveDocument(
            Frame frame,
            String title,
            String token,
            int minColumns,
            int maxColumns,
            int minRows,
            int maxRows,
            boolean browserResize) {
        return renderTemplate(frame, title, true, token, minColumns, maxColumns, minRows, maxRows, browserResize);
    }

    private static String renderTemplate(
            Frame frame,
            String title,
            boolean live,
            String token,
            int minColumns,
            int maxColumns,
            int minRows,
            int maxRows,
            boolean browserResize) {
        String safeTitle = title == null || title.isBlank() ? "Lanterna terminal" : title;
        StringBuilder bootstrap = new StringBuilder(toJson(frame).length() + 256);
        bootstrap.append('{');
        field(bootstrap, "live", live).append(',');
        field(bootstrap, "token", token == null ? "" : token).append(',');
        field(bootstrap, "title", safeTitle).append(',');
        field(bootstrap, "min_cols", minColumns).append(',');
        field(bootstrap, "max_cols", maxColumns).append(',');
        field(bootstrap, "min_rows", minRows).append(',');
        field(bootstrap, "max_rows", maxRows).append(',');
        field(bootstrap, "resizable", browserResize).append(',');
        quote(bootstrap, "frame").append(':').append(toJson(frame));
        bootstrap.append('}');
        return fillTemplate(escapeHtml(safeTitle), bootstrap.toString());
    }

    private static StringBuilder field(StringBuilder json, String name, String value) {
        quote(json, name).append(':');
        return quote(json, value);
    }

    private static StringBuilder field(StringBuilder json, String name, long value) {
        return quote(json, name).append(':').append(value);
    }

    private static StringBuilder field(StringBuilder json, String name, boolean value) {
        return quote(json, name).append(':').append(value);
    }

    private static StringBuilder quote(StringBuilder json, String value) {
        json.append('"');
        String text = value == null ? "" : value;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                case '<' -> json.append("\\u003c");
                case '>' -> json.append("\\u003e");
                case '&' -> json.append("\\u0026");
                default -> {
                    if (character < 0x20 || character == '\u2028' || character == '\u2029') {
                        json.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    }
                    else json.append(character);
                }
            }
        }
        return json.append('"');
    }

    private static String fillTemplate(String title, String bootstrap) {
        int titleAt = TEMPLATE.indexOf(TITLE_MARKER);
        int bootstrapAt = TEMPLATE.indexOf(BOOTSTRAP_MARKER);
        if (titleAt < 0
                || bootstrapAt < titleAt + TITLE_MARKER.length()
                || TEMPLATE.indexOf(TITLE_MARKER, titleAt + TITLE_MARKER.length()) >= 0
                || TEMPLATE.indexOf(BOOTSTRAP_MARKER, bootstrapAt + BOOTSTRAP_MARKER.length()) >= 0) {
            throw new IllegalStateException("HTML terminal template markers are invalid");
        }
        return TEMPLATE.substring(0, titleAt)
                + title
                + TEMPLATE.substring(titleAt + TITLE_MARKER.length(), bootstrapAt)
                + bootstrap
                + TEMPLATE.substring(bootstrapAt + BOOTSTRAP_MARKER.length());
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
