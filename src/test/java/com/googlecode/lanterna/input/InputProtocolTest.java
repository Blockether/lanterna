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
    public void shiftEnterDecodesFromEveryTerminalEncoding() throws Exception {
        KeyStroke shiftEnter = new KeyStroke(KeyType.Enter, false, false, true);
        String[] reports = {
                "\u001b[13;2u",     // kitty keyboard protocol / CSI u: kitty, Ghostty, foot, Alacritty, iTerm2, Windows Terminal
                "\u001b[13;2:1u",   // the same with an explicit press event
                "\u001b[13;66u",    // with Caps Lock on
                "\u001b[13;130u",   // with Num Lock on
                "\u001b[57414;2u",  // keypad Enter
                "\u001b[27;2;13~",  // xterm modifyOtherKeys: xterm, WezTerm, tmux extended-keys
                "\u001b[13;28;13;1;16;1_", // win32-input-mode: Windows Terminal and its console host, e.g. WSL
                "\u001bOM",         // Konsole's default keytab
        };
        for (String report : reports) {
            assertEquals(printable(report), List.of(shiftEnter), decodeAll(report));
        }
        assertEquals(List.of(shiftEnter, new KeyStroke('o', false, false), new KeyStroke('k', false, false)),
                decodeAll("\u001b[13;2uok"));
        assertEquals(List.of(new KeyStroke(KeyType.Enter)), decodeAll("\r"));
        assertKey("\u001b\r", KeyType.Enter, false, true, false);
    }

    @Test
    public void extendedEnterReportsKeepTheirModifiers() throws Exception {
        assertKey("\u001b[13u", KeyType.Enter, false, false, false);
        assertKey("\u001b[13;3u", KeyType.Enter, false, true, false);
        assertKey("\u001b[13;4u", KeyType.Enter, false, true, true);
        assertKey("\u001b[13;5u", KeyType.Enter, true, false, false);
        assertKey("\u001b[13;6u", KeyType.Enter, true, false, true);
        assertKey("\u001b[13;33u", KeyType.Enter, false, true, false);  // meta reads as alt
        assertKey("\u001b[13;10u", KeyType.Enter, false, false, true);  // super is ignored
        assertKey("\u001b[27;3;13~", KeyType.Enter, false, true, false);
        assertKey("\u001b[27;5;13~", KeyType.Enter, true, false, false);
        assertKey("\u001b[27;6;13~", KeyType.Enter, true, false, true);
    }

    @Test
    public void extendedReportsDecodeLikeTheLegacyBytesOfTheSameKey() throws Exception {
        String[][] reportAndLegacy = {
                {"\u001b[27u", "\u001b"},           // Escape
                {"\u001b[91;5u", "\u001b"},         // Ctrl+[
                {"\u001b[99;5u", "\u0003"},         // Ctrl+C
                {"\u001b[27;5;99~", "\u0003"},      // Ctrl+C, modifyOtherKeys
                {"\u001b[32;5u", "\u0000"},         // Ctrl+Space
                {"\u001b[105;5u", "\t"},            // Ctrl+I
                {"\u001b[106;5u", "\n"},            // Ctrl+J
                {"\u001b[109;5u", "\r"},            // Ctrl+M
                {"\u001b[120;3u", "\u001bx"},       // Alt+x
                {"\u001b[27;3;120~", "\u001bx"},    // Alt+x, modifyOtherKeys
                {"\u001b[98:66;4u", "\u001bB"},     // Alt+Shift+b with its shifted key
                {"\u001b[98;4u", "\u001bB"},        // Alt+Shift+b without it
                {"\u001b[46:62;4u", "\u001b>"},     // Alt+Shift+. on a US layout
                {"\u001b[261;3u", "\u001bą"},  // Alt+ą
                {"\u001b[97;7u", "\u001b\u0001"},   // Ctrl+Alt+a
                {"\u001b[9;2u", "\u001b[Z"},        // Shift+Tab
                {"\u001b[27;2;9~", "\u001b[Z"},     // Shift+Tab, modifyOtherKeys
                {"\u001b[127;3u", "\u001b\u007f"},  // Alt+Backspace
                {"\u001b[57414u", "\r"},            // keypad Enter
                {"\u001b[57399u", "0"},             // keypad 0
                {"\u001b[57417u", "\u001b[D"},      // keypad Left
                {"\u001b[57376u", "\u001b[25~"},    // F13
        };
        for (String[] pair : reportAndLegacy) {
            assertEquals(printable(pair[0]), decodeAll(pair[1]), decodeAll(pair[0]));
        }
    }

    @Test
    public void extendedReportsWithoutAKeyTypeAreConsumedWhole() throws Exception {
        KeyStroke unknown = new KeyStroke(KeyType.Unknown);
        KeyStroke x = new KeyStroke('x', false, false);
        String[] reports = {
                "\u001b[13;2:3u",    // key release
                "\u001b[57428u",     // media play
                "\u001b[57441;2u",   // left Shift alone
                "\u001b[57427~",     // keypad begin
                "\u001b[128512u",    // a code point outside the Basic Multilingual Plane
                "\u001b[13;2;13;1u", // too many fields
        };
        for (String report : reports) {
            assertEquals(printable(report), List.of(unknown, x), decodeAll(report + "x"));
        }
        assertKey("\u001b[3;5~", KeyType.Delete, true, false, false);
        assertKey("\u001b[1;2P", KeyType.F1, false, false, true);
    }

    @Test
    public void win32ShiftEnterIsOneShiftedEnter() throws Exception {
        // Windows Terminal and its console host (WSL) after Esc [ ? 9001 h send every key event as
        // Esc [ Vk ; Sc ; Uc ; Kd ; Cs ; Rc _: here Shift down, Enter down, Enter up, Shift up.
        String shiftEnter = "\u001b[16;42;0;1;16;1_\u001b[13;28;13;1;16;1_\u001b[13;28;13;0;16;1_\u001b[16;42;0;0;0;1_";
        String enter = "\u001b[13;28;13;1;0;1_\u001b[13;28;13;0;0;1_";
        KeyStroke shiftedEnter = new KeyStroke(KeyType.Enter, false, false, true);
        assertEquals(List.of(shiftedEnter), decodeAll(shiftEnter));
        assertEquals(List.of(new KeyStroke(KeyType.Enter)), decodeAll(enter));
        // pasted text reaches the application as plain characters between the reports
        assertEquals(List.of(shiftedEnter, new KeyStroke('o', false, false), new KeyStroke('k', false, false),
                        new KeyStroke(KeyType.Enter)),
                decodeAll(shiftEnter + "ok" + enter));
        assertKey("\u001b[13;28;13;1;176;1_", KeyType.Enter, false, false, true);  // with Num Lock and Caps Lock on
        assertKey("\u001b[13;28;13;1;272;1_", KeyType.Enter, false, false, true);  // keypad Enter
        assertKey("\u001b[13;28;10;1;8;1_", KeyType.Enter, true, false, false);    // Ctrl+Enter
        assertKey("\u001b[13;28;13;1;2;1_", KeyType.Enter, false, true, false);    // Alt+Enter
        assertKey("\u001b[8;14;127;1;8;1_", KeyType.Backspace, true, false, false); // Ctrl+Backspace
    }

    @Test
    public void win32ReportsEndAtTheirFinalCharacter() {
        // No pattern may wait for more input after a whole report, or every key would lag.
        List<Character> report = "\u001b[13;28;13;1;16;1_".chars()
                .mapToObj(value -> Character.valueOf((char) value))
                .toList();
        for (CharacterPattern pattern : new DefaultKeyDecodingProfile().getPatterns()) {
            CharacterPattern.Matching matching = pattern.match(report);
            assertTrue(pattern.getClass().getSimpleName(), matching == null || !matching.partialMatch);
        }
    }

    @Test
    public void win32ReportsDecodeLikeTheLegacyBytesOfTheSameKey() throws Exception {
        String[][] reportAndLegacy = {
                {"\u001b[65;30;97;1;0;1_", "a"},
                {"\u001b[65;30;65;1;16;1_", "A"},                // Shift+a
                {"\u001b[65;30;261;1;9;1_", "ą"},                // AltGr+a, Polish
                {"\u001b[81;16;64;1;9;1_", "@"},                 // AltGr+q, German
                {"\u001b[67;46;3;1;8;1_", "\u0003"},             // Ctrl+C
                {"\u001b[32;57;32;1;8;1_", "\u0000"},            // Ctrl+Space
                {"\u001b[50;3;0;1;8;1_", "\u0000"},              // Ctrl+2
                {"\u001b[219;26;27;1;8;1_", "\u001b"},           // Ctrl+[
                {"\u001b[88;45;120;1;2;1_", "\u001bx"},          // Alt+x
                {"\u001b[66;48;66;1;18;1_", "\u001bB"},          // Alt+Shift+b
                {"\u001b[65;30;0;1;10;1_", "\u001b\u0001"},      // Ctrl+Alt+a, US layout
                {"\u001b[9;15;9;1;0;1_", "\t"},                  // Tab
                {"\u001b[9;15;9;1;16;1_", "\u001b[Z"},           // Shift+Tab
                {"\u001b[27;1;27;1;0;1_", "\u001b"},             // Escape
                {"\u001b[8;14;8;1;0;1_", "\u007f"},              // Backspace
                {"\u001b[8;14;8;1;2;1_", "\u001b\u007f"},        // Alt+Backspace
                {"\u001b[38;72;0;1;256;1_", "\u001b[A"},         // Up
                {"\u001b[37;75;0;1;264;1_", "\u001b[1;5D"},      // Ctrl+Left
                {"\u001b[46;83;0;1;272;1_", "\u001b[3;2~"},      // Shift+Delete
                {"\u001b[34;81;0;1;256;1_", "\u001b[6~"},        // Page Down
                {"\u001b[116;63;0;1;0;1_", "\u001b[15~"},        // F5
                {"\u001b[97;79;49;1;32;1_", "1"},                // keypad 1 with Num Lock on
                {"\u001b[231;0;55357;1;0;1_\u001b[231;0;56832;1;0;1_", "😀"}, // emoji, one UTF-16 unit each
        };
        for (String[] pair : reportAndLegacy) {
            assertEquals(printable(pair[0]), decodeAll(pair[1]), decodeAll(pair[0]));
        }
    }

    @Test
    public void win32ReportsWithoutAKeyStrokeAreConsumedSilently() throws Exception {
        KeyStroke x = new KeyStroke('x', false, false);
        String[] reports = {
                "\u001b[88;45;120;0;0;1_",   // x released
                "\u001b[16;42;0;1;16;1_",    // Shift pressed alone
                "\u001b[17;29;0;1;8;1_",     // Ctrl pressed alone
                "\u001b[18;56;0;1;2;1_",     // Alt pressed alone
                "\u001b[20;58;0;1;128;1_",   // Caps Lock
                "\u001b[91;91;0;1;256;1_",   // Windows key
                "\u001b[222;40;0;1;0;1_",    // a dead key, waiting for the next letter
                "\u001b[1;2;3;4;5;6;7_",     // too many fields
        };
        for (String report : reports) {
            assertEquals(printable(report), List.of(x), decodeAll(report + "x"));
        }
        InputDecoder decoder = new InputDecoder(new StringReader(String.join("", reports)));
        decoder.addProfile(new DefaultKeyDecodingProfile());
        assertEquals(KeyType.EOF, decoder.getNextCharacter(false).getKeyType());
    }

    @Test
    public void win32AltKeypadCodeTypesItsCharacter() throws Exception {
        // Alt+0261: Alt down, keypad 0 2 6 1 down and up, then Alt up carrying the character
        String altCode = "\u001b[18;56;0;1;2;1_"
                + "\u001b[96;82;0;1;34;1_\u001b[96;82;0;0;34;1_"
                + "\u001b[98;80;0;1;34;1_\u001b[98;80;0;0;34;1_"
                + "\u001b[102;77;0;1;34;1_\u001b[102;77;0;0;34;1_"
                + "\u001b[97;79;0;1;34;1_\u001b[97;79;0;0;34;1_"
                + "\u001b[18;56;261;0;32;1_";
        assertEquals(List.of(new KeyStroke('ą', false, false)), decodeAll(altCode));
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

    private static List<KeyStroke> decodeAll(String sequence) throws Exception {
        InputDecoder decoder = new InputDecoder(new StringReader(sequence));
        decoder.addProfile(new DefaultKeyDecodingProfile());
        List<KeyStroke> keys = new java.util.ArrayList<>();
        for (KeyStroke key = decoder.getNextCharacter(true);
                key.getKeyType() != KeyType.EOF;
                key = decoder.getNextCharacter(true)) {
            keys.add(key);
        }
        return keys;
    }

    private static String printable(String sequence) {
        return sequence.replace("\u001b", "ESC ");
    }

    private static void assertKey(
            KeyStroke key, KeyType type, boolean ctrl, boolean alt, boolean shift) {
        assertEquals(type, key.getKeyType());
        assertEquals(ctrl, key.isCtrlDown());
        assertEquals(alt, key.isAltDown());
        assertEquals(shift, key.isShiftDown());
    }
}
