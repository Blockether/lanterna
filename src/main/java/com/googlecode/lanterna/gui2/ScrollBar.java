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
 *
 * Copyright (C) 2010-2020 Martin Berglund
 */
package com.googlecode.lanterna.gui2;

import com.googlecode.lanterna.Symbols;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.graphics.ThemeDefinition;
import com.googlecode.lanterna.graphics.ThemeStyle;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Interactive one-cell scrollbar for GUI2 components and raw {@link TextGraphics} painters.
 *
 * <p>The maximum is the total content size, the view size is the visible content size and the
 * position is the first visible content unit. The thumb is intentionally one cell: its position,
 * rather than its length, communicates the scroll fraction consistently across terminal renderers.
 * Track clicks jump to the selected position, dragging preserves the pointer grip and wheel events
 * move by their coalesced count.</p>
 */
public class ScrollBar extends AbstractInteractableComponent<ScrollBar> {
    /** Called after the effective scroll position changes. */
    public interface Listener {
        void onChanged(ScrollBar scrollBar, int oldPosition, int newPosition);
    }

    /** Geometry shared by GUI2 rendering, raw painters and pointer hit-testing. */
    public record Geometry(int thumbOffset, int thumbSize, int maximumPosition, int trackSize) {
    }

    /** Result of folding one non-wheel pointer action into a drag interaction. */
    public record DragResult(Integer scrollPosition, Integer gripOffset, boolean release) {
        private static DragResult arm(int gripOffset) {
            return new DragResult(null, gripOffset, false);
        }

        private static DragResult scroll(int position) {
            return new DragResult(position, null, false);
        }

        private static DragResult jump(int position, int gripOffset) {
            return new DragResult(position, gripOffset, false);
        }

        private static DragResult released() {
            return new DragResult(null, null, true);
        }
    }

    private final Direction direction;
    private final List<Listener> listeners;
    private int maximum;
    private int position;
    private int viewSize;
    private Integer dragOffset;

    /** Creates a scrollbar along {@code direction}. */
    public ScrollBar(Direction direction) {
        this.direction = Objects.requireNonNull(direction, "direction");
        this.listeners = new CopyOnWriteArrayList<>();
        this.maximum = 100;
        this.position = 0;
        this.viewSize = 0;
        this.dragOffset = null;
    }

    public Direction getDirection() {
        return direction;
    }

    /** Sets the total content size. */
    public ScrollBar setScrollMaximum(int maximum) {
        if (maximum < 0) {
            throw new IllegalArgumentException("ScrollBar maximum must not be negative: " + maximum);
        }
        if (this.maximum != maximum) {
            this.maximum = maximum;
            setScrollPosition(position);
            invalidate();
        }
        return this;
    }

    public int getScrollMaximum() {
        return maximum;
    }

    /** Sets and clamps the first visible content unit. */
    public ScrollBar setScrollPosition(int position) {
        int next = clamp(position, 0, maximumPosition(maximum, getViewSize()));
        if (this.position != next) {
            int previous = this.position;
            this.position = next;
            invalidate();
            for (Listener listener : listeners) {
                listener.onChanged(this, previous, next);
            }
        }
        return this;
    }

    public int getScrollPosition() {
        return position;
    }

    /** Sets the visible content size; zero derives it from the component size. */
    public ScrollBar setViewSize(int viewSize) {
        if (viewSize < 0) {
            throw new IllegalArgumentException("ScrollBar view size must not be negative: " + viewSize);
        }
        if (this.viewSize != viewSize) {
            this.viewSize = viewSize;
            setScrollPosition(position);
            invalidate();
        }
        return this;
    }

    public int getViewSize() {
        if (viewSize > 0) {
            return viewSize;
        }
        return direction == Direction.HORIZONTAL ? getSize().getColumns() : getSize().getRows();
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public boolean removeListener(Listener listener) {
        return listeners.remove(listener);
    }

    @Override
    protected ScrollBarRenderer createDefaultRenderer() {
        return new DefaultScrollBarRenderer();
    }

    @Override
    protected synchronized Result handleKeyStroke(KeyStroke keyStroke) {
        Integer wheel = wheelStep(keyStroke);
        if (wheel != null) {
            setScrollPosition(position + wheel);
            return Result.HANDLED;
        }

        if (keyStroke.getKeyType() == KeyType.MouseEvent) {
            MouseAction mouse = (MouseAction) keyStroke;
            int trackSize = direction == Direction.VERTICAL ? getSize().getRows() : getSize().getColumns();
            TerminalPosition start = getGlobalPosition();
            if (start == null) start = TerminalPosition.TOP_LEFT_CORNER;
            DragResult result = dragStep(
                    mouse, direction, start, trackSize, maximum, getViewSize(),
                    position, dragOffset, 1);
            if (result != null) {
                if (result.release()) {
                    dragOffset = null;
                }
                else {
                    if (result.gripOffset() != null) {
                        dragOffset = result.gripOffset();
                    }
                    if (result.scrollPosition() != null) {
                        setScrollPosition(result.scrollPosition());
                    }
                }
                return Result.HANDLED;
            }
        }

        if (!keyStroke.isAltDown() && !keyStroke.isCtrlDown() && !keyStroke.isShiftDown()) {
            int delta = direction == Direction.VERTICAL ? verticalDelta(keyStroke) : horizontalDelta(keyStroke);
            if (delta != 0) {
                setScrollPosition(position + delta);
                return Result.HANDLED;
            }
            if (keyStroke.getKeyType() == KeyType.Home) {
                setScrollPosition(0);
                return Result.HANDLED;
            }
            if (keyStroke.getKeyType() == KeyType.End) {
                setScrollPosition(maximumPosition(maximum, getViewSize()));
                return Result.HANDLED;
            }
            if (keyStroke.getKeyType() == KeyType.PageUp) {
                setScrollPosition(position - Math.max(1, getViewSize()));
                return Result.HANDLED;
            }
            if (keyStroke.getKeyType() == KeyType.PageDown) {
                setScrollPosition(position + Math.max(1, getViewSize()));
                return Result.HANDLED;
            }
        }
        return super.handleKeyStroke(keyStroke);
    }

    private static int verticalDelta(KeyStroke keyStroke) {
        if (keyStroke.getKeyType() == KeyType.ArrowUp) return -1;
        if (keyStroke.getKeyType() == KeyType.ArrowDown) return 1;
        return 0;
    }

    private static int horizontalDelta(KeyStroke keyStroke) {
        if (keyStroke.getKeyType() == KeyType.ArrowLeft) return -1;
        if (keyStroke.getKeyType() == KeyType.ArrowRight) return 1;
        return 0;
    }

    /** Returns one-cell thumb geometry, or {@code null} when the content fits. */
    public static Geometry geometry(int totalSize, int viewSize, int trackSize, Integer position) {
        if (viewSize <= 0 || trackSize <= 0 || totalSize <= viewSize) {
            return null;
        }
        int maximumPosition = maximumPosition(totalSize, viewSize);
        int effectivePosition = position == null
                ? maximumPosition
                : clamp(position, 0, maximumPosition);
        int thumbOffset = (int) ((trackSize - 1L) * ((double) effectivePosition / maximumPosition));
        return new Geometry(thumbOffset, 1, maximumPosition, trackSize);
    }

    /** Returns signed coalesced wheel steps, otherwise {@code null}. */
    public static Integer wheelStep(KeyStroke event) {
        if (!(event instanceof MouseAction mouse)) return null;
        int delta = mouse.getScrollDelta();
        return delta == 0 ? null : delta;
    }

    /** Tests the track with a cross-axis band extending left/up from the painted cell. */
    public static boolean isOnTrack(
            Direction direction,
            int pointerColumn,
            int pointerRow,
            TerminalPosition start,
            int trackSize,
            int crossBand) {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(start, "start");
        if (trackSize <= 0 || crossBand <= 0) return false;
        if (direction == Direction.VERTICAL) {
            return pointerColumn >= start.getColumn() - (crossBand - 1)
                    && pointerColumn <= start.getColumn()
                    && pointerRow >= start.getRow()
                    && pointerRow < start.getRow() + trackSize;
        }
        return pointerRow >= start.getRow() - (crossBand - 1)
                && pointerRow <= start.getRow()
                && pointerColumn >= start.getColumn()
                && pointerColumn < start.getColumn() + trackSize;
    }

    /** Tests the one-cell thumb using the same geometry as {@link #draw}. */
    public static boolean isOnThumb(
            Direction direction,
            int pointerColumn,
            int pointerRow,
            TerminalPosition start,
            int crossBand,
            Geometry geometry) {
        if (geometry == null || !isOnTrack(
                direction, pointerColumn, pointerRow, start, geometry.trackSize(), crossBand)) {
            return false;
        }
        int pointerAxis = direction == Direction.VERTICAL ? pointerRow : pointerColumn;
        int startAxis = direction == Direction.VERTICAL ? start.getRow() : start.getColumn();
        return pointerAxis >= startAxis + geometry.thumbOffset()
                && pointerAxis < startAxis + geometry.thumbOffset() + geometry.thumbSize();
    }

    /** Converts an absolute pointer coordinate on one axis to a clamped scroll position. */
    public static Integer scrollFromTrackCoordinate(
            int pointerCoordinate,
            int trackStart,
            int trackSize,
            int totalSize,
            int viewSize,
            int gripOffset) {
        Geometry geometry = geometry(totalSize, viewSize, trackSize, 0);
        if (geometry == null) return null;
        long relative = (long) pointerCoordinate - trackStart - gripOffset;
        int denominator = Math.max(1, trackSize - geometry.thumbSize());
        double fraction = Math.max(0.0, Math.min(1.0, (double) relative / denominator));
        return (int) Math.round(fraction * geometry.maximumPosition());
    }

    /** Folds one non-wheel pointer event into click-to-position and drag state. */
    public static DragResult dragStep(
            MouseAction mouse,
            Direction direction,
            TerminalPosition start,
            int trackSize,
            int totalSize,
            int viewSize,
            Integer position,
            Integer dragOffset,
            int crossBand) {
        Objects.requireNonNull(mouse, "mouse");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(start, "start");
        if (mouse.getActionType() == MouseActionType.CLICK_RELEASE) {
            return DragResult.released();
        }

        Geometry geometry = geometry(totalSize, viewSize, trackSize, position);
        if (geometry == null) return null;
        int column = mouse.getPosition().getColumn();
        int row = mouse.getPosition().getRow();
        int pointerAxis = direction == Direction.VERTICAL ? row : column;
        int startAxis = direction == Direction.VERTICAL ? start.getRow() : start.getColumn();

        if (mouse.getActionType() == MouseActionType.CLICK_DOWN
                && isOnThumb(direction, column, row, start, crossBand, geometry)) {
            return DragResult.arm(pointerAxis - startAxis - geometry.thumbOffset());
        }
        if (mouse.getActionType() == MouseActionType.CLICK_DOWN
                && isOnTrack(direction, column, row, start, trackSize, crossBand)) {
            int grip = geometry.thumbSize() / 2;
            return DragResult.jump(
                    scrollFromTrackCoordinate(pointerAxis, startAxis, trackSize, totalSize, viewSize, grip),
                    grip);
        }
        if (mouse.getActionType() == MouseActionType.DRAG && dragOffset != null) {
            return DragResult.scroll(scrollFromTrackCoordinate(
                    pointerAxis, startAxis, trackSize, totalSize, viewSize, dragOffset));
        }
        return null;
    }

    /** Draws a modern scrollbar into any Lanterna graphics surface. */
    public static Geometry draw(
            TextGraphics graphics,
            Direction direction,
            TerminalPosition start,
            int trackSize,
            int totalSize,
            int viewSize,
            Integer position,
            TextColor trackForeground,
            TextColor trackBackground,
            TextColor thumbForeground,
            TextColor thumbBackground) {
        return draw(
                graphics, direction, start, trackSize, totalSize, viewSize, position,
                trackForeground, trackBackground, thumbForeground, thumbBackground,
                direction == Direction.VERTICAL ? Symbols.SINGLE_LINE_VERTICAL : Symbols.SINGLE_LINE_HORIZONTAL,
                Symbols.BLOCK_SOLID);
    }

    private static Geometry draw(
            TextGraphics graphics,
            Direction direction,
            TerminalPosition start,
            int trackSize,
            int totalSize,
            int viewSize,
            Integer position,
            TextColor trackForeground,
            TextColor trackBackground,
            TextColor thumbForeground,
            TextColor thumbBackground,
            char trackCharacter,
            char thumbCharacter) {
        Objects.requireNonNull(graphics, "graphics");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(start, "start");
        Geometry geometry = geometry(totalSize, viewSize, trackSize, position);
        if (geometry == null) return null;

        graphics.setForegroundColor(Objects.requireNonNull(trackForeground, "trackForeground"));
        graphics.setBackgroundColor(Objects.requireNonNull(trackBackground, "trackBackground"));
        for (int offset = 0; offset < trackSize; offset++) {
            setAxisCharacter(graphics, direction, start, offset, trackCharacter);
        }
        graphics.setForegroundColor(Objects.requireNonNull(thumbForeground, "thumbForeground"));
        graphics.setBackgroundColor(Objects.requireNonNull(thumbBackground, "thumbBackground"));
        setAxisCharacter(graphics, direction, start, geometry.thumbOffset(), thumbCharacter);
        return geometry;
    }

    private static void setAxisCharacter(
            TextGraphics graphics, Direction direction, TerminalPosition start, int offset, char character) {
        if (direction == Direction.VERTICAL) {
            graphics.setCharacter(start.getColumn(), start.getRow() + offset, character);
        }
        else {
            graphics.setCharacter(start.getColumn() + offset, start.getRow(), character);
        }
    }

    private static int maximumPosition(int totalSize, int viewSize) {
        return Math.max(0, totalSize - Math.max(0, viewSize));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    /** Renderer contract for an interactable scrollbar. */
    public abstract static class ScrollBarRenderer implements InteractableRenderer<ScrollBar> {
        @Override
        public TerminalSize getPreferredSize(ScrollBar component) {
            return TerminalSize.ONE;
        }

        @Override
        public TerminalPosition getCursorLocation(ScrollBar component) {
            return null;
        }
    }

    /** Default quiet track plus one-cell thumb renderer. */
    public static class DefaultScrollBarRenderer extends ScrollBarRenderer {
        @Override
        public void drawComponent(TextGUIGraphics graphics, ScrollBar component) {
            TerminalSize size = graphics.getSize();
            int trackSize = component.getDirection() == Direction.VERTICAL
                    ? size.getRows()
                    : size.getColumns();
            if (trackSize <= 0) return;

            ThemeDefinition theme = component.getThemeDefinition();
            ThemeStyle track = theme.getNormal();
            ThemeStyle thumb = component.isFocused() ? theme.getActive() : theme.getSelected();
            Direction direction = component.getDirection();
            draw(
                    graphics,
                    direction,
                    TerminalPosition.TOP_LEFT_CORNER,
                    trackSize,
                    component.getScrollMaximum(),
                    component.getViewSize(),
                    component.getScrollPosition(),
                    track.getForeground(),
                    track.getBackground(),
                    thumb.getForeground(),
                    thumb.getBackground(),
                    theme.getCharacter(
                            direction == Direction.VERTICAL ? "VERTICAL_BACKGROUND" : "HORIZONTAL_BACKGROUND",
                            direction == Direction.VERTICAL
                                    ? Symbols.SINGLE_LINE_VERTICAL
                                    : Symbols.SINGLE_LINE_HORIZONTAL),
                    theme.getCharacter(
                            direction == Direction.VERTICAL ? "VERTICAL_SMALL_TRACKER" : "HORIZONTAL_SMALL_TRACKER",
                            Symbols.BLOCK_SOLID));
        }
    }
}
