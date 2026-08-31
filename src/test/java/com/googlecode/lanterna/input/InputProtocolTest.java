package com.googlecode.lanterna.input;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalRectangle;
import org.junit.Test;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.Assert.*;

public class InputProtocolTest {
    @Test
    public void sgrMouseUsesAsciiCoordinatesAndStandardButtons() {
        MouseCharacterPattern pattern = new MouseCharacterPattern();

        MouseAction down = matchMouse(pattern, "[<0;200;50M");
        assertEquals(MouseActionType.CLICK_DOWN, down.getActionType());
        assertEquals(1, down.getButton());
        assertEquals(new TerminalPosition(199, 49), down.getPosition());

        MouseAction release = matchMouse(pattern, "[<0;200;50m");
        assertEquals(MouseActionType.CLICK_RELEASE, release.getActionType());
        assertEquals(1, release.getButton());

        MouseAction move = matchMouse(pattern, "[<35;20;10M");
        assertEquals(MouseActionType.MOVE, move.getActionType());
        assertEquals(0, move.getButton());

        MouseAction drag = matchMouse(pattern, "[<32;20;10M");
        assertEquals(MouseActionType.DRAG, drag.getActionType());
        assertEquals(1, drag.getButton());

        MouseAction wheel = matchMouse(pattern, "[<65;20;10M");
        assertEquals(MouseActionType.SCROLL_DOWN, wheel.getActionType());
        assertEquals(5, wheel.getButton());
        assertEquals(1, wheel.getCount());
        assertEquals(1, wheel.getScrollDelta());
    }

    @Test
    public void defaultProfileOwnsTerminalControlKeysAndBracketedPasteMarkers() throws Exception {
        assertKey("\n", KeyType.Enter, false, false, false);
        assertKey("\r", KeyType.Enter, false, false, false);
        assertKey("\r\0", KeyType.Enter, false, false, false);
        assertKey("\u001b\n", KeyType.Enter, false, true, false);
        assertKey("\u001b\u007f", KeyType.Backspace, false, true, false);
        assertKey("\b", KeyType.Character, true, false, false);
        assertKey("\u001b[1;4A", KeyType.ArrowUp, false, true, true);
        assertKey("\u001b[200~", KeyType.PasteStart, false, false, false);
        assertKey("\u001b[201~", KeyType.PasteEnd, false, false, false);
    }


    @Test
    public void decoderWaitsForAControlSequenceSplitAcrossReads() throws Exception {
        StringReader splitInput = new StringReader("[<0;200;50M") {
            private long suffixAvailableAt = Long.MAX_VALUE;

            @Override
            public int read() throws java.io.IOException {
                int value = super.read();
                if (suffixAvailableAt == Long.MAX_VALUE) {
                    suffixAvailableAt = System.nanoTime() + 5_000_000L;
                }
                return value;
            }

            @Override
            public boolean ready() {
                return suffixAvailableAt == Long.MAX_VALUE || System.nanoTime() >= suffixAvailableAt;
            }
        };
        InputDecoder decoder = new InputDecoder(splitInput);
        decoder.setEscapeSequenceTimeoutMillis(100);
        decoder.addProfile(new DefaultKeyDecodingProfile());

        KeyStroke decoded = decoder.getNextCharacter(true);

        assertEquals(100, decoder.getEscapeSequenceTimeoutMillis());
        assertTrue(decoded instanceof MouseAction);
        assertEquals(new TerminalPosition(199, 49), ((MouseAction) decoded).getPosition());
    }
    @Test
    public void keystrokesExposePasteTextWithoutApplicationDecoding() {
        assertEquals("x", new KeyStroke('x', false, false).getText());
        assertEquals("\n", new KeyStroke(KeyType.Enter).getText());
        assertEquals("\t", new KeyStroke(KeyType.Tab).getText());
        assertNull(new KeyStroke(KeyType.ArrowLeft).getText());
    }

    @Test
    public void mouseActionsKeepButtonIdentitySeparateFromCoalescedCount() {
        MouseAction wheel = new MouseAction(
                MouseActionType.SCROLL_UP, 4, TerminalPosition.TOP_LEFT_CORNER, 6);
        assertEquals(4, wheel.getButton());
        assertEquals(6, wheel.getCount());
        assertEquals(-6, wheel.getScrollDelta());
        assertEquals(0, new MouseAction(
                MouseActionType.CLICK_DOWN, 1, TerminalPosition.TOP_LEFT_CORNER).getScrollDelta());
    }

    @Test
    public void queuedMouseInputCoalescesInsideLanterna() {
        Queue<KeyStroke> wheel = new ArrayDeque<>(List.of(
                new MouseAction(MouseActionType.SCROLL_UP, 4, TerminalPosition.TOP_LEFT_CORNER, 2),
                new MouseAction(MouseActionType.SCROLL_UP, 4, TerminalPosition.TOP_LEFT_CORNER, 3),
                new KeyStroke(KeyType.Enter)));
        MouseAction.CoalescedInput wheelBatch = MouseAction.coalesceQueued(wheel.remove(), wheel::poll);
        assertEquals(Long.valueOf(-5), wheelBatch.scrollDelta());
        assertEquals(1, wheelBatch.dragCount());
        assertEquals(KeyType.Enter, wheelBatch.nextKey().getKeyType());

        Queue<KeyStroke> jitter = new ArrayDeque<>(List.of(
                new MouseAction(MouseActionType.SCROLL_UP, 4, TerminalPosition.TOP_LEFT_CORNER),
                new MouseAction(MouseActionType.SCROLL_DOWN, 5, TerminalPosition.TOP_LEFT_CORNER)));
        assertNull(MouseAction.coalesceQueued(jitter.remove(), jitter::poll).scrollDelta());
        TerminalPosition latest = new TerminalPosition(8, 6);
        Queue<KeyStroke> drag = new ArrayDeque<>(List.of(
                new MouseAction(MouseActionType.DRAG, 1, TerminalPosition.TOP_LEFT_CORNER),
                new MouseAction(MouseActionType.DRAG, 1, latest),
                new KeyStroke(KeyType.Tab)));
        MouseAction.CoalescedInput dragBatch = MouseAction.coalesceQueued(drag.remove(), drag::poll);
        assertEquals(latest, ((MouseAction) dragBatch.key()).getPosition());
        assertNull(dragBatch.scrollDelta());
        assertEquals(2, dragBatch.dragCount());
        assertEquals(KeyType.Tab, dragBatch.nextKey().getKeyType());
    }

    @Test
    public void pointerGeometryUsesTerminalRectangles() {
        MouseAction mouse = new MouseAction(
                MouseActionType.CLICK_DOWN, 1, new TerminalPosition(8, 6));
        TerminalRectangle rectangle = new TerminalRectangle(5, 4, 10, 5);
        assertTrue(rectangle.contains(mouse.getPosition()));
        assertEquals(new TerminalPosition(3, 2), rectangle.relativePosition(mouse.getPosition()));
        assertFalse(new TerminalRectangle(9, 6, 1, 1).contains(mouse.getPosition()));
    }

    private static MouseAction matchMouse(MouseCharacterPattern pattern, String sequence) {
        CharacterPattern.Matching matching = pattern.match(sequence.chars()
                .mapToObj(value -> Character.valueOf((char) value))
                .toList());
        assertNotNull(matching);
        assertNotNull(matching.fullMatch);
        return (MouseAction) matching.fullMatch;
    }

    private static void assertKey(
            String sequence, KeyType type, boolean ctrl, boolean alt, boolean shift) throws Exception {
        InputDecoder decoder = new InputDecoder(new StringReader(sequence));
        decoder.addProfile(new DefaultKeyDecodingProfile());
        KeyStroke key = decoder.getNextCharacter(true);
        assertEquals(type, key.getKeyType());
        assertEquals(ctrl, key.isCtrlDown());
        assertEquals(alt, key.isAltDown());
        assertEquals(shift, key.isShiftDown());
        if (type == KeyType.Character) assertEquals(Character.valueOf('h'), key.getCharacter());
    }
}
