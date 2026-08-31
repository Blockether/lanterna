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
        assertCharacterKey("\b", 'h', true, false, false);
        assertCharacterKey("\u0007", 'g', true, false, false);
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
    public void queuedMouseInputCoalescesToCanonicalMouseActions() {
        TerminalPosition latestWheel = new TerminalPosition(5, 4);
        Queue<KeyStroke> wheel = new ArrayDeque<>(List.of(
                new MouseAction(MouseActionType.SCROLL_UP, 4, TerminalPosition.TOP_LEFT_CORNER, 2),
                new MouseAction(MouseActionType.SCROLL_UP, 4, latestWheel, 3),
                new KeyStroke(KeyType.Enter)));
        InputCoalescer wheelInput = new InputCoalescer();
        MouseAction wheelAction = (MouseAction) wheelInput.next(wheel::poll, wheel::poll);
        assertEquals(MouseActionType.SCROLL_UP, wheelAction.getActionType());
        assertEquals(4, wheelAction.getButton());
        assertEquals(5, wheelAction.getCount());
        assertEquals(-5, wheelAction.getScrollDelta());
        assertEquals(latestWheel, wheelAction.getPosition());
        assertTrue(wheelInput.inputPending(() -> { fail("lookahead was not retained"); return null; }));
        assertEquals(KeyType.Enter, wheelInput.next(
                () -> { fail("retained input was not replayed"); return null; },
                () -> { fail("non-pointer input must not drain"); return null; }).getKeyType());
        wheelInput.replay(new KeyStroke(KeyType.Tab));
        assertEquals(KeyType.Tab, wheelInput.next(
                () -> { fail("replayed application input was not retained"); return null; },
                () -> { fail("replayed non-pointer input must not drain"); return null; }).getKeyType());

        Queue<KeyStroke> jitter = new ArrayDeque<>(List.of(
                new MouseAction(MouseActionType.SCROLL_UP, 4, TerminalPosition.TOP_LEFT_CORNER),
                new MouseAction(MouseActionType.SCROLL_DOWN, 5, TerminalPosition.TOP_LEFT_CORNER),
                new KeyStroke('d', false, false)));
        assertEquals(Character.valueOf('d'),
                new InputCoalescer().next(jitter::poll, jitter::poll).getCharacter());

        Queue<KeyStroke> balanced = new ArrayDeque<>(List.of(
                new MouseAction(MouseActionType.SCROLL_UP, 4, TerminalPosition.TOP_LEFT_CORNER),
                new MouseAction(MouseActionType.SCROLL_DOWN, 5, TerminalPosition.TOP_LEFT_CORNER)));
        assertNull(new InputCoalescer().next(balanced::poll, balanced::poll));

        TerminalPosition latestDrag = new TerminalPosition(8, 6);
        Queue<KeyStroke> drag = new ArrayDeque<>(List.of(
                new MouseAction(MouseActionType.DRAG, 1, TerminalPosition.TOP_LEFT_CORNER),
                new MouseAction(MouseActionType.DRAG, 1, latestDrag),
                new KeyStroke(KeyType.Tab)));
        InputCoalescer dragInput = new InputCoalescer();
        MouseAction dragAction = (MouseAction) dragInput.next(drag::poll, drag::poll);
        assertEquals(MouseActionType.DRAG, dragAction.getActionType());
        assertEquals(latestDrag, dragAction.getPosition());
        assertEquals(2, dragAction.getCount());
        assertEquals(KeyType.Tab, dragInput.next(drag::poll, drag::poll).getKeyType());
    }

    @Test
    public void wheelMomentumSmoothingLivesWithPointerInput() {
        assertEquals(List.of(1, 1, 1, 1, 1), driveWheelStream(1, 1, 1, 1, 1));
        assertEquals(List.of(3), driveWheelStream(3));
        assertEquals(List.of(2), driveWheelStream(2, 0));
        assertEquals(List.of(3, 2, 1, 1, 1, 1, 1),
                driveWheelStream(3, 2, 1, 1, 1, -1, 1, -1, 1));
        assertEquals(List.of(3, 3, -2), driveWheelStream(3, 3, -8));

        MouseAction.WheelMomentum cancelled = MouseAction.mergeWheelDelta(3, -3);
        assertEquals(0, cancelled.momentum());
        assertNull(cancelled.delta());

        int momentum = 0;
        for (int index = 0; index < 20; index++) {
            momentum = MouseAction.mergeWheelDelta(momentum, 1).momentum();
        }
        assertEquals(MouseAction.WHEEL_MOMENTUM_CAP, momentum);
    }

    @Test
    public void wheelMomentumDecayUsesAnIdleTimeWindow() {
        assertEquals(0, MouseAction.decayWheelMomentum(0, 999));
        assertEquals(10, MouseAction.decayWheelMomentum(10, 0));
        assertEquals(-10, MouseAction.decayWheelMomentum(-10, 0));
        assertEquals(-1, MouseAction.decayWheelMomentum(-1, 100));
        assertEquals(1, MouseAction.decayWheelMomentum(1, 149));
        assertTrue(MouseAction.decayWheelMomentum(-12, 100) < 0);
        assertTrue(Math.abs(MouseAction.decayWheelMomentum(12, 100))
                < Math.abs(MouseAction.decayWheelMomentum(12, 20)));
        assertEquals(0, MouseAction.decayWheelMomentum(12, MouseAction.WHEEL_MOMENTUM_HOLD_MILLIS));
        assertEquals(0, MouseAction.decayWheelMomentum(-12, 99_999));
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

    private static List<Integer> driveWheelStream(int... deltas) {
        List<Integer> effective = new java.util.ArrayList<>();
        int momentum = 0;
        for (int delta : deltas) {
            MouseAction.WheelMomentum merged = MouseAction.mergeWheelDelta(momentum, delta);
            momentum = merged.momentum();
            if (merged.delta() != null) effective.add(merged.delta());
        }
        return effective;
    }

    private static void assertKey(
            String sequence, KeyType type, boolean ctrl, boolean alt, boolean shift) throws Exception {
        assertKey(decode(sequence), type, ctrl, alt, shift);
    }

    private static void assertCharacterKey(
            String sequence, char character, boolean ctrl, boolean alt, boolean shift) throws Exception {
        KeyStroke key = decode(sequence);
        assertKey(key, KeyType.Character, ctrl, alt, shift);
        assertEquals(Character.valueOf(character), key.getCharacter());
    }

    private static KeyStroke decode(String sequence) throws Exception {
        InputDecoder decoder = new InputDecoder(new StringReader(sequence));
        decoder.addProfile(new DefaultKeyDecodingProfile());
        return decoder.getNextCharacter(true);
    }

    private static void assertKey(
            KeyStroke key, KeyType type, boolean ctrl, boolean alt, boolean shift) {
        assertEquals(type, key.getKeyType());
        assertEquals(ctrl, key.isCtrlDown());
        assertEquals(alt, key.isAltDown());
        assertEquals(shift, key.isShiftDown());
    }
}
