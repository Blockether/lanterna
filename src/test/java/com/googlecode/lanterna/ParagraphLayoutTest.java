/*
 * Algorithm cases and independent exhaustive oracle adapted from Justice's
 * MIT-licensed tests, copyright (c) 2026 Kit Langton.
 * See META-INF/justice-LICENSE.txt and ParagraphLayout's source attribution.
 */
package com.googlecode.lanterna;

import com.googlecode.lanterna.ParagraphLayout.Ending;
import com.googlecode.lanterna.ParagraphLayout.Layout;
import com.googlecode.lanterna.ParagraphLayout.Line;
import com.googlecode.lanterna.ParagraphLayout.Margins;
import com.googlecode.lanterna.ParagraphLayout.Mode;
import com.googlecode.lanterna.ParagraphLayout.OpticalMeasurer;
import com.googlecode.lanterna.ParagraphLayout.Options;
import com.googlecode.lanterna.ParagraphLayout.Prepared;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class ParagraphLayoutTest {
    private static final ToDoubleFunction<String> MEASURE = text -> text.codePoints()
            .mapToDouble(cp -> cp == ' ' ? 4 : 8).sum();
    private static final ToDoubleFunction<String> SHAPED = text -> MEASURE.applyAsDouble(text)
            - (text.length() - text.replace("a-", "").length());
    private static final String PROSE = "A quiet paragraph can become much more comfortable when its lines share "
            + "a reasonably even rhythm of spaces instead of alternating between very tight and very loose arrangements.";

    private static List<String> texts(Prepared prepared, Layout layout) {
        return layout.lines().stream().map(line -> ParagraphLayout.lineText(prepared, line)).toList();
    }

    private static List<String> thirds(String word) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < word.length(); i += 3) {
            parts.add(word.substring(i, Math.min(i + 3, word.length())));
        }
        return parts;
    }

    private static long jumps(Layout layout) {
        long jumps = 0;
        for (int i = 1; i < layout.lines().size(); i++) {
            if (Math.abs(layout.lines().get(i).fitness() - layout.lines().get(i - 1).fitness()) > 1) {
                jumps++;
            }
        }
        return jumps;
    }

    @Test
    public void matches_exhaustive_partitions_across_policies_and_varying_measures() {
        Random random = new Random(42);
        for (int iteration = 0; iteration < 700; iteration++) {
            List<String> words = new ArrayList<>();
            for (int j = 0, n = 2 + random.nextInt(8); j < n; j++) {
                words.add((random.nextInt(5) == 0 ? "“" : "") + "w".repeat(1 + random.nextInt(14))
                        + List.of("", "", ".", ",", ".”").get(random.nextInt(5)));
            }
            String text = String.join(" ", words);
            Prepared prepared = ParagraphLayout.prepare(text, MEASURE);
            OpticalMeasurer optical = iteration % 3 == 0
                    ? (word, index) -> new Margins(index % 3 * 1.7, index % 2 * 2.1) : null;
            if (optical != null) {
                prepared = ParagraphLayout.withOpticalMargins(prepared, optical);
            }
            double[] widths = new double[iteration < 500 ? 1 : 1 + random.nextInt(4)];
            for (int j = 0; j < widths.length; j++) {
                widths[j] = 20 + random.nextInt(300);
            }
            Options options = new Options();
            options.ending = iteration % 2 == 0 ? Ending.FIT : Ending.SOFT;
            options.emergencyStretch = iteration % 3 == 0 ? 0 : random.nextDouble() * 0.2;
            options.opening = random.nextDouble();
            options.stretch = random.nextDouble() * 2;
            options.shrink = random.nextDouble();
            options.tracking = iteration % 10 == 0 ? 10 : random.nextDouble();
            options.compressionPenalty = random.nextDouble() * 4;
            options.hanging = random.nextDouble();
            options.lastLine = random.nextDouble();
            options.widowPenalty = random.nextDouble() * 3000;
            options.adjacentPenalty = iteration % 4 == 0 ? 0 : 100;
            for (Mode mode : Mode.values()) {
                options.mode = mode;
                Layout actual = ParagraphLayout.solve(prepared, widths, options);
                double expected = exhaustive(text, widths, options, false, MEASURE, optical);
                assertEquals("case " + iteration + " " + mode, expected, actual.cost(), 1e-5);
                assertEquals(text, String.join(" ", texts(prepared, actual)));
                verifyGeometry(prepared, actual, widths, options);
            }
        }
    }

    @Test
    public void matches_exhaustive_shaped_hyphenation_with_adjacency_and_optical_margins() {
        Random random = new Random(17);
        for (int iteration = 0; iteration < 100; iteration++) {
            List<String> words = new ArrayList<>();
            for (int j = 0; j < 2 + iteration % 3; j++) {
                words.add("a".repeat(3 + random.nextInt(7)));
            }
            String text = String.join(" ", words);
            double[] widths = iteration % 4 == 0
                    ? new double[] {30 + random.nextDouble() * 150, 30 + random.nextDouble() * 150}
                    : new double[] {30 + random.nextDouble() * 150};
            Options options = new Options();
            options.mode = iteration % 2 == 0 ? Mode.BALANCED : Mode.STRICT;
            options.ending = iteration % 3 == 0 ? Ending.SOFT : Ending.FIT;
            options.tracking = random.nextDouble();
            options.shrink = random.nextDouble();
            options.hyphenPenalty = random.nextDouble() * 100;
            options.consecutiveHyphenPenalty = random.nextDouble() * 300;
            options.finalHyphenPenalty = random.nextDouble() * 300;
            Prepared prepared = ParagraphLayout.withHyphenation(ParagraphLayout.prepare(text, SHAPED),
                    (word, index) -> thirds(word), (part, index) -> SHAPED.applyAsDouble(part));
            OpticalMeasurer optical = iteration % 3 == 0
                    ? (part, index) -> new Margins(0.3 * (part.charAt(0) % 5) + index * 0.1,
                            part.endsWith("-") ? 2.5 : 0.4 * index) : null;
            if (optical != null) {
                prepared = ParagraphLayout.withOpticalMargins(prepared, optical);
            }
            Layout actual = ParagraphLayout.solve(prepared, widths, options);
            assertEquals("hyphenated case " + iteration,
                    exhaustive(text, widths, options, true, SHAPED, optical), actual.cost(), 1e-5);
            assertEquals(text, reconstruct(prepared, actual));
            for (Line line : actual.lines()) {
                assertEquals(SHAPED.applyAsDouble(ParagraphLayout.lineText(prepared, line)), line.natural(), 1e-8);
            }
            verifyGeometry(prepared, actual, widths, options);
        }
    }

    private static void verifyGeometry(Prepared p, Layout layout, double[] widths, Options options) {
        for (int i = 0; i < layout.lines().size(); i++) {
            Line line = layout.lines().get(i);
            assertEquals(widths[Math.min(i, widths.length - 1)], line.width(), 0);
            assertTrue(Math.abs(line.tracking()) <= options.tracking + 1e-9);
            assertTrue(line.wordSpacing() >= -p.space() * options.shrink - 1e-9);
            if (options.mode == Mode.STRICT) {
                assertTrue(line.wordSpacing() <= p.space() * options.stretch + 1e-9);
            }
            if (!line.last() || line.natural() > line.width() + line.hanging() + line.opening()) {
                double rendered = line.natural() + line.wordSpacing() * (line.end() - line.start() - 1)
                        + line.tracking() * TextCharacter.fromString(ParagraphLayout.lineText(p, line)).length;
                assertEquals(line.width() + line.hanging() + line.opening(), rendered + line.residual(), 1e-7);
            }
        }
        assertEquals(layout.cost(), layout.lines().stream().mapToDouble(Line::cost).sum(), 1e-6);
    }

    private static String reconstruct(Prepared p, Layout layout) {
        StringBuilder source = new StringBuilder();
        Line previous = null;
        for (Line line : layout.lines()) {
            if (previous != null && previous.endOffset() < 0) {
                source.append(' ');
            }
            String text = ParagraphLayout.lineText(p, line);
            source.append(line.hyphenated() ? text.substring(0, text.length() - 1) : text);
            previous = line;
        }
        return source.toString();
    }

    @Test
    public void retains_competing_fitness_histories_and_matches_upstream_examples() {
        Prepared prepared = ParagraphLayout.prepare(PROSE, MEASURE);
        Options independent = new Options();
        independent.adjacentPenalty = 0;
        Layout withoutAdjacency = ParagraphLayout.solve(prepared, 187, independent);
        Layout adjacent = ParagraphLayout.solve(prepared, 187);
        assertEquals(5, jumps(withoutAdjacency));
        assertEquals(3, jumps(adjacent));
        assertTrue(adjacent.cost() < withoutAdjacency.cost() + 100 * jumps(withoutAdjacency));
        assertEquals(PROSE, String.join(" ", texts(prepared, adjacent)));
        Prepared terminal = ParagraphLayout.prepare(PROSE);
        Layout cells = ParagraphLayout.solve(terminal, 24, Options.terminal());
        assertEquals(746.124696359168, cells.cost(), 1e-9);
        assertEquals(List.of("A quiet paragraph", "can become much more", "comfortable when its",
                "lines share a reasonably", "even rhythm of spaces", "instead of alternating",
                "between very tight and", "very loose arrangements."), texts(terminal, cells));
    }

    @Test
    public void reuses_preparation_and_retains_original_source_offsets() {
        AtomicInteger calls = new AtomicInteger();
        Prepared p = ParagraphLayout.prepare("  one\t two one  two", text -> {
            calls.incrementAndGet();
            return MEASURE.applyAsDouble(text);
        });
        assertEquals(3, calls.get());
        assertEquals(List.of("one", "two", "one", "two"), p.words());
        assertEquals(7, p.sourceOffset(1));
        for (int width : new int[] {40, 80, 100}) {
            Layout layout = ParagraphLayout.solve(p, width);
            for (Line line : layout.lines()) {
                String original = "  one\t two one  two".substring(line.sourceStart(), line.sourceEnd());
                assertEquals(original.replaceAll("[ \\t]+", " "), ParagraphLayout.lineText(p, line));
            }
        }
        assertEquals(3, calls.get());
        assertThrows(UnsupportedOperationException.class, () -> p.words().add("mutate"));
    }

    @Test
    public void finishes_loose_lines_but_never_stretches_a_fitting_last_line() {
        Prepared p = ParagraphLayout.prepare("aaa bbb ccccccc", MEASURE);
        Options options = new Options();
        options.tracking = 0;
        options.mode = Mode.STRICT;
        assertTrue(ParagraphLayout.solve(p, 70, options).lines().getFirst().residual() > 0);
        options.mode = Mode.BALANCED;
        Layout balanced = ParagraphLayout.solve(p, 70, options);
        assertEquals(0, balanced.lines().getFirst().residual(), 0);
        assertTrue(balanced.lines().getFirst().relaxed());
        assertEquals(0, balanced.lines().getLast().wordSpacing(), 0);
        options.shrink = 0.5;
        Line tight = ParagraphLayout.solve(ParagraphLayout.prepare("aaa bbb", MEASURE), 50, options).lines().getFirst();
        assertEquals(-2, tight.wordSpacing(), 0);
        assertEquals(0, tight.residual(), 0);
        Prepared unfit = ParagraphLayout.prepare("tiny extraordinarilylongword tiny", MEASURE);
        Layout overflow = ParagraphLayout.solve(unfit, 40, options);
        assertTrue(overflow.lines().stream().anyMatch(line -> line.residual() < 0));
        assertEquals(unfit.words(), List.of(String.join(" ", texts(unfit, overflow)).split(" ")));
    }

    @Test
    public void supports_source_hyphens_and_merged_dictionary_breaks_without_losing_text() {
        Prepared p = ParagraphLayout.prepare("a well-known state-of-the-art thing", MEASURE);
        Options options = new Options();
        options.tracking = 0;
        Layout narrow = ParagraphLayout.solve(p, 60, options);
        assertTrue(texts(p, narrow).contains("a well-"));
        assertTrue(narrow.lines().stream().noneMatch(Line::hyphenated));
        assertEquals(String.join(" ", p.words()), reconstruct(p, narrow));
        options.explicitHyphenPenalty = 0;
        double cheap = ParagraphLayout.solve(p, 60, options).cost();
        options.explicitHyphenPenalty = 500;
        assertTrue(ParagraphLayout.solve(p, 60, options).cost() > cheap);
        Prepared merged = ParagraphLayout.withHyphenation(ParagraphLayout.prepare("abc-defghijkl", MEASURE),
                (word, index) -> List.of("abc-def", "ghijkl"), (part, index) -> MEASURE.applyAsDouble(part));
        options.explicitHyphenPenalty = 20;
        Layout tiny = ParagraphLayout.solve(merged, 60, options);
        assertEquals(List.of("abc-", "def-", "ghijkl"), texts(merged, tiny));
        assertEquals(List.of(false, true, false), tiny.lines().stream().map(Line::hyphenated).toList());
        assertEquals("abc-defghijkl", reconstruct(merged, tiny));
    }

    @Test
    public void supports_successive_hyphens_and_charges_adjacency_and_final_penalties() {
        AtomicInteger calls = new AtomicInteger();
        Prepared p = ParagraphLayout.withHyphenation(ParagraphLayout.prepare("abcdefghijklmnopqr", MEASURE),
                (word, index) -> thirds(word), (part, index) -> {
                    calls.incrementAndGet();
                    return MEASURE.applyAsDouble(part);
                });
        int measured = calls.get();
        Options options = new Options();
        options.tracking = 0;
        Layout layout = ParagraphLayout.solve(p, 56, options);
        assertEquals(List.of("abcdef-", "ghijkl-", "mnopqr"), texts(p, layout));
        assertEquals(List.of(0, 6, 12), layout.lines().stream().map(Line::sourceStart).toList());
        assertEquals(List.of(6, 12, 18), layout.lines().stream().map(Line::sourceEnd).toList());
        options.hyphenPenalty = 0;
        options.consecutiveHyphenPenalty = 0;
        options.finalHyphenPenalty = 0;
        assertEquals(500, layout.cost() - ParagraphLayout.solve(p, 56, options).cost(), 1e-8);
        assertEquals(List.of("abcdefghijklmnopqr"), texts(p, ParagraphLayout.solve(p, 500)));
        assertEquals(measured, calls.get());
    }

    @Test
    public void measures_optical_words_continuations_and_hyphens_once() {
        Prepared word = ParagraphLayout.withOpticalMargins(ParagraphLayout.prepare("Thus.", MEASURE),
                (text, index) -> new Margins(2, 3));
        Line on = ParagraphLayout.solve(word, 100).lines().getFirst();
        assertEquals(2, on.opening(), 0);
        assertEquals(8, on.hanging(), 0);
        Options options = new Options();
        options.protrusion = 0;
        assertEquals(0, ParagraphLayout.solve(word, 100, options).lines().getFirst().opening(), 0);
        assertEquals(8, ParagraphLayout.solve(word, 100, options).lines().getFirst().hanging(), 0);
        AtomicInteger calls = new AtomicInteger();
        Prepared original = ParagraphLayout.withHyphenation(ParagraphLayout.prepare("abcdefghijklmnopqr", MEASURE),
                (text, index) -> thirds(text), (text, index) -> MEASURE.applyAsDouble(text));
        Prepared p = ParagraphLayout.withOpticalMargins(original, (text, index) -> {
            calls.incrementAndGet();
            return new Margins(text.startsWith("a") ? 1 : 2, text.equals("-") ? 4 : 0);
        });
        int measured = calls.get();
        options = new Options();
        options.tracking = 0;
        Layout layout = ParagraphLayout.solve(p, 54, options);
        assertTrue(layout.lines().stream().anyMatch(Line::hyphenated));
        for (Line line : layout.lines()) {
            assertEquals(line.startOffset() > 0 ? 2 : 1, line.opening(), 0);
            assertEquals(line.hyphenated() ? 4 : 0, line.hanging(), 0);
        }
        ParagraphLayout.solve(p, 100);
        ParagraphLayout.solve(p, 250);
        assertEquals(measured, calls.get());
    }

    @Test
    public void keeps_graphemes_and_nbsp_indivisible_and_validates_callbacks() {
        Prepared p = ParagraphLayout.prepare("  walk\t10\u00a0km\n today  ", MEASURE);
        assertEquals(List.of("walk", "10\u00a0km", "today"), p.words());
        Prepared nbsp = ParagraphLayout.withHyphenation(ParagraphLayout.prepare("10\u00a0km", MEASURE),
                (text, index) -> { throw new AssertionError("NBSP must not reach a dictionary"); },
                (text, index) -> MEASURE.applyAsDouble(text));
        assertEquals(List.of("10\u00a0km"), texts(nbsp, ParagraphLayout.solve(nbsp, 20)));
        for (List<String> parts : List.of(List.of("wrong"), List.of("", "word"))) {
            assertThrows(IllegalArgumentException.class, () -> ParagraphLayout.withHyphenation(
                    ParagraphLayout.prepare("word", MEASURE), (text, index) -> parts,
                    (text, index) -> MEASURE.applyAsDouble(text)));
        }
        for (List<String> pieces : List.of(List.of("a", "́bc"), List.of("👩", "‍💻x"), List.of("🇵", "🇱x"))) {
            assertThrows(IllegalArgumentException.class, () -> ParagraphLayout.withHyphenation(
                    ParagraphLayout.prepare(String.join("", pieces)), (text, index) -> pieces,
                    (text, index) -> TerminalTextUtils.displayWidth(text)));
        }
        Prepared unicode = ParagraphLayout.prepare("zażółć gęślą á 👩‍💻 🇵🇱 done");
        Layout layout = ParagraphLayout.solve(unicode, 15, Options.terminal());
        assertEquals(String.join(" ", unicode.words()), reconstruct(unicode, layout));
        for (Line line : layout.lines()) {
            assertEquals(TerminalTextUtils.displayWidth(ParagraphLayout.lineText(unicode, line)), line.natural(), 0);
        }
        assertThrows(IllegalArgumentException.class, () -> ParagraphLayout.withHyphenation(
                ParagraphLayout.prepare("abcdef"), (text, index) -> thirds(text), (text, index) -> Double.NaN));
    }

    @Test
    public void validates_dimensions_policies_measurements_and_optical_credits() {
        Prepared empty = ParagraphLayout.prepare(" \n\t", MEASURE);
        assertEquals(new Layout(List.of(), 0, 0), ParagraphLayout.solve(empty, 100));
        for (double width : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> ParagraphLayout.solve(empty, width));
        }
        assertThrows(IllegalArgumentException.class, () -> ParagraphLayout.solve(empty, new double[0], new Options()));
        for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            Options options = new Options();
            options.tracking = invalid;
            assertThrows(IllegalArgumentException.class, () -> ParagraphLayout.solve(empty, 100, options));
            assertThrows(IllegalArgumentException.class, () -> ParagraphLayout.prepare("word", text -> invalid));
        }
        Options options = new Options();
        options.shrink = 1.1;
        assertThrows(IllegalArgumentException.class, () -> ParagraphLayout.solve(empty, 100, options));
        assertThrows(IllegalArgumentException.class, () -> ParagraphLayout.prepare("word", text -> 0));
        Prepared p = ParagraphLayout.prepare("word");
        assertThrows(IllegalArgumentException.class, () -> ParagraphLayout.withOpticalMargins(p,
                (text, index) -> new Margins(-1, 0)));
        assertThrows(IllegalArgumentException.class, () -> ParagraphLayout.withOpticalMargins(p,
                (text, index) -> new Margins(0, Double.POSITIVE_INFINITY)));
    }

    @Test(timeout = 5000)
    public void bounds_terminal_adapter_work_and_keeps_fallbacks_grapheme_safe() {
        assertTrue(ParagraphLayout.isTerminalProse("zażółć gęślą á 👩‍💻 🇵🇱 10\u00a0km"));
        assertFalse(ParagraphLayout.isTerminalProse("word ".repeat(401)));
        assertFalse(ParagraphLayout.isTerminalProse("a".repeat(16385)));
        assertFalse(ParagraphLayout.isTerminalProse("a-".repeat(17)));
        assertFalse(ParagraphLayout.isTerminalProse("a-b ".repeat(129)));
        assertTrue(ParagraphLayout.isTerminalProse("a-b ".repeat(128)));
        assertFalse(ParagraphLayout.isTerminalProse("日本語"));
        assertFalse(ParagraphLayout.isTerminalProse("مرحبا"));
        assertFalse(ParagraphLayout.isTerminalProse("hidden\u202etext"));
        assertFalse(ParagraphLayout.isTerminalProse("hidden\u200etext"));
        assertFalse(ParagraphLayout.isTerminalProse("hidden\u200ftext"));
        assertFalse(ParagraphLayout.isTerminalProse("hidden\u061ctext"));
        assertFalse(ParagraphLayout.isTerminalProse("\u001b[31mtext"));
        for (String input : List.of("word ".repeat(401), "a-".repeat(129), "日本語日本語", "👩‍💻".repeat(8))) {
            List<String> lines = TerminalTextUtils.justify(12, input, false);
            assertTrue(lines.stream().allMatch(line -> TerminalTextUtils.displayWidth(line) <= 12));
            assertEquals(input.replaceAll(" +", ""), String.join("", lines).replaceAll(" +", ""));
        }
        Prepared longProse = ParagraphLayout.prepare("one two three four five six seven ".repeat(500));
        Layout layout = ParagraphLayout.solve(longProse, 60, Options.terminal());
        assertTrue("ordinary pruning should keep work subquadratic", layout.candidates() < 100L * longProse.words().size());
    }

    // This oracle enumerates source-offset partitions. It deliberately has no
    // prefix sums, fragment matrices, pruning or dynamic-programming states.
    private record Boundary(int end, int next, boolean hyphen) {
    }

    private static double exhaustive(String text, double[] widths, Options o,
            boolean hyphenation, ToDoubleFunction<String> measure, OpticalMeasurer optical) {
        List<Boundary> breaks = new ArrayList<>();
        breaks.add(new Boundary(0, 0, false));
        Matcher words = Pattern.compile("[^ ]+").matcher(text);
        while (words.find()) {
            if (hyphenation) {
                for (int offset = words.start() + 3; offset < words.end(); offset += 3) {
                    breaks.add(new Boundary(offset, offset, true));
                }
            }
            breaks.add(new Boundary(words.end(), Math.min(text.length(), words.end() + 1), false));
        }
        double ordinary = visit(text, breaks, widths, o, measure, optical, false, 0, 0, 1);
        return ordinary == Double.POSITIVE_INFINITY
                ? visit(text, breaks, widths, o, measure, optical, true, 0, 0, 1) : ordinary;
    }

    private static double visit(String text, List<Boundary> breaks, double[] widths,
            Options o, ToDoubleFunction<String> measure, OpticalMeasurer optical,
            boolean emergency, int start, int lineIndex, int previousFitness) {
        if (start == breaks.size() - 1) {
            return 0;
        }
        double best = Double.POSITIVE_INFINITY;
        double width = widths[Math.min(lineIndex, widths.length - 1)];
        for (int end = start + 1; end < breaks.size(); end++) {
            Boundary a = breaks.get(start);
            Boundary b = breaks.get(end);
            String visible = text.substring(a.next, b.end) + (b.hyphen ? "-" : "");
            String[] words = visible.split(" ");
            int gaps = words.length - 1;
            int chars = visible.codePointCount(0, visible.length());
            boolean last = end == breaks.size() - 1;
            double natural = measure.applyAsDouble(visible);
            int firstIndex = (int) text.substring(0, a.next).chars().filter(cp -> cp == ' ').count();
            int lastIndex = (int) text.substring(0, b.end).chars().filter(cp -> cp == ' ').count();
            Matcher punctuation = Pattern.compile("[.,;:!?…’”'\"]+$").matcher(visible);
            Matcher quote = Pattern.compile("^[“‘\"'«‹]").matcher(visible);
            double hanging = b.hyphen ? 0 : (punctuation.find() ? measure.applyAsDouble(punctuation.group()) * o.hanging : 0);
            double opening = quote.find() ? measure.applyAsDouble(quote.group()) * o.opening : 0;
            if (optical != null) {
                hanging = Math.max(hanging, optical.measure(b.hyphen ? "-" : words[words.length - 1], lastIndex).end() * o.protrusion);
                opening = optical.measure(words[0], firstIndex).start() * o.protrusion;
            }
            double delta = last && natural <= width + hanging + opening ? 0 : width + hanging + opening - natural;
            double capacity = gaps * 4 * (delta < 0 ? o.shrink : o.stretch) + chars * o.tracking;
            double ratio = capacity == 0 ? 0 : Math.min(1, Math.abs(delta) / capacity);
            double signed = capacity != 0 ? delta / capacity : delta == 0 ? 0 : Math.copySign(Double.POSITIVE_INFINITY, delta);
            int fitness = signed < -0.5 ? 0 : signed <= 0.5 ? 1 : signed <= 1 ? 2 : 3;
            double residual = delta - Math.signum(delta) * ratio * capacity;
            double strain = 100 * Math.pow(ratio, 3);
            if (o.mode == Mode.BALANCED) {
                strain = 100 * Math.pow(ratio + Math.abs(residual) / Math.max(capacity, Math.max(gaps * 4, 4)), 3);
                if (gaps > 0) {
                    double before = Math.signum(delta) * ratio * 4 * (delta < 0 ? o.shrink : o.stretch);
                    residual -= (Math.max(-4 * o.shrink, before + residual / gaps) - before) * gaps;
                }
            }
            if (emergency && delta > 0) {
                strain = 100 * Math.pow(delta / (capacity + width * o.emergencyStretch), 3);
            }
            if (o.mode == Mode.BALANCED && o.emergencyStretch > 0 && !emergency
                    && (delta > capacity * Math.cbrt(2) + 0.01 || Math.abs(residual) > 0.01)) {
                continue;
            }
            double cost = 1 + strain * (delta < 0 ? o.compressionPenalty : 1);
            if (Math.abs(residual) > 0.01) {
                cost += o.mode == Mode.BALANCED && residual < 0
                        ? 1e6 + 1e4 * residual * residual : 1e4 + 100 * residual * residual;
            }
            if (last && start > 0) {
                double missing = Math.max(0, width * o.lastLine - natural);
                cost += o.ending == Ending.SOFT ? o.widowPenalty * Math.pow(missing / width, 2)
                        : o.widowPenalty / 300 * Math.min(10000, 100 * Math.pow(missing / Math.max(4, gaps * 4 * o.stretch + chars * o.tracking), 3));
            }
            cost += b.hyphen ? o.hyphenPenalty + (a.hyphen ? o.consecutiveHyphenPenalty : 0)
                    : last && a.hyphen ? o.finalHyphenPenalty : 0;
            if (start > 0 && Math.abs(fitness - previousFitness) > 1) {
                cost += o.adjacentPenalty;
            }
            best = Math.min(best, cost + visit(text, breaks, widths, o, measure, optical,
                    emergency, end, lineIndex + 1, fitness));
        }
        return best;
    }
}
