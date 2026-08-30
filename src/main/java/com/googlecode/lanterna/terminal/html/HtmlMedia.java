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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Cell-positioned media embedded in an {@link HtmlTerminal} frame.
 *
 * <p>The bytes are owned by this value, so both live pages and exported HTML
 * documents are self-contained and never depend on the source path remaining
 * readable. Images, video and audio use native browser elements; audio and
 * video controls remain interactive in the live terminal and in an exported
 * snapshot.</p>
 */
public final class HtmlMedia {
    public enum Kind {
        IMAGE,
        VIDEO,
        AUDIO
    }

    private final String id;
    private final Kind kind;
    private final String mimeType;
    private final byte[] data;
    private final TerminalPosition position;
    private final TerminalSize size;
    private final String description;
    private final boolean controls;
    private final boolean autoplay;
    private final boolean loop;
    private final boolean muted;

    private HtmlMedia(Builder builder) {
        id = builder.id;
        kind = builder.kind;
        mimeType = builder.mimeType;
        data = builder.data.clone();
        position = builder.position;
        size = builder.size;
        description = builder.description;
        controls = builder.controls;
        autoplay = builder.autoplay;
        loop = builder.loop;
        muted = builder.muted;
    }

    public static Builder builder(Kind kind, String mimeType, byte[] data) {
        return new Builder(kind, mimeType, data);
    }

    public static Builder image(Path path) throws IOException {
        return fromPath(Kind.IMAGE, path);
    }

    public static Builder video(Path path) throws IOException {
        return fromPath(Kind.VIDEO, path).controls(true);
    }

    public static Builder audio(Path path) throws IOException {
        return fromPath(Kind.AUDIO, path).controls(true).size(new TerminalSize(32, 3));
    }

    public static Builder fromPath(Kind kind, Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return builder(kind, detectMimeType(kind, path), Files.readAllBytes(path))
                .description(path.getFileName().toString());
    }

    private static String detectMimeType(Kind kind, Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".webm")) return kind == Kind.AUDIO ? "audio/webm" : "video/webm";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".ogg")) return kind == Kind.VIDEO ? "video/ogg" : "audio/ogg";

        String detected = Files.probeContentType(path);
        String expectedPrefix = kind.name().toLowerCase(Locale.ROOT) + "/";
        if (detected != null && detected.toLowerCase(Locale.ROOT).startsWith(expectedPrefix)) {
            return detected;
        }
        throw new IOException("Cannot determine media type for " + path.getFileName());
    }

    public String getId() {
        return id;
    }

    public Kind getKind() {
        return kind;
    }

    public String getMimeType() {
        return mimeType;
    }

    public byte[] getData() {
        return data.clone();
    }

    byte[] dataUnsafe() {
        return data;
    }

    public TerminalPosition getPosition() {
        return position;
    }

    public TerminalSize getSize() {
        return size;
    }

    public String getDescription() {
        return description;
    }

    public boolean hasControls() {
        return controls;
    }

    public boolean isAutoplay() {
        return autoplay;
    }

    public boolean isLoop() {
        return loop;
    }

    public boolean isMuted() {
        return muted;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HtmlMedia that)) return false;
        return controls == that.controls
                && autoplay == that.autoplay
                && loop == that.loop
                && muted == that.muted
                && id.equals(that.id)
                && kind == that.kind
                && mimeType.equals(that.mimeType)
                && Arrays.equals(data, that.data)
                && position.equals(that.position)
                && size.equals(that.size)
                && description.equals(that.description);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(id, kind, mimeType, position, size, description, controls, autoplay, loop, muted);
        return 31 * result + Arrays.hashCode(data);
    }

    public static final class Builder {
        private final Kind kind;
        private final String mimeType;
        private final byte[] data;
        private String id = UUID.randomUUID().toString();
        private TerminalPosition position = TerminalPosition.TOP_LEFT_CORNER;
        private TerminalSize size = TerminalSize.ONE;
        private String description = "";
        private boolean controls;
        private boolean autoplay;
        private boolean loop;
        private boolean muted;

        private Builder(Kind kind, String mimeType, byte[] data) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.mimeType = requireMimeType(mimeType, kind);
            this.data = Objects.requireNonNull(data, "data").clone();
        }

        private static String requireMimeType(String mimeType, Kind kind) {
            String value = Objects.requireNonNull(mimeType, "mimeType").trim().toLowerCase(Locale.ROOT);
            String prefix = kind.name().toLowerCase(Locale.ROOT) + "/";
            if (!value.startsWith(prefix)) {
                throw new IllegalArgumentException("Media type " + value + " does not match " + kind);
            }
            return value;
        }

        public Builder id(String id) {
            String value = Objects.requireNonNull(id, "id").trim();
            if (value.isEmpty()) throw new IllegalArgumentException("id must not be empty");
            this.id = value;
            return this;
        }

        public Builder position(TerminalPosition position) {
            this.position = Objects.requireNonNull(position, "position");
            if (position.getColumn() < 0 || position.getRow() < 0) {
                throw new IllegalArgumentException("position must not be negative");
            }
            return this;
        }

        public Builder size(TerminalSize size) {
            this.size = Objects.requireNonNull(size, "size");
            if (size.getColumns() < 1 || size.getRows() < 1) {
                throw new IllegalArgumentException("size must be positive");
            }
            return this;
        }

        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        public Builder controls(boolean controls) {
            this.controls = controls;
            return this;
        }

        public Builder autoplay(boolean autoplay) {
            this.autoplay = autoplay;
            return this;
        }

        public Builder loop(boolean loop) {
            this.loop = loop;
            return this;
        }

        public Builder muted(boolean muted) {
            this.muted = muted;
            return this;
        }

        public HtmlMedia build() {
            return new HtmlMedia(this);
        }
    }
}
