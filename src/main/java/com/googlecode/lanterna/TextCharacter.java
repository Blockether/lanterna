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
 * 
 * Copyright (C) 2010-2024 Martin Berglund
 */
package com.googlecode.lanterna;

import java.io.Serializable;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Represents a single character with additional metadata such as colors and modifiers. This class is immutable and
 * cannot be modified after creation.
 * @author Martin
 */
public class TextCharacter implements Serializable {
    private static EnumSet<SGR> toEnumSet(SGR... modifiers) {
        if(modifiers.length == 0) {
            return EnumSet.noneOf(SGR.class);
        }
        else {
            return EnumSet.copyOf(Arrays.asList(modifiers));
        }
    }

    public static final TextCharacter DEFAULT_CHARACTER = new TextCharacter(' ', TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);

    public static TextCharacter[] fromCharacter(char c) {
        return fromString(Character.toString(c));
    }

    public static TextCharacter[] fromString(String string) {
        return fromString(string, TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
    }

    public static TextCharacter[] fromCharacter(char c, TextColor foregroundColor, TextColor backgroundColor, SGR... modifiers) {
        return fromString(Character.toString(c), foregroundColor, backgroundColor, modifiers);
    }

    public static TextCharacter[] fromString(
            String string,
            TextColor foregroundColor,
            TextColor backgroundColor,
            SGR... modifiers) {
        return fromString(string, foregroundColor, backgroundColor, toEnumSet(modifiers));
    }

    public static TextCharacter[] fromString(
            String string,
            TextColor foregroundColor,
            TextColor backgroundColor,
            EnumSet<SGR> modifiers) {

        BreakIterator breakIterator = BreakIterator.getCharacterInstance();
        breakIterator.setText(string);
        List<TextCharacter> result = new ArrayList<>();
        for (int begin = 0, end = 0; (end = breakIterator.next()) != BreakIterator.DONE; begin = breakIterator.current()) {
            result.add(new TextCharacter(string.substring(begin, end), foregroundColor, backgroundColor, modifiers));
        }
        return result.toArray(new TextCharacter[0]);
    }

    /**
     * The "character" might not fit in a Java 16-bit char (emoji and other types) so we store it in a String
     * as of 3.1 instead.
     */
    private final String character;
    private final TextColor foregroundColor;
    private final TextColor backgroundColor;
    private final EnumSet<SGR> modifiers;  //This isn't immutable, but we should treat it as such and not expose it!

    /**
     * Creates a {@code ScreenCharacter} based on a supplied character, with default colors and no extra modifiers.
     * @param character Physical character to use
     * @deprecated Use fromCharacter instead
     */
    @Deprecated
    public TextCharacter(char character) {
        this(character, TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
    }
    
    /**
     * Copies another {@code ScreenCharacter}
     * @param character screenCharacter to copy from
     * @deprecated TextCharacters are immutable so you shouldn't need to call this
     */
    @Deprecated
    public TextCharacter(TextCharacter character) {
        this(character.getCharacterString(),
                character.getForegroundColor(), 
                character.getBackgroundColor(),
                EnumSet.copyOf(character.getModifiers()));
    }

    /**
     * Creates a new {@code ScreenCharacter} based on a physical character, color information and optional modifiers.
     * @param character Physical character to refer to
     * @param foregroundColor Foreground color the character has
     * @param backgroundColor Background color the character has
     * @param styles Optional list of modifiers to apply when drawing the character
     * @deprecated Use fromCharacter instead
     */
    @SuppressWarnings("WeakerAccess")
    @Deprecated
    public TextCharacter(
            char character,
            TextColor foregroundColor,
            TextColor backgroundColor,
            SGR... styles) {
        
        this(character, 
                foregroundColor, 
                backgroundColor, 
                toEnumSet(styles));
    }

    /**
     * Creates a new {@code ScreenCharacter} based on a physical character, color information and a set of modifiers.
     * @param character Physical character to refer to
     * @param foregroundColor Foreground color the character has
     * @param backgroundColor Background color the character has
     * @param modifiers Set of modifiers to apply when drawing the character
     * @deprecated Use fromCharacter instead
     */
    @Deprecated
    public TextCharacter(
            char character,
            TextColor foregroundColor,
            TextColor backgroundColor,
            EnumSet<SGR> modifiers) {
        this(Character.toString(character), foregroundColor, backgroundColor, modifiers);
    }

    /**
     * Creates a new {@code ScreenCharacter} based on a physical character, color information and a set of modifiers.
     * @param character Physical character to refer to
     * @param foregroundColor Foreground color the character has
     * @param backgroundColor Background color the character has
     * @param modifiers Set of modifiers to apply when drawing the character
     */
    private TextCharacter(
            String character,
            TextColor foregroundColor,
            TextColor backgroundColor,
            EnumSet<SGR> modifiers) {

        if (character.isEmpty()) {
            throw new IllegalArgumentException("Cannot create TextCharacter from an empty string");
        }
        validateSingleCharacter(character);

        char firstCharacter = character.charAt(0);

        // vis fork: SUBSTITUTE control characters (C0 0x00-0x1F + DEL 0x7F,
        // per TerminalTextUtils.isControlCharacter), except TAB, with a space
        // instead of throwing. Upstream threw IllegalArgumentException here,
        // which let a single stray byte from dirty upstream text — e.g. a
        // 0x7F (DEL) in scraped web content — crash a caller's render thread
        // EVERY frame and freeze the whole TUI (vis session efa3371c). A
        // space is harmless, width 1, and no caller can be taken down by one
        // bad byte again. TAB is preserved for backward-compatibility.
        if(TerminalTextUtils.isControlCharacter(firstCharacter) && firstCharacter != '\t') {
            character = " ";
            firstCharacter = ' ';
        }

        // intern the string so we don't waste more memory than necessary
        this.character = character.intern();

        if(foregroundColor == null) {
            foregroundColor = TextColor.ANSI.DEFAULT;
        }
        if(backgroundColor == null) {
            backgroundColor = TextColor.ANSI.DEFAULT;
        }

        this.foregroundColor = foregroundColor;
        this.backgroundColor = backgroundColor;
        this.modifiers = EnumSet.copyOf(modifiers);
    }

    private void validateSingleCharacter(String character) {
        BreakIterator breakIterator = BreakIterator.getCharacterInstance();
        breakIterator.setText(character);
        String firstCharacter = null;
        for (int begin = 0, end = 0; (end = breakIterator.next()) != BreakIterator.DONE; begin = breakIterator.current()) {
            if (firstCharacter == null) {
                firstCharacter = character.substring(begin, end);
            }
            else {
                throw new IllegalArgumentException("Invalid String for TextCharacter, can only have one logical character");
            }
        }
    }

    public boolean is(char otherCharacter) {
        return otherCharacter == character.charAt(0) && character.length() == 1;
    }

    /**
     * The actual character this TextCharacter represents
     * @return character of the TextCharacter
     * @deprecated This won't work with advanced characters like emoji
     */
    @Deprecated
    public char getCharacter() {
        return character.charAt(0);
    }

    /**
     * Returns the character this TextCharacter represents as a String. This is not returning a char
     * @return
     */
    public String getCharacterString() {
        return character;
    }


    /**
     * Foreground color specified for this TextCharacter
     * @return Foreground color of this TextCharacter
     */
    public TextColor getForegroundColor() {
        return foregroundColor;
    }

    /**
     * Background color specified for this TextCharacter
     * @return Background color of this TextCharacter
     */
    public TextColor getBackgroundColor() {
        return backgroundColor;
    }

    /**
     * Returns a set of all active modifiers on this TextCharacter
     * @return Set of active SGR codes
     */
    public EnumSet<SGR> getModifiers() {
        return EnumSet.copyOf(modifiers);
    }

    /**
     * Returns true if this TextCharacter has the bold modifier active
     * @return {@code true} if this TextCharacter has the bold modifier active
     */
    public boolean isBold() {
        return modifiers.contains(SGR.BOLD);
    }

    /**
     * Returns true if this TextCharacter has the reverse modifier active
     * @return {@code true} if this TextCharacter has the reverse modifier active
     */
    public boolean isReversed() {
        return modifiers.contains(SGR.REVERSE);
    }

    /**
     * Returns true if this TextCharacter has the underline modifier active
     * @return {@code true} if this TextCharacter has the underline modifier active
     */
    public boolean isUnderlined() {
        return modifiers.contains(SGR.UNDERLINE);
    }

    /**
     * Returns true if this TextCharacter has the blink modifier active
     * @return {@code true} if this TextCharacter has the blink modifier active
     */
    public boolean isBlinking() {
        return modifiers.contains(SGR.BLINK);
    }

    /**
     * Returns true if this TextCharacter has the bordered modifier active
     * @return {@code true} if this TextCharacter has the bordered modifier active
     */
    public boolean isBordered() {
        return modifiers.contains(SGR.BORDERED);
    }

    /**
     * Returns true if this TextCharacter has the crossed-out modifier active
     * @return {@code true} if this TextCharacter has the crossed-out modifier active
     */
    public boolean isCrossedOut() {
        return modifiers.contains(SGR.CROSSED_OUT);
    }

    /**
     * Returns true if this TextCharacter has the italic modifier active
     * @return {@code true} if this TextCharacter has the italic modifier active
     */
    public boolean isItalic() {
        return modifiers.contains(SGR.ITALIC);
    }

    /**
     * Returns a new TextCharacter with the same colors and modifiers but a different underlying character
     * @param character Character the copy should have
     * @return Copy of this TextCharacter with different underlying character
     */
    @SuppressWarnings("SameParameterValue")
    public TextCharacter withCharacter(char character) {
        if(this.character.equals(Character.toString(character))) {
            return this;
        }
        return new TextCharacter(character, foregroundColor, backgroundColor, modifiers);
    }

    /**
     * Returns a copy of this TextCharacter with a specified foreground color
     * @param foregroundColor Foreground color the copy should have
     * @return Copy of the TextCharacter with a different foreground color
     */
    public TextCharacter withForegroundColor(TextColor foregroundColor) {
        if(this.foregroundColor == foregroundColor || this.foregroundColor.equals(foregroundColor)) {
            return this;
        }
        return new TextCharacter(character, foregroundColor, backgroundColor, modifiers);
    }

    /**
     * Returns a copy of this TextCharacter with a specified background color
     * @param backgroundColor Background color the copy should have
     * @return Copy of the TextCharacter with a different background color
     */
    public TextCharacter withBackgroundColor(TextColor backgroundColor) {
        if(this.backgroundColor == backgroundColor || this.backgroundColor.equals(backgroundColor)) {
            return this;
        }
        return new TextCharacter(character, foregroundColor, backgroundColor, modifiers);
    }

    /**
     * Returns a copy of this TextCharacter with specified list of SGR modifiers. None of the currently active SGR codes
     * will be carried over to the copy, only those in the passed in value.
     * @param modifiers SGR modifiers the copy should have
     * @return Copy of the TextCharacter with a different set of SGR modifiers
     */
    public TextCharacter withModifiers(Collection<SGR> modifiers) {
        EnumSet<SGR> newSet = EnumSet.copyOf(modifiers);
        if(modifiers.equals(newSet)) {
            return this;
        }
        return new TextCharacter(character, foregroundColor, backgroundColor, newSet);
    }

    /**
     * Returns a copy of this TextCharacter with an additional SGR modifier. All of the currently active SGR codes
     * will be carried over to the copy, in addition to the one specified.
     * @param modifier SGR modifiers the copy should have in additional to all currently present
     * @return Copy of the TextCharacter with a new SGR modifier
     */
    public TextCharacter withModifier(SGR modifier) {
        if(modifiers.contains(modifier)) {
            return this;
        }
        EnumSet<SGR> newSet = EnumSet.copyOf(this.modifiers);
        newSet.add(modifier);
        return new TextCharacter(character, foregroundColor, backgroundColor, newSet);
    }

    /**
     * Returns a copy of this TextCharacter with an SGR modifier removed. All of the currently active SGR codes
     * will be carried over to the copy, except for the one specified. If the current TextCharacter doesn't have the
     * SGR specified, it will return itself.
     * @param modifier SGR modifiers the copy should not have
     * @return Copy of the TextCharacter without the SGR modifier
     */
    public TextCharacter withoutModifier(SGR modifier) {
        if(!modifiers.contains(modifier)) {
            return this;
        }
        EnumSet<SGR> newSet = EnumSet.copyOf(this.modifiers);
        newSet.remove(modifier);
        return new TextCharacter(character, foregroundColor, backgroundColor, newSet);
    }

    public boolean isDoubleWidth() {
        // TODO: make this better to work properly with emoji and other complicated "characters"
        //
        // Variation Selector-15 (U+FE0E) explicitly asks for text presentation,
        // so keep it single-width even though it makes the Java String longer
        // than one char.
        if (containsVariationSelector15(character)) {
            return false;
        }
        // Variation Selector-16 (U+FE0F) requests EMOJI presentation, which the
        // target terminals (iTerm2 &c.) paint as TWO columns — ⚠️ ❤️ ☑️ 🏷️ …
        // Count it double so Lanterna's cursor advances in step with the
        // terminal; otherwise the row undercounts and the following text /
        // border / scrollbar jams (the original "EMOTIKON|" tooth). This is
        // INDEPENDENT of the bare base glyph: a BARE ⚠ / ● / ▶ (no VS-16) is
        // text/geometric presentation and stays ONE column (see
        // TerminalTextUtils.isCharDoubleWidth) — only the VS-16 form widens.
        // (VS-15 above is always narrow: it explicitly asks for text.)
        if (containsVariationSelector16(character)) {
            if (!APPLE_TERMINAL_WIDTHS) {
                return true;
            }
            // Apple Terminal.app IGNORES the VS-16 emoji-presentation request and
            // paints the base glyph at its text-presentation width. For a BMP base
            // (⚠️ ✔️ ☑️ ▶️ ⏺️ ❤️ …) that is the base char's intrinsic width — narrow
            // unless the base itself is emoji-presentation (⭐️) or CJK/EAW-wide.
            // Astral-base sequences (🏷️ …) fall through to the shared logic below,
            // which treats supplementary code points as wide.
            char appleBase = character.charAt(0);
            if (!Character.isHighSurrogate(appleBase)) {
                return TerminalTextUtils.isCharDoubleWidth(appleBase)
                        || isCharEmojiPresentation(appleBase);
            }
        }
        // East-Asian *Ambiguous* width (Unicode EAW=A). These code points
        // render as ONE column in Western/default terminals but TWO in
        // terminals running ambiguous-wide (iTerm2 "treat ambiguous-width
        // as double", CJK locales, tmux cjkwidth).
        //
        // DEFAULT = NARROW. The overwhelming majority of modern terminals
        // (Terminal.app, iTerm2 default, kitty, alacritty, wezterm, ghostty)
        // render EAW=A as a single column. Forcing them double-width makes
        // Lanterna over-advance the cursor on ordinary prose (em dash,
        // ellipsis, curly quotes, bullet, middle dot ...), so glyphs paint
        // one column short and the row bleeds into / leaves stale cells in
        // the right gutter / borders -- the scroll-redraw artifacts.
        //
        // Terminals actually configured for ambiguous-wide can opt back in
        // with `-Dlanterna.eastAsianAmbiguousWide=true`.
        //
        // Restricted to length()==1: graphemes carrying VS-15/VS-16 were
        // already resolved by the guards above; multi-char graphemes are
        // handled by the emoji branch below.
        if (EAW_AMBIGUOUS_WIDE
                && character.length() == 1
                && isCharEastAsianAmbiguous(character.charAt(0))) {
            return true;
        }
        return TerminalTextUtils.isCharDoubleWidth(character.charAt(0)) ||
                isCharEmojiPresentation(character.charAt(0)) ||
                isEmoji(character) ||
                // If the character takes up more than one char, assume it's double width (unless thai)
                (character.length() > 1 && !TerminalTextUtils.isCharThai(character.charAt(0)));
    }

    /**
     * True when {@code s} contains the Variation Selector-15 (U+FE0E)
     * codepoint.
     */
    private static boolean containsVariationSelector15(String s) {
        if (s.length() < 2) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\uFE0E') {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code s} contains the Variation Selector-16 (U+FE0F)
     * codepoint.
     */
    private static boolean containsVariationSelector16(String s) {
        if (s.length() < 2) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\uFE0F') {
                return true;
            }
        }
        return false;
    }

    private static boolean isEmoji(final String s) {
        // This is really hard to do properly and would require an emoji library as a dependency, so here's a hack that
        // basically assumes anything NOT a regular latin1/CJK/thai character is an emoji
        char firstCharacter = s.charAt(0);
        return s.length() > 1 ||
                !(TerminalTextUtils.isCharCJK(firstCharacter) ||
                        TerminalTextUtils.isPrintableCharacter(firstCharacter) ||
                        TerminalTextUtils.isCharThai(firstCharacter) ||
                        TerminalTextUtils.isCharCJK(firstCharacter) ||
                        TerminalTextUtils.isControlCharacter(firstCharacter));
    }

    /**
     * BMP code points whose Unicode property `Emoji_Presentation=Yes` &mdash;
     * i.e. the character defaults to emoji presentation in modern terminals
     * and should be allocated TWO terminal columns. Without this check,
     * single-`char` BMP emoji like ✅ (U+2705 WHITE HEAVY CHECK MARK),
     * ⭐ (U+2B50 WHITE MEDIUM STAR), or ⚡ (U+26A1 HIGH VOLTAGE) fall
     * through `isEmoji` (which only catches multi-`char` graphemes) AND
     * through `isCharCJK` (they're in Misc Symbols / Dingbats blocks, not
     * CJK), so {@code isDoubleWidth()} returned {@code false} and the
     * grid math under-counted them by one column. Visible symptom in any
     * markdown table that contained ✅ etc.: every cell after the emoji
     * shifted left by one column, the closing `┃` overdrew cell text,
     * and the row read as broken.
     *
     * The list mirrors Unicode 15.1's `emoji-data.txt` filtered to BMP
     * code points with `Emoji_Presentation=Yes`. Code points with
     * `Emoji=Yes` but `Emoji_Presentation=No` (❤ U+2764 HEAVY BLACK HEART,
     * ☀ U+2600 BLACK SUN WITH RAYS, etc.) are intentionally NOT included
     * here &mdash; they default to text presentation (one column) and
     * become wide only when followed by VS-16 (U+FE0F), which lanterna
     * already handles via the `character.length() &gt; 1` branch above.
     *
     * Range bounds checked first to keep the common path (ASCII / CJK /
     * non-emoji symbols) at one comparison and one branch.
     */
    private static boolean isCharEmojiPresentation(char c) {
        if (c < 0x231A || c > 0x2B55) {
            return false;
        }
        return (c >= 0x231A && c <= 0x231B)        // ⌚..⌛ watch, hourglass done
                || (c >= 0x23E9 && c <= 0x23EC)    // ⏩..⏬ fast-forward..fast down
                || (c == 0x23F0)                   // ⏰ alarm clock
                || (c == 0x23F3)                   // ⏳ hourglass not done
                || (c >= 0x25FD && c <= 0x25FE)    // ◽..◾ medium-small white/black square
                || (c >= 0x2614 && c <= 0x2615)    // ☔..☕ umbrella with rain, hot beverage
                || (c >= 0x2648 && c <= 0x2653)    // ♈..♓ zodiac signs Aries..Pisces
                || (c == 0x267F)                   // ♿ wheelchair symbol
                || (c == 0x2693)                   // ⚓ anchor
                || (c == 0x26A1)                   // ⚡ high voltage
                || (c >= 0x26AA && c <= 0x26AB)    // ⚪..⚫ white/black circle
                || (c >= 0x26BD && c <= 0x26BE)    // ⚽..⚾ soccer ball, baseball
                || (c >= 0x26C4 && c <= 0x26C5)    // ⛄..⛅ snowman, sun behind cloud
                || (c == 0x26CE)                   // ⛎ Ophiuchus
                || (c == 0x26D4)                   // ⛔ no entry
                || (c == 0x26EA)                   // ⛪ church
                || (c >= 0x26F2 && c <= 0x26F3)    // ⛲..⛳ fountain, flag in hole
                || (c == 0x26F5)                   // ⛵ sailboat
                || (c == 0x26FA)                   // ⛺ tent
                || (c == 0x26FD)                   // ⛽ fuel pump
                || (c == 0x2705)                   // ✅ check mark button
                || (c >= 0x270A && c <= 0x270B)    // ✊..✋ raised fist, raised hand
                || (c == 0x2728)                   // ✨ sparkles
                || (c == 0x274C)                   // ❌ cross mark
                || (c == 0x274E)                   // ❎ cross mark button
                || (c >= 0x2753 && c <= 0x2755)    // ❓..❕ question, exclamation marks
                || (c == 0x2757)                   // ❗ heavy exclamation
                || (c >= 0x2795 && c <= 0x2797)    // ➕..➗ heavy plus, minus, division
                || (c == 0x27B0)                   // ➰ curly loop
                || (c == 0x27BF)                   // ➿ double curly loop
                || (c >= 0x2B1B && c <= 0x2B1C)    // ⬛..⬜ large black/white square
                || (c == 0x2B50)                   // ⭐ white medium star
                || (c == 0x2B55);                  // ⭕ hollow red circle
    }

    /**
     * BMP code points with East-Asian-Width property `Ambiguous` (EAW=A)
     * that should be allocated TWO terminal columns when Lanterna targets
     * an ambiguous-wide terminal. Mirrors the Unicode `EastAsianWidth.txt`
     * `A` rows, but DELIBERATELY CURATED to the text subset:
     *
     *   - Latin-1 punctuation/letters (incl. \u00b7 MIDDLE DOT),
     *     Latin-Extended-A, IPA / spacing modifiers
     *   - Greek + Coptic, Cyrillic
     *   - General Punctuation (\u2010 \u2013-\u2016 dashes, \u2018-\u2019 /
     *     \u201c-\u201d quotes, \u2020-\u2022 dagger/bullet, \u2024-\u2027 /
     *     \u2026 ellipsis, \u2030 \u2032-\u2033 etc.)
     *   - super/subscripts, \u20ac EURO, letterlike (\u2116 \u2122 \u2126 ...),
     *     number forms / Roman numerals, common math operators
     *
     * EXCLUDED ON PURPOSE -- these are EAW=A too, but Vis paints them as
     * structural chrome and the target terminal renders them NARROW, so
     * widening them here would detonate the layout:
     *
     *   - Arrows U+2190..U+21FF      (footer hints up/down arrows)
     *   - Enclosed alphanumerics U+2460..U+24FF
     *   - Box drawing U+2500..U+257F (table borders, scrollbar \u2502)
     *   - Block elements U+2580..U+259F (\u2588 thumb, \u258e gutter)
     *   - Geometric shapes U+25A0..U+25FF (\u25b6 \u25bc markers)
     *   - Misc symbols / Dingbats U+2600.. (handled by the emoji table)
     *
     * The upper bound 0x2312 cleanly drops everything from the enclosed /
     * box-drawing blocks onward; the arrow block (below 0x2312) is skipped
     * by simply having no range cover it.
     */
    private static boolean isCharEastAsianAmbiguous(char c) {
        // O(1) membership test against a precomputed bitset; no branch
        // chain. The `c < 0x00A1` fast-reject still wins for ASCII (the
        // overwhelming common path) before BitSet#get ever runs.
        return c >= 0x00A1 && c <= EAW_AMBIGUOUS_MAX && EAW_AMBIGUOUS.get(c);
    }

    /**
     * When {@code true}, curated East-Asian *Ambiguous* (EAW=A) code points
     * are treated as DOUBLE width. Default {@code false} (narrow) because
     * the overwhelming majority of modern terminals render EAW=A as a
     * single column; forcing them wide over-advances the cursor on normal
     * prose and produces scroll-redraw artifacts. Opt back in for genuine
     * ambiguous-wide terminals with {@code -Dlanterna.eastAsianAmbiguousWide=true}.
     */
    // DEFAULT OFF. EAW=A renders as ONE column on most modern terminals;
    // widening ordinary prose (em dash, ellipsis, bullet, middle dot, curly
    // quotes …) over-advances the cursor so glyphs paint a column short and the
    // row leaves stale cells -- the scroll-redraw artifacts. The header status
    // DOTS (● ○ …) are NOT handled here: their font gives them emoji
    // presentation (genuinely 2 cols), so they're force-wide in
    // TerminalTextUtils.isCharDoubleWidth regardless of this flag. Opt the whole
    // EAW=A class into wide per-terminal with -Dlanterna.eastAsianAmbiguousWide=true.
    private static final boolean EAW_AMBIGUOUS_WIDE =
            Boolean.getBoolean("lanterna.eastAsianAmbiguousWide");

    // ── Apple Terminal.app width quirk ──────────────────────────────────────
    // Apple Terminal.app renders VS-16 (U+FE0F) emoji-PRESENTATION sequences at
    // the base glyph's TEXT-presentation width — ONE column — ignoring the emoji
    // selector. iTerm2 / Ghostty / Kitty / WezTerm / tmux paint them as TWO
    // columns (see the VS-16 branch in isDoubleWidth). Trusting the two-column
    // model on Terminal.app over-advances the cursor after every ⚠️ ✔️ ☑️ ▶️ ⏺️,
    // so following text merges into the icon and the stale trailing half-cell
    // shows as a scroll artifact.
    //
    // Auto-detected from TERM_PROGRAM; NOT applied inside tmux (TMUX set), where
    // tmux is the width authority and agrees with the two-column model. Force
    // either way with -Dlanterna.appleTerminalWidths=true|false.
    private static volatile boolean APPLE_TERMINAL_WIDTHS = detectAppleTerminalWidths();

    private static boolean detectAppleTerminalWidths() {
        String override = System.getProperty("lanterna.appleTerminalWidths");
        if (override != null) {
            return Boolean.parseBoolean(override);
        }
        return "Apple_Terminal".equals(System.getenv("TERM_PROGRAM"))
                && System.getenv("TMUX") == null;
    }

    /** Whether Apple Terminal.app width rules are active (VS-16 narrowed). */
    public static boolean appleTerminalWidths() {
        return APPLE_TERMINAL_WIDTHS;
    }

    /** Override the Apple Terminal.app width mode (for tests / embedders). */
    public static void setAppleTerminalWidths(boolean value) {
        APPLE_TERMINAL_WIDTHS = value;
    }

    /** Inclusive range bounds (lo, hi pairs) of the curated EAW=A set. */
    private static final int[] EAW_AMBIGUOUS_RANGES = {
        // Latin-1 Supplement
        0x00A1, 0x00A1, 0x00A4, 0x00A4, 0x00A7, 0x00A8, 0x00AA, 0x00AA,
        0x00AD, 0x00AE, 0x00B0, 0x00B4, 0x00B6, 0x00BA, 0x00BC, 0x00BF,
        0x00C6, 0x00C6, 0x00D0, 0x00D0, 0x00D7, 0x00D8, 0x00DE, 0x00E1,
        0x00E6, 0x00E6, 0x00E8, 0x00EA, 0x00EC, 0x00ED, 0x00F0, 0x00F0,
        0x00F2, 0x00F3, 0x00F7, 0x00FA, 0x00FC, 0x00FC, 0x00FE, 0x00FE,
        // Latin Extended-A
        0x0101, 0x0101, 0x0111, 0x0111, 0x0113, 0x0113, 0x011B, 0x011B,
        0x0126, 0x0127, 0x012B, 0x012B, 0x0131, 0x0133, 0x0138, 0x0138,
        0x013F, 0x0142, 0x0144, 0x0144, 0x0148, 0x014B, 0x014D, 0x014D,
        0x0152, 0x0153, 0x0166, 0x0167, 0x016B, 0x016B,
        // IPA / spacing modifier letters
        0x01CE, 0x01CE, 0x01D0, 0x01D0, 0x01D2, 0x01D2, 0x01D4, 0x01D4,
        0x01D6, 0x01D6, 0x01D8, 0x01D8, 0x01DA, 0x01DA, 0x01DC, 0x01DC,
        0x0251, 0x0251, 0x0261, 0x0261, 0x02C4, 0x02C4, 0x02C7, 0x02C7,
        0x02C9, 0x02CB, 0x02CD, 0x02CD, 0x02D0, 0x02D0, 0x02D8, 0x02DB,
        0x02DD, 0x02DD, 0x02DF, 0x02DF,
        // Greek and Coptic
        0x0391, 0x03A1, 0x03A3, 0x03A9, 0x03B1, 0x03C1, 0x03C3, 0x03C9,
        // Cyrillic
        0x0401, 0x0401, 0x0410, 0x044F, 0x0451, 0x0451,
        // General Punctuation
        0x2010, 0x2010, 0x2013, 0x2016, 0x2018, 0x2019, 0x201C, 0x201D,
        0x2020, 0x2022, 0x2024, 0x2027, 0x2030, 0x2030, 0x2032, 0x2033,
        0x2035, 0x2035, 0x203B, 0x203B, 0x203E, 0x203E,
        // Super/subscripts, currency
        0x2074, 0x2074, 0x207F, 0x207F, 0x2081, 0x2084, 0x20AC, 0x20AC,
        // Letterlike symbols
        0x2103, 0x2103, 0x2105, 0x2105, 0x2109, 0x2109, 0x2113, 0x2113,
        0x2116, 0x2116, 0x2121, 0x2122, 0x2126, 0x2126, 0x212B, 0x212B,
        // Number forms / Roman numerals
        0x2153, 0x2154, 0x215B, 0x215E, 0x2160, 0x216B, 0x2170, 0x2179,
        0x2189, 0x2189,
        // Mathematical operators (arrows U+2190..U+21FF deliberately skipped)
        0x2200, 0x2200, 0x2202, 0x2203, 0x2207, 0x2208, 0x220B, 0x220B,
        0x220F, 0x220F, 0x2211, 0x2211, 0x2215, 0x2215, 0x221A, 0x221A,
        0x221D, 0x2220, 0x2223, 0x2223, 0x2225, 0x2225, 0x2227, 0x222C,
        0x222E, 0x222E, 0x2234, 0x2237, 0x223C, 0x223D, 0x2248, 0x2248,
        0x224C, 0x224C, 0x2252, 0x2252, 0x2260, 0x2261, 0x2264, 0x2267,
        0x226A, 0x226B, 0x226E, 0x226F, 0x2282, 0x2283, 0x2286, 0x2287,
        0x2295, 0x2295, 0x2299, 0x2299, 0x22A5, 0x22A5, 0x22BF, 0x22BF,
        0x2312, 0x2312,
    };

    private static final int EAW_AMBIGUOUS_MAX = 0x2312;

    /** Built once at class load from {@link #EAW_AMBIGUOUS_RANGES}. */
    private static final BitSet EAW_AMBIGUOUS = buildAmbiguousBitSet();

    private static BitSet buildAmbiguousBitSet() {
        BitSet bits = new BitSet(EAW_AMBIGUOUS_MAX + 1);
        for (int i = 0; i < EAW_AMBIGUOUS_RANGES.length; i += 2) {
            bits.set(EAW_AMBIGUOUS_RANGES[i], EAW_AMBIGUOUS_RANGES[i + 1] + 1);
        }
        return bits;
    }

    @SuppressWarnings("SimplifiableIfStatement")
    @Override
    public boolean equals(Object obj) {
        if(obj == null) {
            return false;
        }
        if(getClass() != obj.getClass()) {
            return false;
        }
        final TextCharacter other = (TextCharacter) obj;
        if(!Objects.equals(this.character, other.character)) {
            return false;
        }
        if(!Objects.equals(this.foregroundColor, other.foregroundColor)) {
            return false;
        }
        if(!Objects.equals(this.backgroundColor, other.backgroundColor)) {
            return false;
        }
        return Objects.equals(this.modifiers, other.modifiers);
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 37 * hash + this.character.hashCode();
        hash = 37 * hash + (this.foregroundColor != null ? this.foregroundColor.hashCode() : 0);
        hash = 37 * hash + (this.backgroundColor != null ? this.backgroundColor.hashCode() : 0);
        hash = 37 * hash + (this.modifiers != null ? this.modifiers.hashCode() : 0);
        return hash;
    }

    @Override
    public String toString() {
        return "TextCharacter{" + "character=" + character + ", foregroundColor=" + foregroundColor + ", backgroundColor=" + backgroundColor + ", modifiers=" + modifiers + '}';
    }
}
