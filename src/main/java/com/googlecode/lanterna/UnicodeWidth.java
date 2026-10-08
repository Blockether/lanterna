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

import java.util.Objects;

/**
 * Terminal column widths of code points and grapheme clusters.
 * <p>
 * This class ports the width rules of OpenTUI ({@code packages/native/src/utf8.zig}) and the
 * grapheme break rules of its uucode dependency. The property tables come from Unicode 17.0.0
 * (see {@code scripts/gen_unicode_width.py}).
 */
public final class UnicodeWidth {

    /**
     * How the terminal measures a grapheme cluster. The values are the same as the OpenTUI
     * {@code WidthMethod}.
     */
    public enum WidthMethod {
        /** Sum of the code point widths, as in tmux and GNU screen. */
        WCWIDTH,
        /** Unicode grapheme cluster widths (DEC mode 2027). */
        UNICODE,
        /** Same as {@link #UNICODE}: OpenTUI buffers map this method to Unicode widths. */
        NO_ZWJ,
        /** Unicode grapheme cluster widths, where each visible extension makes the cluster wide. */
        UNICODE_WIDE
    }

    // Grapheme break classes. The order is the same as GB in scripts/gen_unicode_width.py.
    static final int GB_OTHER = 0;
    static final int GB_CONTROL = 1;
    static final int GB_PREPEND = 2;
    static final int GB_CR = 3;
    static final int GB_LF = 4;
    static final int GB_REGIONAL_INDICATOR = 5;
    static final int GB_SPACING_MARK = 6;
    static final int GB_L = 7;
    static final int GB_V = 8;
    static final int GB_T = 9;
    static final int GB_LV = 10;
    static final int GB_LVT = 11;
    static final int GB_ZWJ = 12;
    static final int GB_ZWNJ = 13;
    static final int GB_EXTENDED_PICTOGRAPHIC = 14;
    static final int GB_EMOJI_MODIFIER_BASE = 15;
    static final int GB_EMOJI_MODIFIER = 16;
    static final int GB_INCB_EXTEND = 17;
    static final int GB_INCB_LINKER = 18;
    static final int GB_INCB_CONSONANT = 19;

    // Grapheme break states of uucode (BreakState).
    static final int STATE_DEFAULT = 0;
    static final int STATE_REGIONAL_INDICATOR = 1;
    static final int STATE_EXTENDED_PICTOGRAPHIC = 2;
    static final int STATE_INCB_CONSONANT = 3;
    static final int STATE_INCB_LINKER = 4;

    private static final int GC_MN = 1;
    private static final int GC_MC = 2;
    private static final int GC_ME = 3;
    private static final int WIDE = 1 << 8;
    private static final int ZERO_IN_GRAPHEME = 1 << 9;

    private static final int ZWJ = 0x200D;
    private static final int VS16 = 0xFE0F;
    private static final int REPLACEMENT = 0xFFFD;

    /** Inclusive ranges that OpenTUI {@code eawToWidth} makes zero width. */
    private static final int[] ZERO_WIDTH_RANGES = {
        0x034F, 0x034F, 0x180B, 0x180D, 0x200B, 0x200D, 0x2060, 0x2060,
        0xFE00, 0xFE0F, 0xFEFF, 0xFEFF, 0xE0100, 0xE01EF,
    };

    private static final int[] RUN_STARTS;
    private static final short[] RUN_VALUES;

    private static volatile WidthMethod widthMethod = WidthMethod.UNICODE;

    static {
        int count = 0;
        for (String chunk : UnicodeWidthData.RUNS) {
            count += chunk.split(";").length;
        }
        RUN_STARTS = new int[count];
        RUN_VALUES = new short[count];
        int index = 0;
        for (String chunk : UnicodeWidthData.RUNS) {
            for (String run : chunk.split(";")) {
                int comma = run.indexOf(',');
                RUN_STARTS[index] = Integer.parseInt(run.substring(0, comma), 16);
                RUN_VALUES[index] = (short) Integer.parseInt(run.substring(comma + 1), 16);
                index++;
            }
        }
    }

    private UnicodeWidth() {
    }

    /**
     * Returns the width method that {@link TextCharacter} uses for new width calculations.
     * @return the active width method
     */
    public static WidthMethod getWidthMethod() {
        return widthMethod;
    }

    /**
     * Sets the width method that {@link TextCharacter} uses. Terminal capability detection
     * calls this method with the method that the terminal reports.
     * @param method the new width method
     */
    public static void setWidthMethod(WidthMethod method) {
        widthMethod = Objects.requireNonNull(method, "method");
    }

    private static int properties(int codePoint) {
        int low = 0;
        int high = RUN_STARTS.length - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (RUN_STARTS[middle] <= codePoint) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return RUN_VALUES[low];
    }

    static int graphemeBreak(int codePoint) {
        return properties(codePoint) & 0x1F;
    }

    private static int generalCategory(int codePoint) {
        return (properties(codePoint) >> 5) & 0x7;
    }

    private static boolean inRanges(int[] ranges, int codePoint) {
        for (int i = 0; i < ranges.length; i += 2) {
            if (codePoint >= ranges[i] && codePoint <= ranges[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Port of OpenTUI {@code eawToWidth}.
     * @param codePoint code point in the range 0 to 0x10FFFF
     * @return -1 for a control character, else 0, 1 or 2
     */
    static int eawToWidth(int codePoint) {
        if (codePoint == 0) {
            return 0;
        }
        if (codePoint < 32 || (codePoint >= 0x7F && codePoint < 0xA0)) {
            return -1;
        }
        int properties = properties(codePoint);
        int category = (properties >> 5) & 0x7;
        if (category == GC_MN || category == GC_MC || category == GC_ME) {
            return 0;
        }
        if (inRanges(ZERO_WIDTH_RANGES, codePoint)) {
            return 0;
        }
        return (properties & WIDE) != 0 ? 2 : 1;
    }

    /**
     * Returns the column width of one code point (OpenTUI {@code eastAsianWidth}).
     * @param codePoint code point to measure
     * @return 0, 1 or 2
     */
    public static int codePointWidth(int codePoint) {
        if (codePoint < 0 || codePoint > 0x10FFFF) {
            return 0;
        }
        return Math.max(0, eawToWidth(codePoint));
    }

    /** Port of OpenTUI {@code charWidth}, without TAB expansion. */
    private static int charWidth(int codePoint) {
        if (codePoint < 0x80) {
            return codePoint >= 32 && codePoint <= 126 ? 1 : 0;
        }
        return codePointWidth(codePoint);
    }

    /** Port of uucode {@code computeGraphemeBreak}. */
    static boolean computeGraphemeBreak(int gb1, int gb2, int[] state) {
        switch (state[0]) {
            case STATE_REGIONAL_INDICATOR:
                if (gb1 != GB_REGIONAL_INDICATOR || gb2 != GB_REGIONAL_INDICATOR) {
                    state[0] = STATE_DEFAULT;
                }
                break;
            case STATE_EXTENDED_PICTOGRAPHIC:
                if (!keepsExtendedPictographic(gb1) || !keepsExtendedPictographic(gb2)) {
                    state[0] = STATE_DEFAULT;
                }
                break;
            case STATE_INCB_CONSONANT:
            case STATE_INCB_LINKER:
                if (!keepsIndicConjunct(gb1) || !keepsIndicConjunct(gb2)) {
                    state[0] = STATE_DEFAULT;
                }
                break;
            default:
                break;
        }

        // GB3: CR x LF
        if (gb1 == GB_CR && gb2 == GB_LF) {
            return false;
        }
        // GB4 and GB5: Control
        if (isControl(gb1) || isControl(gb2)) {
            return true;
        }
        // GB6: L x (L | V | LV | LVT)
        if (gb1 == GB_L && (gb2 == GB_L || gb2 == GB_V || gb2 == GB_LV || gb2 == GB_LVT)) {
            return false;
        }
        // GB7: (LV | V) x (V | T)
        if ((gb1 == GB_LV || gb1 == GB_V) && (gb2 == GB_V || gb2 == GB_T)) {
            return false;
        }
        // GB8: (LVT | T) x T
        if ((gb1 == GB_LVT || gb1 == GB_T) && gb2 == GB_T) {
            return false;
        }
        // GB9a: SpacingMark
        if (gb2 == GB_SPACING_MARK) {
            return false;
        }
        // GB9b: Prepend
        if (gb1 == GB_PREPEND) {
            return false;
        }
        // GB9c: Indic
        if (gb1 == GB_INCB_CONSONANT) {
            if (isIndicConjunctBreakExtend(gb2)) {
                state[0] = STATE_INCB_CONSONANT;
                return false;
            }
            if (gb2 == GB_INCB_LINKER) {
                state[0] = STATE_INCB_LINKER;
                return false;
            }
        } else if (state[0] == STATE_INCB_CONSONANT) {
            if (gb2 == GB_INCB_LINKER) {
                state[0] = STATE_INCB_LINKER;
                return false;
            }
            if (isIndicConjunctBreakExtend(gb2)) {
                return false;
            }
            state[0] = STATE_DEFAULT;
        } else if (state[0] == STATE_INCB_LINKER) {
            if (gb2 == GB_INCB_LINKER || isIndicConjunctBreakExtend(gb2)) {
                return false;
            }
            // The state switch above keeps this state only when gb2 is a consonant, linker, extend or ZWJ.
            state[0] = STATE_DEFAULT;
            return false;
        }
        // GB11: Emoji ZWJ sequence and emoji modifier sequence
        if (isExtendedPictographic(gb1)) {
            if (isExtend(gb2) || gb2 == GB_ZWJ) {
                state[0] = STATE_EXTENDED_PICTOGRAPHIC;
                return false;
            }
            if (gb1 == GB_EMOJI_MODIFIER_BASE && gb2 == GB_EMOJI_MODIFIER) {
                state[0] = STATE_EXTENDED_PICTOGRAPHIC;
                return false;
            }
        } else if (state[0] == STATE_EXTENDED_PICTOGRAPHIC) {
            if ((isExtend(gb1) || gb1 == GB_EMOJI_MODIFIER) && (isExtend(gb2) || gb2 == GB_ZWJ)) {
                return false;
            }
            if (gb1 == GB_ZWJ && isExtendedPictographic(gb2)) {
                state[0] = STATE_DEFAULT;
                return false;
            }
            state[0] = STATE_DEFAULT;
        }
        // GB12 and GB13: Regional Indicator
        if (gb1 == GB_REGIONAL_INDICATOR && gb2 == GB_REGIONAL_INDICATOR) {
            if (state[0] == STATE_DEFAULT) {
                state[0] = STATE_REGIONAL_INDICATOR;
                return false;
            }
            state[0] = STATE_DEFAULT;
            return true;
        }
        // GB9: x (Extend | ZWJ)
        return !(isExtend(gb2) || gb2 == GB_ZWJ);
    }

    private static boolean isControl(int gb) {
        return gb == GB_CONTROL || gb == GB_CR || gb == GB_LF;
    }

    private static boolean keepsExtendedPictographic(int gb) {
        return gb == GB_INCB_EXTEND || gb == GB_INCB_LINKER || gb == GB_ZWNJ || gb == GB_ZWJ
                || gb == GB_EXTENDED_PICTOGRAPHIC || gb == GB_EMOJI_MODIFIER_BASE || gb == GB_EMOJI_MODIFIER;
    }

    private static boolean keepsIndicConjunct(int gb) {
        return gb == GB_INCB_CONSONANT || gb == GB_INCB_LINKER || gb == GB_INCB_EXTEND || gb == GB_ZWJ;
    }

    private static boolean isIndicConjunctBreakExtend(int gb) {
        return gb == GB_INCB_EXTEND || gb == GB_ZWJ;
    }

    private static boolean isExtend(int gb) {
        return gb == GB_ZWNJ || gb == GB_INCB_EXTEND || gb == GB_INCB_LINKER;
    }

    private static boolean isExtendedPictographic(int gb) {
        return gb == GB_EXTENDED_PICTOGRAPHIC || gb == GB_EMOJI_MODIFIER_BASE;
    }

    /** Port of OpenTUI {@code isGraphemeBreak}. */
    static boolean isGraphemeBreak(int previous, int current, int[] state) {
        if (current == REPLACEMENT) {
            return true;
        }
        if (previous == REPLACEMENT) {
            return true;
        }
        return computeGraphemeBreak(graphemeBreak(previous), graphemeBreak(current), state);
    }

    /** Code point at {@code index}, with a lone surrogate read as U+FFFD (as UTF-8 decoding does). */
    private static int codePointAt(CharSequence text, int index) {
        int codePoint = Character.codePointAt(text, index);
        return codePoint <= 0xFFFF && Character.isSurrogate((char) codePoint) ? REPLACEMENT : codePoint;
    }

    /**
     * Returns the end of the grapheme cluster that starts at {@code start}.
     * @param text text to segment
     * @param start char index of the first code point of the cluster
     * @return char index after the last code point of the cluster
     */
    public static int graphemeEnd(CharSequence text, int start) {
        int length = text.length();
        int previous = codePointAt(text, start);
        int index = start + Character.charCount(Character.codePointAt(text, start));
        int[] state = {STATE_DEFAULT};
        while (index < length) {
            int current = codePointAt(text, index);
            if (isGraphemeBreak(previous, current, state)) {
                break;
            }
            previous = current;
            index += Character.charCount(Character.codePointAt(text, index));
        }
        return index;
    }

    /**
     * Returns the column width of one grapheme cluster (OpenTUI {@code getWidthAt}). The text
     * must hold exactly one cluster, for example a {@link TextCharacter} string.
     * @param grapheme the grapheme cluster
     * @param method width method of the terminal
     * @return column width of the cluster, which can be 0 or more than 2
     */
    public static int graphemeWidth(String grapheme, WidthMethod method) {
        int first = codePointAt(grapheme, 0);
        int width = charWidth(first);
        boolean hasWidth = width > 0;
        boolean regionalIndicatorPair = first >= 0x1F1E6 && first <= 0x1F1FF;
        boolean hasIndicVirama = false;
        int index = Character.charCount(Character.codePointAt(grapheme, 0));
        while (index < grapheme.length()) {
            int codePoint = codePointAt(grapheme, index);
            index += Character.charCount(Character.codePointAt(grapheme, index));
            int codePointWidth = charWidth(codePoint);
            if (method == WidthMethod.WCWIDTH) {
                int w = eawToWidth(codePoint);
                if (w > 0) {
                    width += w;
                    hasWidth = true;
                }
                continue;
            }
            if (codePoint == VS16) {
                if (hasWidth && width == 1) {
                    width = 2;
                }
                continue;
            }
            if (generalCategory(codePoint) == GC_MN) {
                hasIndicVirama = true;
                continue;
            }
            boolean isRegionalIndicator = codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
            boolean isDevanagariBase = (codePoint >= 0x0915 && codePoint <= 0x0939)
                    || (codePoint >= 0x0958 && codePoint <= 0x095F);
            if (regionalIndicatorPair && isRegionalIndicator) {
                width += codePointWidth;
                hasWidth = true;
            } else if (!hasWidth && codePointWidth > 0) {
                width = codePointWidth;
                hasWidth = true;
            } else if (method == WidthMethod.UNICODE_WIDE && hasWidth
                    && (properties(codePoint) & ZERO_IN_GRAPHEME) == 0) {
                width = Math.max(width, 2);
            } else if (hasWidth && graphemeBreak(codePoint) == GB_SPACING_MARK && codePointWidth > 0) {
                width = Math.max(width, 2);
            } else if (hasWidth && hasIndicVirama && isDevanagariBase) {
                // OpenTUI also checks codePointWidth > 0, which is always true for a Devanagari base.
                if (codePoint != 0x0930) {
                    width += codePointWidth;
                }
                hasIndicVirama = false;
            }
        }
        return width;
    }
}
