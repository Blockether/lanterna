# FOLLOWUP — Blockether/lanterna fork

> **Why this fork exists, what's in it, what's *not* in it, and the
> exact tripwire that makes it disappear.**
> Read this first before touching the `vis/3.1.5` branch.

## TL;DR

| | |
|---|---|
| Upstream baseline | `lanterna-3.1.5` (mabe02/lanterna, Mar 2026) |
| Branch | `vis/3.1.5` |
| Tag | `vis-3.1.5` |
| Maven GAV | `com.blockether:lanterna:3.1.5-vis.2` |
| Distribution | Clojars (`https://repo.clojars.org/com/blockether/lanterna/3.1.5-vis.2/`) |
| Cherry-picked from upstream master | **PR #625** (2 commits) |
| Blockether-original patches | **BMP `Emoji_Presentation=Yes` width fix** (1 commit) |
| Consumer | `vis-tui` (`packages/vis-tui/deps.edn` in the `vis` monorepo) |
| Retirement trigger | First upstream lanterna release that contains PR #625 (likely 3.1.6 or 3.2.0). |

## Why the fork exists

`vis-tui` renders bubbles, the markdown table view, the directory
listing widget, etc. via `TextGraphics.putString` and the
underlying `StreamBasedTerminal.putString`. In stock lanterna 3.1.5
both methods iterate the input string `char`-by-`char` and encode
each `char` independently. For any code point above U+FFFF (every
non-BMP emoji), Java stores the code point as a UTF-16 surrogate
pair. Encoding each surrogate in isolation against the UTF-8
charset emits the encoder's replacement byte — `0x3F`, ASCII `?` —
*twice*, once per surrogate. Result: every emoji becomes the literal
two-character string `??` on a UTF-8 terminal that should be able to
render the glyph natively.

Reproducer (stock 3.1.5):

```bash
$ jshell --class-path lanterna-3.1.5.jar
jshell> import com.googlecode.lanterna.terminal.ansi.UnixTerminal
jshell> import java.nio.charset.Charset
jshell> var t = new UnixTerminal(System.in, System.out, Charset.defaultCharset())
jshell> t.putString("📁 hello")
?? hello
```

Reproducer (this fork — `3.1.5-vis.1`):

```
📁 hello
```

The bug, the existing umbrella issue, and the eventual fix all live
in upstream. The fix has been merged into `master` since 2026-03-08
but did **not** land in the 3.1.5 release tag (cut from
`release/3.1`, which the merge skipped). We aren't in a position to
wait for 3.1.6.

## What's actually in `vis/3.1.5`

Two cherry-picks on top of the `lanterna-3.1.5` tag, one Blockether-
original fix, plus a build metadata commit. Nothing else — no other
patches, no behavioural drift, no opportunistic clean-ups.

```
$ git log --oneline lanterna-3.1.5..vis-3.1.5
7fb5bdda fix: BMP emoji-presentation detection in TextCharacter.isDoubleWidth
0ec5be77 build: rename GAV to com.blockether:lanterna:3.1.5-vis.1
e4b1868e fix: if terminal supports UTF-8, then directly encode the string as a whole
b92c9afe fix: use `TextCharacter.fromString` to process double width characters like emojis
```

The two upstream `fix:` commits are bit-identical cherry-picks of
`a2b96159` and `c18e9ae4` (the body of mabe02/lanterna PR #625
authored by @mcarleio). The `build:` commit flips groupId / artifact
metadata for Clojars distribution. The Blockether-original `fix:`
commit (7fb5bdda) is described in the new section below. The Java
packages, class names, and public API are **unchanged** — this is a
drop-in replacement, callers swap a Maven coordinate, no imports move.

### The Blockether-original BMP-emoji-presentation fix (7fb5bdda)

`TextCharacter.isDoubleWidth()` upstream uses three heuristics in OR:
`isCharDoubleWidth` (CJK), `isEmoji` (anything not-printable / not-CJK
/ not-thai), and `length > 1` (multi-`char` graphemes catches
surrogate-pair SMP emoji + variation-selector sequences).

This combination misses **single-`char` BMP code points whose Unicode
property is `Emoji_Presentation=Yes`** — chars like

    ✅ U+2705 white heavy check mark
    ⭐ U+2B50 white medium star
    ⚡ U+26A1 high voltage
    ❌ U+274C cross mark
    ✨ U+2728 sparkles
    ☔ U+2614 umbrella with rain
    ☕ U+2615 hot beverage
    ⏰ U+23F0 alarm clock

They live in the Misc Symbols (`U+2600…U+26FF`) and Dingbats
(`U+2700…U+27BF`) blocks — not CJK — and as single BMP `char`s they
pass `isPrintableCharacter`, so `isEmoji` returns false. Result:
`isDoubleWidth = false`, lanterna's `putString` advances the cursor
by ONE column when painting them, the table grid drifts one column
left on every row that uses such an emoji, and the closing `┃`
separator gets overdrawn by cell content.

The new helper `isCharEmojiPresentation(char)` is a hand-rolled range
check against Unicode 15.1's `emoji-data.txt` filtered to BMP code
points with `Emoji_Presentation=Yes` — ~30 ranges, ~100 code points,
one early-exit range bound for the common path.

Explicitly NOT included: `Emoji=Yes` but `Emoji_Presentation=No`
chars like `❤` (U+2764 heavy black heart), `☀` (U+2600 black sun
with rays), `☂` (U+2602 umbrella). Those default to TEXT presentation
(one column) per Unicode and become wide only when followed by VS-16
(`U+FE0F`); the existing `length > 1` branch already handles that path.

Blockether-original because no upstream PR exists for this fix as of
2026-04-27. Worth submitting; the patch is small, well-scoped, and
benefits every lanterna consumer that renders emoji.

## What was deliberately NOT pulled

I audited every commit on `upstream/master` between the `lanterna-3.1.5`
tag and HEAD. Below is the complete inventory of merges, with the
verdict for each.

| Commit / PR | Subject | Decision | Reason |
|---|---|---|---|
| #625 | Allow printing emojis in StreamBasedTerminals and TextGraphics | **PULL** | The reason this fork exists. |
| 17fe3c0e | "should probably only be returning a single TextCharacter" | **SKIP** | Author (mabe02) self-admits in the commit message: *"I'm suspecting there's a corner case somewhere with Thai letters, but if so let's throw an exception for that."* That is a yellow flag in plain English. Also a **breaking API change** (`TextCharacter[]` → `TextCharacter`). vis-tui doesn't call `fromCharacter` so we get nothing for the risk. |
| #626 | Replace deprecated constructor in TextCharacter | SKIP | Touches `Tutorial03.java` only. Test code, no runtime effect. |
| #627 | Docs syntax highlighting | SKIP | Documentation. |
| #630 | Translation to screen coordinates (`ScreenTranslator`) | SKIP | New feature. We don't use it; pulling it would inject untested code paths into our `TextGraphics` chain. |
| #631 | xterm SGR | SKIP | Input-side enhancement; not on our hot path. |
| #632 | Swing/AWT mouse | SKIP | Wrong terminal backend (we use `UnixTerminal`). |
| #633, #634 | Patch-1 micro-fixes | SKIP | Not safety-critical. |
| #641 | Remove malicious link from docs | SKIP | Doc-only. |
| #645, #647 | Screen test fixes (Issue #645) | SKIP | Test-only. |
| #649 | SplitPanel patch | SKIP | We don't use SplitPanel. |
| #650, #651 | Patch for Issue #650 | SKIP | UI features we don't touch. |
| #652, #654 | Accelerator support | SKIP | UI features we don't touch. |
| #653 | Tree component | SKIP | New widget; we don't render Trees. |
| #655, #661 | SplitPanel enhancements | SKIP | We don't use SplitPanel. |
| #660, #662, #663 | Test/tooling improvements (TerminalPosition / TerminalSize / TerminalRectangle) | SKIP | Test-only. |
| 3dd69316 | Standardize enum constant casing (Issue #566) | SKIP | **Breaking change** to enum identifiers across the GUI layer. Forces a re-audit of every `Symbols.*`-style constant we reference. Zero functional benefit for our use case. |
| #575 (5917194e + 603a5975) | Close terminal when closing screen | SKIP | We never call `screen.close()` — we call `.stopScreen()` and let the JVM terminate. The fix activates a code path we don't use. Pulling it changes nothing for us; not pulling keeps the diff minimal. |
| 15d2b45e | Support control of terminal timeouts (`TERMINAL_SIZE_TIMEOUT`) | SKIP | Adds a system property knob. Default behaviour unchanged. We're not tuning timeouts. |

**Conclusion: PR #625 is the only post-3.1.5 commit on master that
is both relevant to vis-tui and net-positive.** The fork's diff
against upstream is therefore as small as humanly possible — three
commits, the third of which is build metadata only.

## Critique of PR #625 itself (we still pulled it)

The patch is correct for our environment but has three honest weaknesses
worth knowing about. None of them justifies *not* pulling the patch;
they justify **not extending it**.

### 1. `UTF8_REFERENCE == terminalCharset` is reference equality

```java
if (UTF8_REFERENCE == terminalCharset) {       // ==, not .equals
    writeToTerminal(string.getBytes(terminalCharset));
} else {
    /* fallback: per-char encoding */
}
```

`UTF8_REFERENCE` is `StandardCharsets.UTF_8`. `terminalCharset` is
whatever the `UnixTerminal` constructor was handed — for vis-tui,
`Charset.defaultCharset()`. The `==` works **in practice** because
`Charset.forName("UTF-8")` and the `defaultCharset()` lookup both
return the same JDK singleton when `LANG` resolves to a UTF-8
locale. But the contract is fragile:

- Anyone constructing the terminal with a custom `Charset` subclass
  named `"UTF-8"` falls into the slow per-char branch silently.
- `Charset.equals()` would be a one-character fix and the obvious
  correct call.

**Impact on vis-tui: zero.** We hand it the JDK singleton.

### 2. The fast path bypasses `convertToVT100`

The old per-char path called `translateCharacter()` which translates
Unicode box-drawing characters to ASCII fallbacks via
`Symbols.convertToVT100()` for terminals (think 1990s Solaris
consoles) that cannot render `┃ ┏ ┓` natively. The new fast path
hands the raw UTF-8 bytes straight to the stream and skips the
translation entirely.

For a modern UTF-8 terminal this is **correct and faster**.
For a legacy terminal it would be a regression, but a legacy terminal
wouldn't be configured with a UTF-8 default charset in the first
place, so it would never enter the fast path. Self-consistent.

**Impact on vis-tui: zero or positive.** Box-drawing chars look
better, not worse.

### 3. The fast path bypasses tab expansion

Stock `putCharacter('\t')` expanded tabs into spaces. The fast path
emits a raw `0x09` byte and lets the terminal interpret it as
"advance to the next tab stop", which in absolute-cursor screen
mode (which `TerminalScreen` uses) can move the cursor in
unexpected ways.

vis-tui sanitises strings via `primitives.clj/sanitize-for-lanterna`
which strips the control range `0x00..0x08, 0x0B, 0x0C, 0x0E..0x1F`
**but explicitly preserves `\t` (0x09)**. If the markdown renderer
ever pushes a literal tab into a bubble line, the rendered output
will diverge between stock 3.1.5 (expansion) and `3.1.5-vis.1`
(raw-tab passthrough).

**Impact on vis-tui: low** — lines reaching `put-str!` are already
laid out and tab-free in current code. Worth a regression-test if
markdown ever starts emitting tabs.

## Build + redeploy instructions

```bash
git clone git@github.com:Blockether/lanterna.git
cd lanterna
git checkout vis/3.1.5

# Build (needs JDK 8+ — the pom targets 1.8 bytecode regardless of build JDK)
mvn -DskipTests -Dgpg.skip -Dmaven.javadoc.skip=true clean package
ls target/lanterna-3.1.5-vis.2.jar    # the jar

# Deploy to Clojars (needs CLOJARS_USERNAME + CLOJARS_PASSWORD env vars;
# password = the deploy token under your Clojars account)
export CLOJARS_USERNAME=blockether-deployer
export CLOJARS_PASSWORD="$CLOJARS_DEPLOY_TOKEN"
clojure -Sdeps '{:deps {slipset/deps-deploy {:mvn/version "0.2.3"}}}' \
  -M deploy_to_clojars.clj
```

Bumping the patch version (e.g., to add another upstream cherry-pick):

1. Cherry-pick the additional commit(s) onto `vis/3.1.5` (or write a
   new Blockether-original commit and add a section to this FOLLOWUP).
2. Edit `pom.xml`: bump `<version>3.1.5-vis.N</version>`.
3. Update `deploy_to_clojars.clj` (it pins the jar filename — change
   `vis.N-1` → `vis.N`).
4. Move tag: `git tag -fa vis-3.1.5 -m '...'` then
   `git push --force origin refs/tags/vis-3.1.5`.
5. Build, deploy, then bump `packages/vis-tui/deps.edn` in the `vis`
   repo to `3.1.5-vis.N`.
6. Update this `FOLLOWUP.md` table at the top.

## Retirement plan

This fork dies the moment upstream cuts a release containing PR #625.
Steps to retire:

1. In `vis`, edit `packages/vis-tui/deps.edn`:
   ```clojure
   ;; restore:
   com.googlecode.lanterna/lanterna {:mvn/version "3.1.6"}   ;; or whatever
   ```
2. Run `./verify.sh` in `vis` to confirm no regressions.
3. Commit + push.
4. Optionally: tag `Blockether/lanterna` with a final note (`obsolete-vis-3.1.5`)
   and archive the repo via the GitHub UI. The Clojars artifact stays
   forever (Clojars doesn't allow deletes); that's fine — it's
   ~570KB of immutable bytes.

How to know upstream has shipped the fix:

```bash
mvn dependency:get -Dartifact=com.googlecode.lanterna:lanterna:LATEST -o 2>&1 | grep version
# Or just visit:
#   https://central.sonatype.com/artifact/com.googlecode.lanterna/lanterna
```

When the latest released version's jar contains the patched
`StreamBasedTerminal.putString` (the `if (UTF8_REFERENCE == terminalCharset)`
branch is present in the bytecode), retire.

## Provenance

- **Upstream PR**: <https://github.com/mabe02/lanterna/pull/625>
- **Upstream issue**: <https://github.com/mabe02/lanterna/issues/505>
- **Cherry-picked commits**: `a2b96159`, `c18e9ae4` (mabe02/lanterna `master`)
- **Author of the upstream fix**: @mcarleio (Marcel Carlé)
- **License**: LGPL-3.0 (unchanged from upstream — we are not relicensing)

## Audit log of what I checked

To make this auditable, the exact filters used during review:

```bash
# Every emoji-or-charset-flavoured commit on master since 3.1.5:
git log --oneline lanterna-3.1.5..upstream/master -- \
  src/main/java/com/googlecode/lanterna/TextCharacter.java \
  src/main/java/com/googlecode/lanterna/graphics/AbstractTextGraphics.java \
  src/main/java/com/googlecode/lanterna/terminal/ansi/StreamBasedTerminal.java \
  'src/main/java/com/googlecode/lanterna/screen/*.java'

# Every PR on the repo whose title hints at emoji/unicode/CJK/wide:
gh pr list --repo mabe02/lanterna --state all --limit 300 \
  --json number,title,author,state,mergedAt
# (programmatic title filter for: emoji|unicode|utf|double width|wide|
#  surrogate|codepoint|cjk|grapheme|breakiterator)
```

Both filters were applied on 2026-04-27. Re-run them before each
fork bump to make sure nothing new has landed.
