package com.googlecode.lanterna;

import org.junit.Test;

import static org.junit.Assert.*;

public class TextCharacterTest {

    @Test
    public void fromString() {
        assertEquals(6, TextCharacter.fromString("Hello!").length);
        assertEquals(5, TextCharacter.fromString("あいうえお").length);
        assertEquals(1, TextCharacter.fromString("\uD83C\uDF55").length);
        assertEquals(1, TextCharacter.fromString("\uD83E\uDD7A").length);
        // This is a weird compound character that should be printed as one, but is actually two char:s
        assertEquals(1, TextCharacter.fromString("บุ").length);

        // This should be one but Java is lagging behind a bit on Unicode emoji and interprets it as 2
        //assertEquals(1, TextCharacter.fromString("\uD83D\uDC4D\uD83C\uDFFF").length);
    }

    @Test
    public void controlCharactersAreSubstitutedNotThrown() {
        // vis fork regression (session efa3371c): a control char must NEVER
        // throw — upstream did, and a single 0x7F (DEL) from scraped web
        // content crashed the render thread every frame. They degrade to a
        // space (width 1) so dirty upstream text can't take a caller down.
        TextCharacter del = TextCharacter.fromString("\u007F")[0];
        assertEquals(" ", del.getCharacterString());

        TextCharacter c0 = TextCharacter.fromString("\u0007")[0]; // BEL
        assertEquals(" ", c0.getCharacterString());

        // A control char embedded in real text leaves the rest intact.
        TextCharacter[] cells = TextCharacter.fromString("ab\u007Fcd");
        assertEquals(5, cells.length);
        assertEquals(" ", cells[2].getCharacterString());
        assertEquals("a", cells[0].getCharacterString());
        assertEquals("d", cells[4].getCharacterString());

        // TAB stays special (backward-compat: not substituted).
        assertEquals("\t", TextCharacter.fromString("\t")[0].getCharacterString());
    }

    @Test
    public void emojisAreDoubleWidth() {
        assertTrue(TextCharacter.fromString("\uD83D\uDC69\uD83C\uDFFD")[0].isDoubleWidth());
        assertTrue(TextCharacter.fromString("\uD83C\uDFE9")[0].isDoubleWidth());
        assertTrue(TextCharacter.fromString("\uD83D\uDC96")[0].isDoubleWidth());
        assertTrue(TextCharacter.fromString("❤\uFE0F")[0].isDoubleWidth()); // base U+2764 + VS-16 -> WIDE (emoji presentation)
        assertTrue(TextCharacter.fromString("\uD83C\uDFF7\uFE0F")[0].isDoubleWidth()); // 🏷️ terminal-matching override
        assertTrue(TextCharacter.fromString("\u2611\uFE0F")[0].isDoubleWidth()); // base U+2611 + VS-16 -> WIDE (emoji presentation)
        assertTrue(TextCharacter.fromString("\uD83D\uDE0A")[0].isDoubleWidth());
        assertTrue(TextCharacter.fromString("\uD83D\uDC40")[0].isDoubleWidth());
        assertFalse(TextCharacter.fromString("\u2611\uFE0E")[0].isDoubleWidth()); // ☑︎ text presentation
        assertFalse(TextCharacter.fromString("M")[0].isDoubleWidth());  // Not emoji
        assertTrue(TextCharacter.fromString("⚠️")[0].isDoubleWidth()); // U+26A0 + VS-16 -> WIDE (emoji presentation); bare ⚠ stays narrow below
        assertFalse(TextCharacter.fromString("⚠")[0].isDoubleWidth());       // bare U+26A0 WARNING SIGN -> NARROW (standard width)
        // Media-control triangles (U+25B6 / U+25C0) and disclosure
        // chevrons (U+25B8 / U+25BE) are text-presentation: ONE column.
        assertFalse(TextCharacter.fromString("▶")[0].isDoubleWidth());  // media triangle U+25B6 -> NARROW (standard width)
        assertFalse(TextCharacter.fromString("◀")[0].isDoubleWidth());  // media triangle U+25C0 -> NARROW (standard width)
        // The SMALL disclosure-chevron triangles are EAW=N + non-emoji: narrow.
        assertFalse(TextCharacter.fromString("▸")[0].isDoubleWidth()); // ▸
        assertFalse(TextCharacter.fromString("▾")[0].isDoubleWidth()); // ▾
    }

    @Test
    public void eastAsianAmbiguousAreSingleWidthByDefault() {
        // DEFAULT = NARROW: EAW=A is one column on modern terminals, so prose
        // must NOT over-advance. Opt in: -Dlanterna.eastAsianAmbiguousWide=true.
        assertFalse(TextCharacter.fromString("\u00B7")[0].isDoubleWidth()); // · MIDDLE DOT (exa output separator)
        assertFalse(TextCharacter.fromString("\u2014")[0].isDoubleWidth()); // — EM DASH
        assertFalse(TextCharacter.fromString("\u2013")[0].isDoubleWidth()); // – EN DASH
        assertFalse(TextCharacter.fromString("\u2026")[0].isDoubleWidth()); // … HORIZONTAL ELLIPSIS
        assertFalse(TextCharacter.fromString("\u2022")[0].isDoubleWidth()); // • BULLET
        assertFalse(TextCharacter.fromString("\u201C")[0].isDoubleWidth()); // “ LEFT DOUBLE QUOTE
        assertFalse(TextCharacter.fromString("\u201D")[0].isDoubleWidth()); // ” RIGHT DOUBLE QUOTE
        assertFalse(TextCharacter.fromString("\u03BB")[0].isDoubleWidth()); // λ GREEK SMALL LAMBDA
        assertFalse(TextCharacter.fromString("\u00A7")[0].isDoubleWidth()); // § SECTION SIGN
        assertFalse(TextCharacter.fromString("\u2122")[0].isDoubleWidth()); // ™ TRADE MARK
        assertFalse(TextCharacter.fromString("\u0451")[0].isDoubleWidth()); // ё CYRILLIC
        // ...the circle status dots are EAW=Ambiguous geometric shapes:
        // NARROW by default (standard width), like every other EAW=A glyph above.
        assertFalse(TextCharacter.fromString("\u25CF")[0].isDoubleWidth());  // ● BLACK CIRCLE -> NARROW
        assertFalse(TextCharacter.fromString("\u25CB")[0].isDoubleWidth());  // ○ WHITE CIRCLE -> NARROW
        assertFalse(TextCharacter.fromString("\u25B8")[0].isDoubleWidth()); // chevron stays narrow
        assertFalse(TextCharacter.fromString("\u25BE")[0].isDoubleWidth()); // chevron stays narrow
    }

    @Test
    public void structuralChromeStaysSingleWidth() {
        // EAW=A too, but Vis paints these as chrome and target terminals
        // render them narrow -- widening would detonate the layout.
        assertFalse(TextCharacter.fromString("\u2502")[0].isDoubleWidth()); // │ scrollbar / table rail
        assertFalse(TextCharacter.fromString("\u2500")[0].isDoubleWidth()); // ─ horizontal rule
        assertFalse(TextCharacter.fromString("\u250C")[0].isDoubleWidth()); // ┌ corner
        assertFalse(TextCharacter.fromString("\u2588")[0].isDoubleWidth()); // █ scrollbar thumb
        assertFalse(TextCharacter.fromString("\u258E")[0].isDoubleWidth()); // ▎ gutter glyph
        assertFalse(TextCharacter.fromString("\u25B8")[0].isDoubleWidth()); // ▸ op-row marker (EAW=N chrome)
        assertFalse(TextCharacter.fromString("\u25BE")[0].isDoubleWidth()); // ▾ marker (EAW=N chrome)
        assertFalse(TextCharacter.fromString("\u2191")[0].isDoubleWidth()); // ↑ footer hint arrow
        assertFalse(TextCharacter.fromString("\u2193")[0].isDoubleWidth()); // ↓ footer hint arrow
        assertFalse(TextCharacter.fromString("A")[0].isDoubleWidth());      // plain ASCII
    }

    @Test
    public void appleTerminalNarrowsVs16Emoji() {
        // Apple Terminal.app ignores the VS-16 emoji-presentation selector and
        // paints the base glyph at its TEXT width (measured on Terminal.app v466:
        // U+26A0 U+2714 U+2611 U+25B6 U+23FA U+2764 + VS-16 = 1 cell; U+2B50 + VS-16
        // = 2; genuine emoji U+2705, U+1F4C4 = 2). Under the Apple width mode
        // isDoubleWidth() must follow the base glyph, not the VS-16 selector.
        boolean prev = TextCharacter.appleTerminalWidths();
        try {
            TextCharacter.setAppleTerminalWidths(true);
            // VS-16 on text-default BMP bases -> NARROW (base is text/geometric).
            assertFalse(TextCharacter.fromString("⚠️")[0].isDoubleWidth()); // warning
            assertFalse(TextCharacter.fromString("✔️")[0].isDoubleWidth()); // heavy check
            assertFalse(TextCharacter.fromString("☑️")[0].isDoubleWidth()); // ballot box + check
            assertFalse(TextCharacter.fromString("▶️")[0].isDoubleWidth()); // play triangle
            assertFalse(TextCharacter.fromString("⏺️")[0].isDoubleWidth()); // record circle
            assertFalse(TextCharacter.fromString("❤️")[0].isDoubleWidth()); // red heart
            // VS-16 on an emoji-presentation BMP base stays WIDE (star is 2 cells).
            assertTrue(TextCharacter.fromString("⭐️")[0].isDoubleWidth());  // star
            // VS-15 (text presentation) always narrow.
            assertFalse(TextCharacter.fromString("☑︎")[0].isDoubleWidth()); // ballot box + VS-15
            // Genuine emoji (dedicated code points, no VS-16) stay WIDE.
            assertTrue(TextCharacter.fromString("✅")[0].isDoubleWidth());        // check mark button
            assertTrue(TextCharacter.fromString("📄")[0].isDoubleWidth());  // page facing up
            assertFalse(TextCharacter.fromString("M")[0].isDoubleWidth());            // plain ASCII

            // The non-Apple contract is unchanged: VS-16 stays WIDE.
            TextCharacter.setAppleTerminalWidths(false);
            assertTrue(TextCharacter.fromString("⚠️")[0].isDoubleWidth());  // warning
            assertTrue(TextCharacter.fromString("☑️")[0].isDoubleWidth());  // ballot box + check
        } finally {
            TextCharacter.setAppleTerminalWidths(prev);
        }
    }
}
