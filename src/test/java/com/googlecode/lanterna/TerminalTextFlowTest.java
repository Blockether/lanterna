package com.googlecode.lanterna;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * vis fork: grapheme/EAW-aware word-wrap + full justification in
 * {@link TerminalTextUtils}.
 */
public class TerminalTextFlowTest {

    @Test
    public void displayWidth_is_grapheme_and_eaw_aware() {
        assertEquals(0, TerminalTextUtils.displayWidth(null));
        assertEquals(0, TerminalTextUtils.displayWidth(""));
        assertEquals(3, TerminalTextUtils.displayWidth("abc"));
        assertEquals(6, TerminalTextUtils.displayWidth("日本語")); // 日本語, 2 cols each
        assertEquals(2, TerminalTextUtils.displayWidth("📁"));        // 📁 emoji, 1 cluster, 2 cols
    }

    @Test
    public void wordWrap_breaks_on_spaces_within_width() {
        List<String> lines = TerminalTextUtils.wordWrap(10, "the quick brown fox jumps");
        for (String line : lines) {
            assertTrue("line '" + line + "' fits", TerminalTextUtils.displayWidth(line) <= 10);
        }
        // greedy packing
        assertEquals("the quick", lines.get(0));
    }

    @Test
    public void wordWrap_hard_splits_a_token_wider_than_width() {
        List<String> lines = TerminalTextUtils.wordWrap(5, "supercalifragilistic");
        for (String line : lines) {
            assertTrue(TerminalTextUtils.displayWidth(line) <= 5);
        }
        // reconstructs the original token
        StringBuilder joined = new StringBuilder();
        for (String l : lines) {
            joined.append(l);
        }
        assertEquals("supercalifragilistic", joined.toString());
    }

    @Test
    public void wordWrap_honours_embedded_newlines() {
        List<String> lines = TerminalTextUtils.wordWrap(40, "alpha\nbeta");
        assertEquals(2, lines.size());
        assertEquals("alpha", lines.get(0));
        assertEquals("beta", lines.get(1));
    }

    @Test
    public void wordWrap_blank_yields_one_empty_line() {
        assertEquals(1, TerminalTextUtils.wordWrap(10, "").size());
        assertEquals("", TerminalTextUtils.wordWrap(10, "   ").get(0));
    }

    @Test
    public void wordWrap_wide_glyphs_never_overflow() {
        List<String> lines = TerminalTextUtils.wordWrap(5, "中文内容测试"); // 6 CJK = 12 cols
        for (String line : lines) {
            assertTrue(TerminalTextUtils.displayWidth(line) <= 5);
        }
    }

    @Test
    public void justifyLine_stretches_to_exact_width() {
        String j = TerminalTextUtils.justifyLine("the quick brown", 20);
        assertEquals(20, TerminalTextUtils.displayWidth(j));
        // remainder distributed to the FIRST gaps: 20 - 13 text = 7 spaces over 2 gaps -> 4 + 3
        assertEquals("the    quick   brown", j);
    }

    @Test
    public void justifyLine_single_word_is_left_padded() {
        assertEquals("hi        ", TerminalTextUtils.justifyLine("hi", 10));
    }

    @Test
    public void justifyLine_blank_is_spaces() {
        assertEquals("     ", TerminalTextUtils.justifyLine("   ", 5));
    }

    @Test
    public void justify_leaves_last_line_of_paragraph_ragged() {
        List<String> lines = TerminalTextUtils.justify(12, "the quick brown fox jumps over", false);
        // every non-last, non-blank line is stretched to the width...
        for (int i = 0; i < lines.size() - 1; i++) {
            if (!lines.get(i).isEmpty()) {
                assertEquals("line " + i + " justified", 12, TerminalTextUtils.displayWidth(lines.get(i)));
            }
        }
        // ...the final line stays left-aligned (not padded to width)
        String last = lines.get(lines.size() - 1);
        assertTrue("last line ragged", TerminalTextUtils.displayWidth(last) <= 12);
        assertTrue("last line not space-stretched", !last.contains("  "));
    }

    @Test
    public void justify_can_stretch_the_last_line_when_asked() {
        List<String> lines = TerminalTextUtils.justify(12, "the quick brown fox jumps over", true);
        for (String line : lines) {
            if (!line.isEmpty()) {
                assertEquals(12, TerminalTextUtils.displayWidth(line));
            }
        }
    }

    @Test
    public void foldColumns_passes_through_lines_within_budget() {
        // A line already within budget is returned byte-for-byte unchanged —
        // normal multi-line source must not be touched.
        List<String> lines = TerminalTextUtils.foldColumns(40, "short line");
        assertEquals(1, lines.size());
        assertEquals("short line", lines.get(0));
    }

    @Test
    public void foldColumns_folds_an_over_wide_line_at_the_edge() {
        List<String> lines = TerminalTextUtils.foldColumns(10, "abcdefghijklmnopqrstuvwxyz");
        for (String line : lines) {
            assertTrue("'" + line + "' fits", TerminalTextUtils.displayWidth(line) <= 10);
        }
        // first segment is exactly the budget, content reconstructs verbatim
        assertEquals("abcdefghij", lines.get(0));
        StringBuilder joined = new StringBuilder();
        for (String l : lines) {
            joined.append(l);
        }
        assertEquals("abcdefghijklmnopqrstuvwxyz", joined.toString());
    }

    @Test
    public void foldColumns_preserves_whitespace_unlike_wordWrap() {
        // The whole point vs wordWrap: a one-line JSON-ish arg with leading
        // indentation and spaces folds WITHOUT reflowing/dropping whitespace.
        String src = "    {\"message\": \"Fix live tab title\", \"all\": true}";
        List<String> folded = TerminalTextUtils.foldColumns(20, src);
        for (String line : folded) {
            assertTrue(TerminalTextUtils.displayWidth(line) <= 20);
        }
        // reconstructs the original byte-for-byte (no whitespace collapse)
        assertEquals(src, String.join("", folded));
        // leading indentation survives on the first segment
        assertTrue(folded.get(0).startsWith("    {"));
    }

    @Test
    public void foldColumns_honours_embedded_newlines_and_keeps_blanks() {
        List<String> lines = TerminalTextUtils.foldColumns(40, "alpha\n\nbeta");
        assertEquals(3, lines.size());
        assertEquals("alpha", lines.get(0));
        assertEquals("", lines.get(1));
        assertEquals("beta", lines.get(2));
    }

    @Test
    public void foldColumns_wide_glyphs_never_overflow_and_never_split_clusters() {
        List<String> lines = TerminalTextUtils.foldColumns(5, "中文内容测试"); // 6 CJK = 12 cols
        for (String line : lines) {
            assertTrue(TerminalTextUtils.displayWidth(line) <= 5);
        }
        assertEquals("中文内容测试", String.join("", lines));
    }

    @Test
    public void foldColumns_blank_yields_one_empty_line() {
        assertEquals(1, TerminalTextUtils.foldColumns(10, "").size());
        assertEquals("", TerminalTextUtils.foldColumns(10, "").get(0));
        assertEquals(1, TerminalTextUtils.foldColumns(10, null).size());
    }
}
