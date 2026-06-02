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
        assertFalse(TextCharacter.fromString("❤\uFE0F")[0].isDoubleWidth()); // terminal-matching override
        assertFalse(TextCharacter.fromString("\uD83C\uDFF7\uFE0F")[0].isDoubleWidth()); // 🏷️ terminal-matching override
        assertFalse(TextCharacter.fromString("\u2611\uFE0F")[0].isDoubleWidth()); // ☑️ matches target terminals
        assertTrue(TextCharacter.fromString("\uD83D\uDE0A")[0].isDoubleWidth());
        assertTrue(TextCharacter.fromString("\uD83D\uDC40")[0].isDoubleWidth());
        assertFalse(TextCharacter.fromString("\u2611\uFE0E")[0].isDoubleWidth()); // ☑︎ text presentation
        assertFalse(TextCharacter.fromString("M")[0].isDoubleWidth());  // Not emoji
    }

    @Test
    public void eastAsianAmbiguousAreSingleWidthByDefault() {
        // DEFAULT = NARROW: modern terminals render EAW=A as one column, so
        // ordinary prose punctuation / letters must NOT over-advance. (Opt
        // into wide with -Dlanterna.eastAsianAmbiguousWide=true.)
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
        assertFalse(TextCharacter.fromString("\u25B6")[0].isDoubleWidth()); // ▶ op-row marker
        assertFalse(TextCharacter.fromString("\u25BC")[0].isDoubleWidth()); // ▼ marker
        assertFalse(TextCharacter.fromString("\u2191")[0].isDoubleWidth()); // ↑ footer hint arrow
        assertFalse(TextCharacter.fromString("\u2193")[0].isDoubleWidth()); // ↓ footer hint arrow
        assertFalse(TextCharacter.fromString("A")[0].isDoubleWidth());      // plain ASCII
    }
}
