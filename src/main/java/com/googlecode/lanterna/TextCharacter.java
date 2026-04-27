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

        // intern the string so we don't waste more memory than necessary
        this.character = character.intern();
        char firstCharacter = character.charAt(0);

        // Don't allow creating a TextCharacter containing a control character
        // For backward-compatibility, do allow tab for now
        if(TerminalTextUtils.isControlCharacter(firstCharacter) && firstCharacter != '\t') {
            throw new IllegalArgumentException("Cannot create a TextCharacter from a control character (0x" + Integer.toHexString(firstCharacter) + ")");
        }

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
        // Variation Selector-16 (U+FE0F) graphemes are deliberately
        // treated as NOT double-width. Real-world terminals (macOS
        // Terminal, iTerm2 with several common monospaced fonts, and
        // others) render emoji+VS-16 sequences at the BASE character's
        // text-presentation width — typically one column — NOT the
        // two-column emoji-presentation width that Unicode says VS-16
        // is supposed to force. Returning true here causes lanterna's
        // putString to advance the cursor by two cells while the
        // terminal only advances by one, which leaves a one-cell gap
        // and shifts every following character left by one. Symptom:
        // markdown table rows containing 🏷️ (LABEL + VS-16) drift the
        // grid one column right of the emoji. Treating these graphemes
        // as single-width keeps lanterna's cursor model in lockstep
        // with what the terminal actually paints — the user-visible
        // bug, fixed at the source.
        if (containsVariationSelector16(character)) {
            return false;
        }
        return TerminalTextUtils.isCharDoubleWidth(character.charAt(0)) ||
                isCharEmojiPresentation(character.charAt(0)) ||
                isEmoji(character) ||
                // If the character takes up more than one char, assume it's double width (unless thai)
                (character.length() > 1 && !TerminalTextUtils.isCharThai(character.charAt(0)));
    }

    /**
     * True when {@code s} contains the Variation Selector-16 (U+FE0F)
     * codepoint. Tiny scan; called once per {@link #isDoubleWidth()}
     * invocation and only matters for graphemes longer than one
     * {@code char}, where the cost is negligible compared to the
     * surrounding emoji handling.
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
