package com.googlecode.lanterna.gui2;

import com.googlecode.lanterna.Symbols;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ScrollBarUnitTest {
    @Test
    public void geometryUsesOneCellThumbAndClampsTheScrollPosition() {
        assertNull(ScrollBar.geometry(20, 20, 20, null));
        ScrollBar.Geometry top = ScrollBar.geometry(100, 20, 20, -5);
        ScrollBar.Geometry middle = ScrollBar.geometry(100, 20, 20, 40);
        ScrollBar.Geometry bottom = ScrollBar.geometry(100, 20, 20, null);

        assertEquals(0, top.thumbOffset());
        assertEquals(9, middle.thumbOffset());
        assertEquals(19, bottom.thumbOffset());
        assertEquals(1, bottom.thumbSize());
        assertEquals(80, bottom.maximumPosition());
        assertEquals(20, bottom.trackSize());
    }

    @Test
    public void drawUsesAQuietTrackAndOneVisiblePositionMarker() {
        BasicTextImage image = new BasicTextImage(1, 8);
        ScrollBar.Geometry geometry = ScrollBar.draw(
                image.newTextGraphics(), Direction.VERTICAL, TerminalPosition.TOP_LEFT_CORNER,
                8, 100, 20, 40,
                TextColor.ANSI.WHITE, TextColor.ANSI.BLACK,
                TextColor.ANSI.CYAN, TextColor.ANSI.BLACK);

        assertNotNull(geometry);
        for (int row = 0; row < 8; row++) {
            char expected = row == 3 ? Symbols.BLOCK_SOLID : Symbols.SINGLE_LINE_VERTICAL;
            assertEquals(expected, image.getCharacterAt(0, row).getCharacter());
        }
    }

    @Test
    public void trackClicksJumpDragPreservesGripAndWheelCountsAreCoalesced() {
        TerminalPosition start = new TerminalPosition(30, 5);
        ScrollBar.DragResult armed = ScrollBar.dragStep(
                mouse(MouseActionType.CLICK_DOWN, 30, 14, 1), Direction.VERTICAL, start,
                20, 100, 20, 40, null, 1);
        assertNull(armed.scrollPosition());
        assertEquals(Integer.valueOf(0), armed.gripOffset());

        ScrollBar.DragResult jumped = ScrollBar.dragStep(
                mouse(MouseActionType.CLICK_DOWN, 30, 15, 1), Direction.VERTICAL, start,
                20, 100, 20, 0, null, 1);
        assertEquals(Integer.valueOf(42), jumped.scrollPosition());
        assertEquals(Integer.valueOf(0), jumped.gripOffset());

        ScrollBar.DragResult dragged = ScrollBar.dragStep(
                mouse(MouseActionType.DRAG, 30, 20, 1), Direction.VERTICAL, start,
                20, 100, 20, 42, 0, 1);
        assertEquals(Integer.valueOf(63), dragged.scrollPosition());
        assertNull(dragged.gripOffset());

        assertTrue(ScrollBar.dragStep(
                mouse(MouseActionType.CLICK_RELEASE, 0, 0, 0), Direction.VERTICAL, start,
                20, 100, 20, 42, 0, 1).release());
        assertNull(ScrollBar.dragStep(
                mouse(MouseActionType.CLICK_DOWN, 29, 15, 1), Direction.VERTICAL, start,
                20, 100, 20, 0, null, 1));
        assertEquals(Integer.valueOf(-3), ScrollBar.wheelStep(wheel(MouseActionType.SCROLL_UP, 4, 3)));
        assertEquals(Integer.valueOf(5), ScrollBar.wheelStep(wheel(MouseActionType.SCROLL_DOWN, 5, 5)));
        assertNull(ScrollBar.wheelStep(new KeyStroke(KeyType.Enter)));
    }

    @Test
    public void componentUsesTheSameGeometryForKeyboardMouseAndDefaultPaint() {
        ScrollBar bar = new ScrollBar(Direction.VERTICAL)
                .setScrollMaximum(100)
                .setViewSize(20)
                .setScrollPosition(0);
        bar.setSize(new TerminalSize(1, 20));
        AtomicInteger changes = new AtomicInteger();
        bar.addListener((ignored, oldPosition, newPosition) -> changes.incrementAndGet());

        assertEquals(Interactable.Result.HANDLED, bar.handleInput(new KeyStroke(KeyType.ArrowDown)));
        assertEquals(1, bar.getScrollPosition());
        assertEquals(Interactable.Result.HANDLED,
                bar.handleInput(mouse(MouseActionType.CLICK_DOWN, 0, 10, 1)));
        assertEquals(42, bar.getScrollPosition());
        assertEquals(2, changes.get());

        BasicTextImage image = new BasicTextImage(1, 20);
        bar.draw(TextGUIGraphics.from(image.newTextGraphics()));
        int blocks = 0;
        for (int row = 0; row < 20; row++) {
            if (image.getCharacterAt(0, row).getCharacter() == Symbols.BLOCK_SOLID) blocks++;
        }
        assertEquals(1, blocks);
    }

    private static MouseAction mouse(MouseActionType type, int column, int row, int button) {
        return new MouseAction(type, button, new TerminalPosition(column, row));
    }

    private static MouseAction wheel(MouseActionType type, int button, int count) {
        return new MouseAction(type, button, TerminalPosition.TOP_LEFT_CORNER, count);
    }
}
