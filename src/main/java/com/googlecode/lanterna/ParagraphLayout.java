/*
 * Copyright (c) 2026 Kit Langton
 * Java adaptation for Lanterna. Distributed under the MIT license; see
 * META-INF/justice-LICENSE.txt in this distribution.
 */
package com.googlecode.lanterna;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whole-paragraph line breaking, adapted from Justice's complete numerical engine
 * at commit f87695114572db6d50bc7a80cf2520fceacd2759 (identical to version 0.3.0).
 * Preparation measures words once; solving can reuse those measurements across
 * widths and policies. No rendering, font, dictionary or terminal is required.
 *
 * <p>The optimum includes adjacent-line fitness, short endings and optional
 * hyphenation, in the first viable ordinary/emergency fitting pass. Widths may
 * vary by line; the final width repeats. Unavoidable overflow remains explicit
 * in {@link Line#residual()}, never silently truncating source text.</p>
 *
 * <p>For terminal cells use {@link #prepare(String)} and
 * {@link Options#terminal()}. Pixel tracking, space compression and optical
 * protrusion are not representable in a cell grid and are disabled by that
 * policy. Render integer gaps separately, and keep a fitting last line ragged.
 * Pass each hard-break-delimited paragraph separately. The bounded terminal
 * adapter can use {@link #isTerminalProse(String)} before preparing untrusted or
 * streaming text; the general numerical API does not impose that size limit.</p>
 *
 * @see <a href="https://github.com/kitlangton/justice">Justice</a>
 */
public final class ParagraphLayout {
    private static final Pattern WORD = Pattern.compile("[^ \\t\\r\\n\\f]+");
    private static final Pattern END_PUNCTUATION = Pattern.compile("[.,;:!?…’”'\"]+$");
    private static final Pattern START_QUOTE = Pattern.compile("^[“‘\"'«‹]");
    private static final Pattern EXPLICIT_HYPHEN = Pattern.compile("(?<=[\\p{L}\\p{N}])[-\u2010](?=[\\p{L}\\p{N}])");
    private static final double INFINITY = Double.POSITIVE_INFINITY;

    private ParagraphLayout() {
    }

    public enum Mode { BALANCED, STRICT }
    public enum Ending { FIT, SOFT }

    /** Fitting policy, copied and validated by each solve. Dimensions use the measurement's units. */
    public static final class Options {
        public double stretch = 0.6;
        public double shrink = 0.25;
        public Mode mode = Mode.BALANCED;
        public double tracking = 0.3;
        public double compressionPenalty = 2;
        public double emergencyStretch = 0.15;
        public double hanging = 1;
        public double opening = 0.3;
        public double protrusion = 1;
        public double adjacentPenalty = 100;
        public double lastLine = 0.33;
        public Ending ending = Ending.FIT;
        public double widowPenalty = 300;
        public double hyphenPenalty = 50;
        public double consecutiveHyphenPenalty = 200;
        public double finalHyphenPenalty = 200;
        public double explicitHyphenPenalty = 20;

        public Options() {
        }

        public Options(Options original) {
            stretch = original.stretch;
            shrink = original.shrink;
            mode = original.mode;
            tracking = original.tracking;
            compressionPenalty = original.compressionPenalty;
            emergencyStretch = original.emergencyStretch;
            hanging = original.hanging;
            opening = original.opening;
            protrusion = original.protrusion;
            adjacentPenalty = original.adjacentPenalty;
            lastLine = original.lastLine;
            ending = original.ending;
            widowPenalty = original.widowPenalty;
            hyphenPenalty = original.hyphenPenalty;
            consecutiveHyphenPenalty = original.consecutiveHyphenPenalty;
            finalHyphenPenalty = original.finalHyphenPenalty;
            explicitHyphenPenalty = original.explicitHyphenPenalty;
        }

        /** Cell-safe policy: gaps never shrink and glyphs never leave their cells. */
        public static Options terminal() {
            Options options = new Options();
            options.shrink = 0;
            options.tracking = 0;
            options.hanging = 0;
            options.opening = 0;
            options.protrusion = 0;
            return options;
        }
    }

    /** Immutable measured paragraph. Arrays remain private and are shared only by immutable preparations. */
    public static final class Prepared {
        private final List<String> words;
        private final int[] sourceOffsets;
        private final double[] widths;
        private final double[] characters;
        private final double[] endHangs;
        private final double[] startHangs;
        private final double space;
        private final WordFragments[] hyphenation;
        private final double[] startProtrusions;
        private final double[] endProtrusions;

        private Prepared(List<String> words, int[] sourceOffsets, double[] widths,
                double[] characters, double[] endHangs, double[] startHangs, double space,
                WordFragments[] hyphenation, double[] startProtrusions, double[] endProtrusions) {
            this.words = words;
            this.sourceOffsets = sourceOffsets;
            this.widths = widths;
            this.characters = characters;
            this.endHangs = endHangs;
            this.startHangs = startHangs;
            this.space = space;
            this.hyphenation = hyphenation;
            this.startProtrusions = startProtrusions;
            this.endProtrusions = endProtrusions;
        }

        public List<String> words() {
            return words;
        }

        /** UTF-16 source start of one word, before whitespace normalization. */
        public int sourceOffset(int word) {
            return sourceOffsets[word];
        }

        public double space() {
            return space;
        }

        private Prepared withFragments(WordFragments[] fragments, double[] starts, double[] ends) {
            return new Prepared(words, sourceOffsets, widths, characters, endHangs,
                    startHangs, space, fragments, starts, ends);
        }
    }

    /**
     * One immutable line. Word end and source end are exclusive. Offsets within
     * words are UTF-16: startOffset is zero for a whole first word, endOffset is
     * -1 for a whole last word. Source offsets refer to the original input, not
     * the normalized display string. A generated hyphen is not part of that source.
     * Fitness classes are 0 tight, 1 decent, 2 loose and 3 very loose.
     */
    public record Line(int start, int end, double width, double natural,
            double wordSpacing, double tracking, double hanging, double opening,
            double residual, boolean relaxed, double cost, boolean last,
            int startOffset, int endOffset, boolean hyphenated, int fitness,
            int sourceStart, int sourceEnd) {
    }

    public record Layout(List<Line> lines, double cost, long candidates) {
        public Layout {
            lines = List.copyOf(lines);
        }
    }

    @FunctionalInterface
    public interface Hyphenator {
        /** Return nonempty pieces whose concatenation is exactly the source word. */
        List<String> split(String word, int wordIndex);
    }

    @FunctionalInterface
    public interface WordMeasurer {
        double measure(String text, int wordIndex);
    }

    public record Margins(double start, double end) {
    }

    @FunctionalInterface
    public interface OpticalMeasurer {
        Margins measure(String text, int wordIndex);
    }

    private record WordFragments(int[] offsets, double[] widths, double[] hyphenWidths,
            double[] characters, boolean[] explicit, double[] startProtrusions,
            double hyphenProtrusion) {
    }

    private static final class Scratch {
        int start;
        int end;
        double width;
        double natural;
        double wordSpacing;
        double tracking;
        double hanging;
        double opening;
        double residual;
        boolean relaxed;
        double cost;
        boolean last;
        int startOffset;
        int endOffset = -1;
        boolean hyphenated;
        int fitness;

        Line snapshot(Prepared p) {
            int sourceStart = p.sourceOffsets[start] + startOffset;
            int sourceEnd = p.sourceOffsets[end - 1]
                    + (endOffset < 0 ? p.words.get(end - 1).length() : endOffset);
            return new Line(start, end, width, natural, wordSpacing, tracking,
                    hanging, opening, residual, relaxed, cost, last, startOffset,
                    endOffset, hyphenated, fitness, sourceStart, sourceEnd);
        }
    }

    /** Measure with the same grapheme/cell model that Lanterna paints. */
    public static Prepared prepare(String text) {
        return prepare(text, TerminalTextUtils::displayWidth);
    }

    /**
     * Collapse ASCII whitespace, retain NBSP inside an indivisible word, and
     * cache repeated measurements. Explicit source hyphens between letters or
     * numbers are legal breakpoints without adding a glyph.
     */
    public static Prepared prepare(String text, ToDoubleFunction<String> measure) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(measure, "measure");
        List<String> words = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            words.add(matcher.group());
            starts.add(matcher.start());
        }
        int n = words.size();
        double[] widths = new double[n + 1];
        double[] characters = new double[n + 1];
        double[] endHangs = new double[n];
        double[] startHangs = new double[n];
        WordFragments[] hyphenation = new WordFragments[n];
        ToDoubleFunction<String> widthOf = cachedMeasure(measure);
        Map<String, Integer> counts = new HashMap<>();
        double space = widthOf.applyAsDouble(" ");
        if (space <= 0) {
            throw new IllegalArgumentException("Space width must be positive");
        }
        for (int i = 0; i < n; i++) {
            String word = words.get(i);
            double width = widthOf.applyAsDouble(word);
            Matcher punctuation = END_PUNCTUATION.matcher(word);
            if (punctuation.find()) {
                endHangs[i] = Math.min(width, widthOf.applyAsDouble(punctuation.group()));
            }
            Matcher quote = START_QUOTE.matcher(word);
            if (quote.find()) {
                startHangs[i] = Math.min(width, widthOf.applyAsDouble(quote.group()));
            }
            widths[i + 1] = widths[i] + width;
            characters[i + 1] = characters[i]
                    + counts.computeIfAbsent(word, ParagraphLayout::graphemeCount);
            TreeSet<Integer> explicit = explicitHyphens(word);
            if (!explicit.isEmpty()) {
                TreeSet<Integer> offsets = new TreeSet<>(explicit);
                offsets.add(0);
                offsets.add(word.length());
                hyphenation[i] = fragments(word, width, offsets, explicit, widthOf);
            }
        }
        return new Prepared(List.copyOf(words), starts.stream().mapToInt(Integer::intValue).toArray(),
                widths, characters, endHangs, startHangs, space, hyphenation, null, null);
    }

    private static ToDoubleFunction<String> cachedMeasure(ToDoubleFunction<String> measure) {
        Map<String, Double> cache = new HashMap<>();
        return text -> cache.computeIfAbsent(text, key -> {
            double width = measure.applyAsDouble(key);
            if (!Double.isFinite(width) || width < 0) {
                throw new IllegalArgumentException("Measured widths must be finite and nonnegative");
            }
            return width;
        });
    }

    private static int graphemeCount(String text) {
        return TextCharacter.fromString(text).length;
    }

    private static TreeSet<Integer> explicitHyphens(String word) {
        TreeSet<Integer> offsets = new TreeSet<>();
        if (word.indexOf('\u00a0') < 0) {
            Matcher matcher = EXPLICIT_HYPHEN.matcher(word);
            while (matcher.find()) {
                offsets.add(matcher.end());
            }
        }
        return offsets;
    }

    private static WordFragments fragments(String word, double wordWidth,
            TreeSet<Integer> boundaries, TreeSet<Integer> explicitAt,
            ToDoubleFunction<String> widthOf) {
        int[] offsets = boundaries.stream().mapToInt(Integer::intValue).toArray();
        int n = offsets.length;
        int size = Math.multiplyExact(n, n);
        double[] widths = new double[size];
        double[] hyphenWidths = new double[size];
        double[] characters = new double[size];
        boolean[] explicit = new boolean[n];
        for (int i = 0; i < n; i++) {
            explicit[i] = explicitAt.contains(offsets[i]);
        }
        for (int from = 0; from < n - 1; from++) {
            for (int to = from + 1; to < n; to++) {
                String text = word.substring(offsets[from], offsets[to]);
                int cell = from * n + to;
                widths[cell] = from == 0 && to == n - 1 ? wordWidth : widthOf.applyAsDouble(text);
                if (to < n - 1) {
                    hyphenWidths[cell] = explicit[to] ? widths[cell] : widthOf.applyAsDouble(text + "-");
                }
                characters[cell] = graphemeCount(text);
            }
        }
        return new WordFragments(offsets, widths, hyphenWidths, characters, explicit, null, 0);
    }

    /**
     * Add dictionary breaks, retaining explicit hyphens. Each fragment including
     * its generated hyphen is measured as a shaped unit. Breaks cannot split a
     * grapheme. NBSP words never reach the dictionary callback.
     */
    public static Prepared withHyphenation(Prepared p, Hyphenator hyphenate, WordMeasurer measure) {
        Objects.requireNonNull(hyphenate, "hyphenate");
        Objects.requireNonNull(measure, "measure");
        WordFragments[] hyphenation = p.hyphenation.clone();
        for (int index = 0; index < p.words.size(); index++) {
            String word = p.words.get(index);
            if (word.indexOf('\u00a0') >= 0) {
                continue;
            }
            List<String> parts = hyphenate.split(word, index);
            if (parts == null || parts.isEmpty() || parts.stream().anyMatch(part -> part == null || part.isEmpty())
                    || !String.join("", parts).equals(word)) {
                throw new IllegalArgumentException("Hyphenation must partition the source word");
            }
            if (parts.size() == 1) {
                continue;
            }
            TreeSet<Integer> graphemes = new TreeSet<>();
            int position = 0;
            graphemes.add(0);
            for (TextCharacter cell : TextCharacter.fromString(word)) {
                position += cell.getCharacterString().length();
                graphemes.add(position);
            }
            TreeSet<Integer> offsets = new TreeSet<>();
            offsets.add(0);
            position = 0;
            for (String part : parts) {
                position += part.length();
                if (!graphemes.contains(position)) {
                    throw new IllegalArgumentException("Hyphenation must not split a grapheme");
                }
                offsets.add(position);
            }
            TreeSet<Integer> explicit = explicitHyphens(word);
            offsets.addAll(explicit);
            int wordIndex = index;
            hyphenation[index] = fragments(word, p.widths[index + 1] - p.widths[index], offsets,
                    explicit, cachedMeasure(text -> measure.measure(text, wordIndex)));
        }
        return p.withFragments(hyphenation, p.startProtrusions, p.endProtrusions);
    }

    /**
     * Attach optical measurements after optional hyphenation. The callback sees
     * whole words, continuation suffixes and the generated hyphen. Leading
     * optical credit replaces quote credit; trailing credit takes the greater
     * of punctuation hanging and optical protrusion.
     */
    public static Prepared withOpticalMargins(Prepared p, OpticalMeasurer measure) {
        double[] starts = new double[p.words.size()];
        double[] ends = new double[p.words.size()];
        WordFragments[] hyphenation = p.hyphenation.clone();
        for (int index = 0; index < p.words.size(); index++) {
            String word = p.words.get(index);
            Margins margins = checkedMargins(measure.measure(word, index));
            starts[index] = margins.start;
            ends[index] = margins.end;
            WordFragments f = hyphenation[index];
            if (f != null) {
                double[] fragmentStarts = new double[f.offsets.length];
                for (int part = 0; part < f.offsets.length - 1; part++) {
                    fragmentStarts[part] = checkedMargins(measure.measure(word.substring(f.offsets[part]), index)).start;
                }
                double hyphenEnd = checkedMargins(measure.measure("-", index)).end;
                hyphenation[index] = new WordFragments(f.offsets, f.widths, f.hyphenWidths,
                        f.characters, f.explicit, fragmentStarts, hyphenEnd);
            }
        }
        return p.withFragments(hyphenation, starts, ends);
    }

    private static Margins checkedMargins(Margins margins) {
        if (margins == null || !Double.isFinite(margins.start) || margins.start < 0
                || !Double.isFinite(margins.end) || margins.end < 0) {
            throw new IllegalArgumentException("Optical margins must be finite and nonnegative");
        }
        return margins;
    }

    /**
     * Bounded, space-delimited left-to-right terminal prose: at most 400 words,
     * 16,384 UTF-16 units and 128 potential hyphens (16 per word). Excludes control/bidi text
     * and scripts requiring a different line-break model. This is an adapter
     * guard, not a restriction on the general measured engine.
     */
    public static boolean isTerminalProse(String text) {
        if (text == null || text.length() > 16384) {
            return false;
        }
        int words = 0;
        int hyphens = 0;
        int wordHyphens = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            boolean whitespace = cp == ' ' || cp == '\t' || cp == '\r' || cp == '\n' || cp == '\f';
            if (!whitespace && !inWord && ++words > 400) {
                return false;
            }
            inWord = !whitespace;
            if (whitespace) {
                wordHyphens = 0;
            }
            if ((cp == '-' || cp == 0x2010) && (++hyphens > 128 || ++wordHyphens > 16)) {
                return false;
            }
            if (cp == 0x061c || cp == 0x200e || cp == 0x200f) {
                return false;
            }
            byte direction = Character.getDirectionality(cp);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if ((!whitespace && Character.isISOControl(cp))
                    || (cp >= 0x202a && cp <= 0x202e) || (cp >= 0x2066 && cp <= 0x2069)
                    || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                    || script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
                    || script == Character.UnicodeScript.THAI || script == Character.UnicodeScript.LAO
                    || script == Character.UnicodeScript.KHMER || script == Character.UnicodeScript.MYANMAR) {
                return false;
            }
        }
        return true;
    }

    private static void validate(double[] widths, Options o) {
        if (widths.length == 0) {
            throw new IllegalArgumentException("At least one measure is required");
        }
        for (double width : widths) {
            if (!Double.isFinite(width) || width <= 0) {
                throw new IllegalArgumentException("Measures must be finite and positive");
            }
        }
        if (o.mode == null || o.ending == null) {
            throw new IllegalArgumentException("Fitting mode and ending model are required");
        }
        double[] numeric = {o.stretch, o.shrink, o.tracking, o.compressionPenalty,
                o.emergencyStretch, o.hanging, o.opening, o.protrusion, o.adjacentPenalty,
                o.lastLine, o.widowPenalty, o.hyphenPenalty, o.consecutiveHyphenPenalty,
                o.finalHyphenPenalty, o.explicitHyphenPenalty};
        for (double value : numeric) {
            if (!Double.isFinite(value) || value < 0) {
                throw new IllegalArgumentException("Policy values must be finite and nonnegative");
            }
        }
        if (o.shrink > 1 || o.lastLine > 1 || o.hanging > 1 || o.opening > 1 || o.protrusion > 1) {
            throw new IllegalArgumentException("Shrink, lastLine, hanging, opening and protrusion must be at most 1");
        }
    }

    private static double cube(double value) {
        return value * value * value;
    }

    private record FragmentMetrics(double natural, double chars, boolean last,
            double hanging, double opening, boolean continued,
            Double startProtrusion, double endProtrusion) {
    }

    private static Scratch fit(Prepared p, int start, int end, double width,
            Options o, Scratch out, boolean emergency, FragmentMetrics fragment) {
        int gaps = end - start - 1;
        double chars = fragment == null ? p.characters[end] - p.characters[start] + gaps : fragment.chars;
        double natural = fragment == null ? p.widths[end] - p.widths[start] + gaps * p.space : fragment.natural;
        boolean last = fragment == null ? end == p.words.size() : fragment.last;
        double endProtrusion = fragment == null ? value(p.endProtrusions, end - 1) : fragment.endProtrusion;
        double hanging = Math.max((fragment == null ? p.endHangs[end - 1] : fragment.hanging) * o.hanging,
                endProtrusion * o.protrusion);
        Double opticalStart = fragment == null ? optionalValue(p.startProtrusions, start) : fragment.startProtrusion;
        double opening = opticalStart == null
                ? (fragment == null ? p.startHangs[start] : fragment.opening) * o.opening
                : opticalStart * o.protrusion;
        double target = width + hanging + opening;
        double delta = last && natural <= target ? 0 : target - natural;
        double spaceBudget = gaps * p.space * (delta >= 0 ? o.stretch : o.shrink);
        double trackBudget = chars * o.tracking;
        double capacity = spaceBudget + trackBudget;
        double ratio = capacity != 0 ? Math.min(1, Math.abs(delta) / capacity) : 0;
        double sign = Math.signum(delta);
        double residual = delta - sign * ratio * capacity;
        double strain = emergency && delta > 0
                ? 100 * cube(delta / (capacity + width * o.emergencyStretch))
                : o.mode == Mode.BALANCED
                ? 100 * cube(ratio + Math.abs(residual) / Math.max(capacity, Math.max(gaps * p.space, p.space)))
                : 100 * cube(ratio);
        double missing = Math.max(0, o.lastLine * width - natural);
        double ending = 0;
        if (last && (fragment == null ? start > 0 : fragment.continued)) {
            ending = o.ending == Ending.FIT
                    ? o.widowPenalty / 300 * Math.min(10000,
                            100 * cube(missing / Math.max(p.space, gaps * p.space * o.stretch + trackBudget)))
                    : o.widowPenalty * Math.pow(missing / width, 2);
        }
        out.start = start;
        out.end = end;
        out.width = width;
        out.natural = natural;
        out.wordSpacing = gaps != 0 ? sign * ratio * spaceBudget / gaps : 0;
        out.tracking = sign * ratio * o.tracking;
        out.hanging = hanging;
        out.opening = opening;
        out.residual = residual;
        out.relaxed = false;
        out.last = last;
        out.startOffset = 0;
        out.endOffset = -1;
        out.hyphenated = false;
        double unbounded = capacity != 0 ? delta / capacity : delta == 0 ? 0 : Math.copySign(INFINITY, delta);
        out.fitness = unbounded < -0.5 ? 0 : unbounded <= 0.5 ? 1 : unbounded <= 1 ? 2 : 3;
        finish(out, p, o);
        double remaining = out.residual;
        double failure = Math.abs(remaining) > 0.01
                ? overflowCost(remaining, remaining < 0 ? o.mode : Mode.STRICT) : 0;
        out.cost = 1 + strain * (delta < 0 ? o.compressionPenalty : 1) + failure + ending;
        if (o.mode == Mode.BALANCED && o.emergencyStretch > 0 && !emergency
                && (delta > capacity * Math.cbrt(2) + 0.01 || Math.abs(remaining) > 0.01)) {
            out.cost = INFINITY;
        }
        return out;
    }

    private static double value(double[] values, int index) {
        return values == null ? 0 : values[index];
    }

    private static Double optionalValue(double[] values, int index) {
        return values == null ? null : values[index];
    }

    private static double overflowCost(double residual, Mode mode) {
        return mode == Mode.BALANCED ? 1e6 + residual * residual * 1e4 : 1e4 + residual * residual * 100;
    }

    private static void finish(Scratch line, Prepared p, Options o) {
        int gaps = line.end - line.start - 1;
        if (o.mode == Mode.STRICT || gaps == 0 || line.residual == 0) {
            return;
        }
        double spacing = Math.max(-p.space * o.shrink, line.wordSpacing + line.residual / gaps);
        double adjustment = spacing - line.wordSpacing;
        line.relaxed = Math.abs(adjustment) > 1e-9;
        line.residual -= adjustment * gaps;
        if (Math.abs(line.residual) < 1e-9) {
            line.residual = 0;
        }
        line.wordSpacing = spacing;
    }

    private static final class Shape {
        final double[] widths;
        final int fitnesses;
        final int states;
        final int origin;
        final double widest;

        Shape(double[] widths, Options options) {
            this.widths = widths;
            fitnesses = options.adjacentPenalty != 0 ? 4 : 1;
            states = Math.multiplyExact(fitnesses, widths.length);
            origin = fitnesses == 4 ? 1 : 0;
            widest = Arrays.stream(widths).max().orElseThrow();
        }

        int next(int line) {
            return Math.min(line + 1, widths.length - 1);
        }
    }

    public static Layout solve(Prepared p, double width) {
        return solve(p, new double[] {width}, new Options());
    }

    public static Layout solve(Prepared p, double width, Options policy) {
        return solve(p, new double[] {width}, policy);
    }

    /** Solve exactly within the first viable fitting pass; the last measure repeats. */
    public static Layout solve(Prepared p, double[] measures, Options policy) {
        double[] widths = measures.clone();
        Options o = new Options(policy);
        validate(widths, o);
        if (Arrays.stream(p.hyphenation).anyMatch(Objects::nonNull)) {
            return solveHyphenated(p, widths, o);
        }
        int n = p.words.size();
        Shape s = new Shape(widths, o);
        double[] costs = new double[Math.multiplyExact(n + 1, s.states)];
        int[] previous = new int[costs.length];
        int[] reachable = new int[n + 1];
        long candidates = 0;
        Scratch scratch = new Scratch();
        boolean emergency = false;
        double maxOpening = 0;
        for (int i = 0; i < n; i++) {
            maxOpening = Math.max(maxOpening, p.startProtrusions == null
                    ? p.startHangs[i] * o.opening : p.startProtrusions[i] * o.protrusion);
        }
        boolean monotone = p.space * (1 - o.shrink) >= o.tracking;
        for (int i = 0; i < n && monotone; i++) {
            monotone = p.widths[i + 1] - p.widths[i] >= (p.characters[i + 1] - p.characters[i]) * o.tracking;
        }
        for (int pass = 0; pass < 2; pass++) {
            emergency = pass == 1;
            Arrays.fill(costs, INFINITY);
            costs[s.origin] = 0;
            int count = 1;
            reachable[0] = 0;
            for (int end = 1; end <= n; end++) {
                for (int index = count - 1; index >= 0; index--) {
                    int start = reachable[index];
                    Scratch line = null;
                    for (int l = 0; l < widths.length; l++) {
                        int base = start * s.states + l * s.fitnesses;
                        if (!anyFinite(costs, base, s.fitnesses)) {
                            continue;
                        }
                        line = fit(p, start, end, widths[l], o, scratch, emergency, null);
                        candidates++;
                        updateCosts(costs, previous, base, end, start, l, s, line, o);
                    }
                    if (line == null) {
                        continue;
                    }
                    // Earlier prefixes cannot beat this lower bound only when
                    // maximum compression is monotone. Compare tight states alone:
                    // other fitness histories may still improve a later line.
                    double overflowBound = line.residual + s.widest - line.width + maxOpening - line.opening;
                    double bestTight = INFINITY;
                    for (int l = 0; l < widths.length; l++) {
                        bestTight = Math.min(bestTight, costs[end * s.states + l * s.fitnesses]);
                    }
                    if (monotone && overflowBound < -0.01
                            && ((!emergency && o.mode == Mode.BALANCED && o.emergencyStretch > 0)
                            || 1 + overflowCost(overflowBound, o.mode) >= bestTight)) {
                        break;
                    }
                }
                if (anyFinite(costs, end * s.states, s.states)) {
                    reachable[count++] = end;
                }
            }
            if (anyFinite(costs, n * s.states, s.states)) {
                break;
            }
        }
        List<Line> lines = new ArrayList<>();
        int terminal = cheapest(costs, n * s.states);
        for (int slot = terminal; slot >= s.states;) {
            int source = previous[slot];
            int start = source / s.states;
            int end = slot / s.states;
            Scratch line = fit(p, start, end, widths[source % s.states / s.fitnesses], o, scratch, emergency, null);
            line.cost += adjacentCost(start, source % s.fitnesses, line.fitness, o);
            lines.add(line.snapshot(p));
            slot = source;
        }
        Collections.reverse(lines);
        return new Layout(lines, costs[terminal], candidates);
    }

    private static double adjacentCost(int start, int previousFitness, int fitness, Options o) {
        return start > 0 && Math.abs(previousFitness - fitness) > 1 ? o.adjacentPenalty : 0;
    }

    private static void updateCosts(double[] costs, int[] previous, int base, int end,
            int start, int l, Shape s, Scratch line, Options o) {
        int target = end * s.states + s.next(l) * s.fitnesses + (s.fitnesses == 4 ? line.fitness : 0);
        for (int fitness = 0; fitness < s.fitnesses; fitness++) {
            int source = base + fitness;
            double cost = costs[source] + line.cost + adjacentCost(start, fitness, line.fitness, o);
            if (cost < costs[target]) {
                costs[target] = cost;
                previous[target] = source;
            }
        }
    }

    private static boolean anyFinite(double[] costs, int from, int count) {
        for (int i = from; i < from + count; i++) {
            if (costs[i] != INFINITY) {
                return true;
            }
        }
        return false;
    }

    private static int cheapest(double[] costs, int from) {
        int best = from;
        for (int i = from + 1; i < costs.length; i++) {
            if (costs[i] < costs[best]) {
                best = i;
            }
        }
        return best;
    }

    /** Normalized display text; generated hyphens do not alter the prepared source. */
    public static String lineText(Prepared p, Line line) {
        StringBuilder text = new StringBuilder();
        for (int i = line.start; i < line.end; i++) {
            if (i > line.start) {
                text.append(' ');
            }
            String word = p.words.get(i);
            text.append(word, i == line.start ? line.startOffset : 0,
                    i == line.end - 1 && line.endOffset >= 0 ? line.endOffset : word.length());
        }
        if (line.hyphenated) {
            text.append('-');
        }
        return text.toString();
    }

    private record BreakNode(int word, int part, int offset, boolean explicit) {
    }

    private record Fragment(double width, double chars) {
    }

    private static Fragment fragment(Prepared p, int word, int from, int to, boolean hyphen) {
        WordFragments f = p.hyphenation[word];
        if (f == null) {
            return new Fragment(p.widths[word + 1] - p.widths[word], p.characters[word + 1] - p.characters[word]);
        }
        int end = to < 0 ? f.offsets.length - 1 : to;
        int cell = from * f.offsets.length + end;
        boolean generated = hyphen && !f.explicit[end];
        return new Fragment(hyphen ? f.hyphenWidths[cell] : f.widths[cell], f.characters[cell] + (generated ? 1 : 0));
    }

    private static Scratch candidate(Prepared p, List<BreakNode> nodes, int start,
            int end, double width, Options o, Scratch scratch, boolean emergency) {
        BreakNode a = nodes.get(start);
        BreakNode b = nodes.get(end);
        boolean hyphen = b.offset > 0;
        boolean generated = hyphen && !b.explicit;
        int lastWord = hyphen ? b.word : b.word - 1;
        double natural;
        double chars;
        if (a.word == lastWord) {
            Fragment part = fragment(p, a.word, a.part, hyphen ? b.part : -1, hyphen);
            natural = part.width;
            chars = part.chars;
        } else {
            Fragment first = fragment(p, a.word, a.part, -1, false);
            Fragment last = fragment(p, lastWord, 0, hyphen ? b.part : -1, hyphen);
            int gaps = lastWord - a.word;
            natural = first.width + p.widths[lastWord] - p.widths[a.word + 1] + last.width + gaps * p.space;
            chars = first.chars + p.characters[lastWord] - p.characters[a.word + 1] + last.chars + gaps;
        }
        Double startProtrusion = a.offset > 0
                ? optionalValue(p.hyphenation[a.word].startProtrusions, a.part)
                : optionalValue(p.startProtrusions, a.word);
        double endProtrusion = hyphen ? p.hyphenation[lastWord].hyphenProtrusion : value(p.endProtrusions, lastWord);
        Scratch line = fit(p, a.word, lastWord + 1, width, o, scratch, emergency,
                new FragmentMetrics(natural, chars, end == nodes.size() - 1,
                        hyphen ? 0 : p.endHangs[lastWord], a.offset > 0 ? 0 : p.startHangs[a.word],
                        start > 0, startProtrusion, endProtrusion));
        line.startOffset = a.offset;
        line.endOffset = hyphen ? b.offset : -1;
        line.hyphenated = generated;
        boolean afterGenerated = a.offset > 0 && !a.explicit;
        line.cost += generated ? o.hyphenPenalty + (afterGenerated ? o.consecutiveHyphenPenalty : 0)
                : hyphen ? o.explicitHyphenPenalty : line.last && afterGenerated ? o.finalHyphenPenalty : 0;
        return line;
    }

    private static Layout solveHyphenated(Prepared p, double[] widths, Options o) {
        List<BreakNode> nodes = new ArrayList<>();
        nodes.add(new BreakNode(0, 0, 0, false));
        for (int word = 0; word < p.words.size(); word++) {
            WordFragments f = p.hyphenation[word];
            if (f != null) {
                for (int part = 1; part < f.offsets.length - 1; part++) {
                    nodes.add(new BreakNode(word, part, f.offsets[part], f.explicit[part]));
                }
            }
            nodes.add(new BreakNode(word + 1, 0, 0, false));
        }
        Shape s = new Shape(widths, o);
        double[] costs = new double[Math.multiplyExact(nodes.size(), s.states)];
        int[] previous = new int[costs.length];
        long candidates = 0;
        boolean emergency = false;
        Scratch scratch = new Scratch();
        // Unlike whole-word prefix sums, shaped fragments need not grow
        // monotonically. Do not use ordinary overflow pruning on this graph.
        for (int pass = 0; pass < 2; pass++) {
            emergency = pass == 1;
            Arrays.fill(costs, INFINITY);
            costs[s.origin] = 0;
            for (int end = 1; end < nodes.size(); end++) {
                for (int start = end - 1; start >= 0; start--) {
                    for (int l = 0; l < widths.length; l++) {
                        int base = start * s.states + l * s.fitnesses;
                        if (!anyFinite(costs, base, s.fitnesses)) {
                            continue;
                        }
                        Scratch line = candidate(p, nodes, start, end, widths[l], o, scratch, emergency);
                        candidates++;
                        updateCosts(costs, previous, base, end, start, l, s, line, o);
                    }
                }
            }
            if (anyFinite(costs, (nodes.size() - 1) * s.states, s.states)) {
                break;
            }
        }
        List<Line> lines = new ArrayList<>();
        int terminal = cheapest(costs, (nodes.size() - 1) * s.states);
        for (int slot = terminal; slot >= s.states;) {
            int source = previous[slot];
            int start = source / s.states;
            int end = slot / s.states;
            Scratch line = candidate(p, nodes, start, end, widths[source % s.states / s.fitnesses], o, scratch, emergency);
            line.cost += adjacentCost(start, source % s.fitnesses, line.fitness, o);
            lines.add(line.snapshot(p));
            slot = source;
        }
        Collections.reverse(lines);
        return new Layout(lines, costs[terminal], candidates);
    }
}
