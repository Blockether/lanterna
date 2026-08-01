# Changelog

<!-- ══════════════════════════════════════════════════════════════════ -->
<!-- Blockether vis fork — see the `## Blockether fork` section below.     -->
<!-- ══════════════════════════════════════════════════════════════════ -->

## Blockether fork

> `com.blockether:lanterna` — a **superset** of `mabe02/lanterna 3.1.5`.
> This started life as a minimal two-cherry-pick emoji fix; it is now a
> materially larger fork carrying its own display-width engine **and** an
> inline-image subsystem. Drop-in for `com.googlecode.lanterna:lanterna:3.1.5`
> (same packages/classes) plus one added package.

### `3.1.5-vis.39`

- **`java.desktop` is gone — the fork no longer touches AWT at all.** The module is
  not even a `requires static` any more, so the jar links and runs on a JDK image
  built without `java.desktop` (and inside a GraalVM native image) without the
  AWT toolkit, fontconfig or the ImageIO reader SPI ever being initialised.
  - `TextColor.toColor()` (and its three implementations on `ANSI`, `Indexed` and
    `RGB`) is **removed** — it was already `@Deprecated` upstream precisely because
    it "adds a runtime dependency to the java.desktop module which isn't declared in
    the module descriptor of lanterna". Callers want `getRed()/getGreen()/getBlue()`.
  - `TerminalImage` no longer decodes or re-encodes anything: the `ImageIO` /
    `BufferedImage` / `Graphics2D` transcode path (`transcodePng`, the box-sizing
    resample, the `encodeKitty` crop overload's re-decode and the two transcode
    caches) and the `java.awt.headless` static initialiser are **removed**. What
    stays is pure byte work — protocol detection, intrinsic pixel-dimension sniffing
    from file headers, cell-box sizing and Kitty/iTerm2 base64 emission — so the
    caller supplies already-encoded PNG bytes and owns its own imaging stack.
  - New `TerminalImage.cellWidth()` / `cellHeight()` accessors expose the cell pixel
    size set through `setCellDimensions`, which a caller now needs to size its own
    rasterisation.
- `module-info` exports `com.googlecode.lanterna.terminal.image`, the fork's one
  added package — it was unexported since the descriptor moved out of
  `META-INF/versions/9` in `3.1.5-vis.37`, so modulepath consumers could not read it.

### `3.1.5-vis.38`

- **The Swing/AWT terminal emulator is gone.** The whole
  `com.googlecode.lanterna.terminal.swing` package (17 classes: `SwingTerminal`,
  `SwingTerminalFrame`, `AWTTerminal`, `AWTTerminalFrame`, the scrolling variants,
  `TerminalEmulator*Configuration`, `TerminalEmulatorPalette`,
  `TerminalInputMethodRequests`, `TerminalScrollController`) is deleted, together with
  its `exports` in `module-info` and the Swing-only manual demos in the test tree
  (`SwingTerminalTest`, `NewSwingTerminalTest`, `Scrolling{Swing,AWT}TerminalTest`,
  `Issue95`, `Issue613`). The consumer is a terminal application; it never opens a
  GUI window.
- `DefaultTerminalFactory` is now text-terminal only: `createTerminal()` always
  delegates to `createHeadlessTerminal()`, and `createTerminalEmulator()`,
  `createSwingTerminal()`, `createAWTTerminal()` and the
  `setTerminalEmulator{Color,Device,Font}Configuration` /
  `setTerminalEmulatorFrameAutoCloseTrigger` / `setForceAWTOverSwing` setters
  (all of which took Swing-package types) are removed.
  `setForceTextTerminal`, `setPreferTerminalEmulator` and
  `setAutoOpenTerminalEmulatorWindow` survive as no-ops so existing call sites compile.
- Jar: 618 KB → 550 KB, 397 → 362 entries, zero `javax/swing` references.
- `java.desktop` is still a `requires static`: `TerminalImage` rasterizes with
  `BufferedImage`/`Graphics2D` and `TextColor` keeps its `java.awt.Color` helper.

### `3.1.5-vis.37`

- **The fork is Java 25 only.** `maven.compiler.release` is now `25` and the
  multi-release layering is gone: `META-INF/versions/9` (`module-info`) and
  `META-INF/versions/22` (the FFM `TTYDeviceControl`) are folded into the ordinary
  `src/main/java` tree, and the jar no longer carries `Multi-Release: true`.
  There is no "unsupported" `TTYDeviceControl` stub any more — the
  `java.lang.foreign` implementation is the only one, matching the consumer
  (vis, GraalVM CE 25).
- The `/bin/stty` fallback in `UnixLikeTTYTerminal` **stays**: it covers an
  unopenable `/dev/tty`, unknown platforms and failing syscalls, not old JDKs.
- Measured on macOS/arm64 against a real pty: a save + canonical/echo/intr +
  restore cycle is **0.117 ms native vs 11.1 ms via stty (~95x)**, and a window-size
  query is **0.026 ms** (`ioctl`) versus an ANSI cursor round-trip.

### `3.1.5-vis.36`

- **New: native TTY control through the Java 22+ FFM API**
  (`com.googlecode.lanterna.terminal.ansi.TTYDeviceControl`, Blockether-original).
  `UnixLikeTTYTerminal` historically drove the controlling terminal by *forking
  `/bin/stty`* — a process per attribute change, six per acquire/restore cycle — and
  asked for the window size over ANSI (park the cursor at 5000,5000, read the reply),
  which silently degrades to 80x24 whenever the reply is late or swallowed.
  The class is shipped as a **multi-release layer** (`META-INF/versions/22`): the base
  `src/main/java` class is an unsupported stub, so the jar still builds and runs on
  Java 8+, while a Java 22+ runtime loads the `java.lang.foreign` implementation that
  calls `open`/`close`/`tcgetattr`/`tcsetattr` and `ioctl(TIOCGWINSZ)` directly
  (macOS and Linux `termios`/`winsize` offsets; `ioctl` linked with
  `Linker.Option.firstVariadicArg(2)`, which Apple silicon requires).
- **Every native call falls back to the old behaviour.** If the platform is unknown, the
  runtime is older than 22, the symbol lookup fails, or any call returns `-1`, the
  terminal transparently reverts to `stty` and the ANSI size query. Force the legacy
  path with `-Dcom.googlecode.lanterna.terminal.UnixTerminal.nativeTTY=false`.
- Callers must pass `--enable-native-access=ALL-UNNAMED` (JDK 24+ prints a restricted-
  method warning otherwise, and a future JDK will refuse the call). In a GraalVM native
  image the downcall `FunctionDescriptor`s must additionally be registered at build time
  via `RuntimeForeignAccess.registerForDowncall`.

### `3.1.5-vis.31`

- **Perf: ASCII fast paths for `truncateColumns` / `columnPrefixLength` /
  `hardSplitColumns`** (Blockether-original). The over-budget branch of each used to
  allocate a full per-grapheme `TextCharacter[]` (one object + one `String` per cell) via
  `fromString` even for pure printable-ASCII text — where truncation/folding is just a
  substring. Gated by the existing `allNarrowAscii` check, ASCII input now takes an
  allocation-free substring path (identical output; non-ASCII/grapheme/CJK/emoji still take
  the exact prior path). This is the hot column-clip family the chat-bubble layout walker
  runs per visible row: measured ~5000 -> ~200 ns/op on a 65-char line (~25x), with the
  per-line `TextCharacter[]` garbage eliminated.

### `3.1.5-vis.30`

- **New: `TerminalTextUtils.expandTabs(String, int)`** (Blockether-original) — hard-TAB
  expansion to fixed char-position tab stops, mirroring how `putString` advances a tab at
  paint time. Ports the vis TUI's last remaining pure-Clojure text primitive
  (`primitives/expand-tabs`, mapped over every code-block line by the markdown layout
  walker) into the shared Java engine. Tab-free input is returned as the SAME instance
  (allocation-free fast path). Tested + microbenched in `TerminalTextUtilsTest`.

### `3.1.5-vis.29`

- **New: `TerminalTextUtils.ansiTruncateColumns(String, int)`** (Blockether-original) —
  ANSI-SGR-aware column TRUNCATE (hard clip to a prefix), the CHOP sibling of
  `ansiFoldColumns`/`ansiSliceColumns`. Escapes kept inline verbatim, malformed control
  escapes render as a middle dot so a raw ESC never reaches the grapheme splitter. Ports
  the TUI's `render/truncate-ansi-cols`. Tested + microbenched.

### `3.1.5-vis.28`

- **New: terminal rule builders in `TerminalTextUtils`** (Blockether-original) —
  `repeat`, `joinedLine`, and `boxedLine` centralize horizontal runs,
  separators, and boxed border strings in Java `StringBuilder` code. Lets vis
  TUI reuse lanterna for table/dialog chrome instead of rebuilding the same
  `─┬─` / `┌─┐` strings through Clojure sequence helpers on every paint.

### `3.1.5-vis.27`

- **New: `TerminalTextUtils.clamp`** (Blockether-original) — a public
  canonical range clamp, `clamp(int,int,int)` + a `long` overload, for
  layout / scroll coordinate math. `Math.min`/lower-bound in one call;
  `gui2.TextEditBuffer` now delegates its private clamp here. Lets
  downstreams (vis channel-tui `primitives/clamp`) reuse one primitive
  implementation instead of re-rolling it per namespace.

### `3.1.5-vis.26`

- **New: ANSI-SGR-aware column fold/slice** in `TerminalTextUtils` (Blockether-
  original): `ansiFoldColumns` (styled SOFT-WRAP that re-opens the active SGR
  across each break) and `ansiSliceColumns` (styled horizontal `less -S` window
  clip). Same grapheme/EAW-aware column engine as `foldColumns` /
  `truncateColumns`; ESC-free input takes the plain fast path. Moves the vis TUI
  code-rail / pager kernels out of Clojure into all-primitive-int Java.

### `3.1.5-vis.25`

- **New: column-aware layout helpers** in `TerminalTextUtils` (Blockether-
  original), the same grapheme/EAW-aware text-flow family as `displayWidth` /
  `wordWrap` / `justify`: `padRight` / `padLeft` / `center` (pad-or-truncate to
  an exact column width), `truncateMiddle` (elide the middle of a path keeping
  head+tail behind a single `…`), `spaceBetween` / `spaceAround` (distribute
  items across a width, CSS-style), and `verticalCenterOffset`. Moves the TUI's
  pure column arithmetic out of Clojure into shared, allocation-light Java so
  one implementation backs both — measured the way the screen paints.

### `3.1.5-vis.24`

- **New: grapheme-cluster column measurement** in `TerminalTextUtils`
  (Blockether-original). Static, allocation-light helpers that measure/cut text
  by the exact same rule `AbstractTextGraphics.putString` paints by — segmenting
  into grapheme clusters via `TextCharacter.fromString` and taking each cluster's
  width from `TextCharacter.isDoubleWidth` (which owns the per-terminal VS-16
  policy). Consolidates measurement logic that downstream consumers (vis) were
  re-implementing in Clojure:
  - `displayColumns(String)` — terminal columns a string occupies; CJK/emoji = 2,
    ASCII = 1, inline-span sentinels (U+E110..E119) = 0. Pure-ASCII fast path.
  - `columnPrefixLength(String, int)` — char length of the longest column-fitting
    prefix that never splits a grapheme.
  - `truncateColumns(String, int)` — that prefix as a string (drops a straddling
    wide grapheme and pads one space so the width is exact).
  - `ellipsize(String, int, String)` — truncate with a trailing marker, reserving
    the marker's own column width.
  - `sanitizeControlChars(String)` — replace C0 control bytes with `/` (identity,
    no allocation, when already clean) so a stray `\n` can't crash a render.

### `3.1.5-vis.23`

- **New: inline terminal images** — `com.googlecode.lanterna.terminal.image.TerminalImage`
  (Blockether-original). A static, thread-safe, headless-safe, JDK-8 utility:
  - **Graphics-capability detection** — `detectCapabilities(env)` sniffs the
    inline-image `Protocol` (`KITTY` for kitty/Ghostty/WezTerm/Warp, `ITERM2`
    for iTerm2) from the environment; tmux/screen report none (they mangle
    pass-through). `isGraphicalTerminal()` / `isGraphicalTerminal(env)` collapse
    that to a boolean so callers can branch **graphical vs non-graphical**
    terminals and fall back to a text card.
  - **Intrinsic pixel-dimension sniffing** — `imageDimensions` / `probeDimensions`
    read only a file head to get `[w,h]` for png/jpeg/gif/webp/bmp (no full decode).
  - **Aspect-preserving cell-box sizing** — `cellSize`, using the reported
    terminal cell pixel size (`setCellDimensions`).
  - **Escape encoding** — `encodeKitty` (with 4096-byte `m=0/1` chunking) and
    `encodeIterm2`, plus `readBase64` and `transcodePngBase64` (AWT/ImageIO,
    Kitty `f=100` is PNG-only), each mtime+size cached so a scroll that
    re-emits an image doesn't re-read/re-encode.
  - Ported from vis's `terminal-image.clj` (itself a port of pi's
    `terminal-image.ts`); vis now delegates to this class.
  - Correctness + performance covered by `TerminalImageTest` and
    `TerminalImageBenchmarkTest` (`src/test`).
- Display-width engine (from earlier `vis.*` builds): PR #625 emoji rendering,
  BMP `Emoji_Presentation=Yes` + astral emoji = 2 cols, VS-15/VS-16 handling,
  geometric/symbol glyphs = 1 col, EAW=A narrow by default, EAW=W/F table,
  control-char degradation, scroll-ghost fix, `TerminalTextUtils` text-flow
  helpers (`foldColumns`), and Apple Terminal.app width mode.

---



## Table of contents
* [**3.0.0**](#3.0.0)
* [2.1.9](#2.1.9)
* [2.1.8](#2.1.8)
* [2.1.7](#2.1.7)
* [2.1.6](#2.1.6)
* [2.1.5](#2.1.5)
* [2.1.3](#2.1.3)
* [2.1.2](#2.1.2)
* [2.1.1](#2.1.1)
* [**2.1.0**](#2.1.0)
* [2.0.4](#2.0.4)
* [2.0.3](#2.0.3)
* [2.0.2](#2.0.3)
* [2.0.1](#2.0.1)
* [**2.0.0**](#2.0.0)

## 3.0.0
Lanterna 3 is a large, and probably final, update to the Lanterna library. Many parts have been completely rewritten and the parts not rewritten have been touched in at least some way. The reason for this major overhaul is to finally get it 'right' and fix all those API mistakes that have been highlighted over the years since Lanterna was first published.

This section can in no way summarize all the changes but will try to highlight some of the new features and redesigns.
Please note that Lanterna 3 is **not** API compatible with Lanterna 2.X and earlier.

**NOTE**: Lanterna 3 is still under development. The majority of the features below are implemented but not all.

## Added
* Proper support for CJK characters and handling of them
* New GUI system: The old GUI system has been deprecated and a new one is replacing it, giving you much more control over how you want your GUI to look. You can do any kind of old-school interface, not just dialog-based ones and even things like multi-tasking windows if you like. Please note that this is currently under development.
* New `SwingTerminal`: `SwingTerminal` in Lanterna 2.X was limited in many ways. For Lanterna 3.0 some of those limitations have been addressed. The actual class is no longer a `JFrame` but a `JComponent`, meaning you can easily embed it into any Swing application. Furthermore, it does not require to be run in private mode anymore. You can switch between normal and private mode as you like and it will keep track of the content. Additionally, it finally supports a backlog history and scrolling. A helper class, `ScrollingSwingTerminal`, can easily get you started with this. If you want the classic behaviour there is `SwingTerminalFrame` which behaves much like `SwingTerminal` used to.
* Telnet server: In addition to the terminal implementations that have been around since the earlier builds of Lanterna, version 3 introduces a Telnet server class that allows you to program multiple terminals against clients connecting in through standard Telnet. A small subset of the Telnet protocol is implemented so far, however, it supports features such as window resizing, line mode setting and echo control.
* `ScreenWriter` now supports not just text and filled rectangles but also lines and both filled and unfilled triangles.

## Changed
* Made `Screen` an interface and cleaned up its API. The default implementation behaves like `Screen` used to with improvements such as full color support
* The code and API more closely follows Java conventions on naming and style

## 2.1.9
### Added
* Better ESC key detection
* Enable EOF 'key' when the input stream is closed (requires setting system property 'com.googlecode.lanterna.enable-eof' to 'true')
* `TextBox` now accepts input of non-Latin characters

### Changed
* Better ESC key detection
* Regression fixed with high CPU load when opening a window with no interactable components
* `KeyMappingProfile` patterns now public

## 2.1.8
### Added
* Ability to set the fill character of `TextBox` components (other than space)
* Ability to disable shadows for windows
* Added a file dialog component
* Added a method to make it easier to wrap components in a border
* Added `SwingTerminal` function key support
* Window-deriving classes can inspect which component has input focus

### Changed
* Input focus bug fixes
* `InputDecoder` fixes backported from master branch

## 2.1.7
### Added
* Added support for the PageUp, PageDown, Home and End keys inside `AbstractListBox` and its subclasses

### Changed
* Change visibility of `LayoutParameter` constructor to public, making it easier to create custom layout managers
* Fixed `TextArea` crash on pressing End when horizontal size is too big
* Miscellaneous bug fixes
* Terminals will remember if they are in private mode and will not attempt to enter twice
* `Screen` will drain the input queue upon exiting

## 2.1.6
### Added
* Added an experimental `TextArea`, a user-contributed component
* Added `Screen.updateScreenSize()` to manually check and update internal data structures, allowing you to redraw the screen before calling `Screen.refresh()`
* Proper `Key.equals(...)` and `Key.hashCode()` methods
* Proper `TerminalPosition.equals(...)` and `TerminalPosition.hashCode()` methods

### Changed
* Fixed a deadlock in `GUIScreen`
* `ActionListBox` has a new parameter that closes the dialog before running the selected `Action`
* `SwingTerminal` AWT threading fixes

## 2.1.5
### Added
* Added a new method to invalidate the `Screen` buffer and force a complete redraw

### Changed
* Visibility changed on `GUIScreen` to make it easier to extend

## 2.1.3
### Added
* Customization of screen padding character
* More input key combinations detecting ALT down

### Changed
* Background color fix with `Screen`
* Expanded `Table` API
* Improved (but still incomplete) CJK character handling
* OS X input compatibility fix

## 2.1.2
### Added
* `RadioCheckBoxList.getCheckedItem()`

### Changed
* Enhanced restoration of the terminal control codes (especially on Solaris)
* Fixed a bug that occurred when `SwingTerminal` is reduced to 0 rows
* Fixed a bug that prevented the cursor from becoming visible again after leaving private mode
* `ActionListDialog` now increases in size as you add items
* `TextBox` can now tell you the current edit cursor position

## 2.1.1
### Added
* Re-added `GUIScreen.closeWindow()` (as deprecated)
* Re-added `Panel.setBetweenComponentsPadding(...)` (as deprecated)

### Changed
* Owner window can now be correctly derived from a component
* Classes extending `AbstractListBox` now follow the preferred size override correctly

### Added
* Added a new component, `ActivityIndicator`
* Added support for showing and hiding the text cursor
* Included ANSI colour palettes for the `SwingTerminal` to mimic the appearance of several popular terminal emulators
* Introduced the `BorderLayout` layout managed
* Support 8-bit and 24-bit colours (not supported by all terminal emulators)
* Support detection of CTRL and ALT key status
* `GUIScreen` backgrounds can now be customized

### Changed
* Close windows using `Window.close()` instead of `GUIScreen.closeWindow(...)`
* Generalized component alignment
* GUI windows can now be display in full-screen mode, taking up the entire terminal
* Lots of bug fixes
* Reworked GUI layout system
* Reworked the theme system
* Window size is overridable
* `SwingTerminal` now uses a new class, `TerminalAppearance`, to retrieve the visual settings, such as fonts and colours

### Removed
* Removed dependencies on proprietary Sun API

## 2.1.0
2.1.X is **not** strictly API compatible with 2.0.X but compared to going from 1.0.X to 2.0.X there will be fewer API breaking changes

## 2.0.4
### Added
* The PageUp, PageDown, Home, and End keys now work in the `TextArea` component

### Changed
* Adding rows to a `Table` will trigger the screen to redraw
* Improved API for `RadioCheckBoxList`

## 2.0.3
### Added
* Added experimental support for F1-F12 keys
* `TextArea` can now be modified (experimental feature)

### Changed
* Font fixes. Hopefully it will look better on Linux now
* Invisible components no longer receive focus
* The size policies are working better now but they are still somewhat mysterious. I will try to come up with something better for the 2.1.0 release

### What about 2.0.2?
There is no 2.0.2. I did a mistake staging the new release and had to start over again but 2.0.2 had already been tagged in Mercurial so I could not re-release it. Instead we skipped a number and landed on 2.0.3

## 2.0.1
### Added
* Added `Screen.clear()` that allows resetting the content of the screen
* Added `Terminal.getTerminalSize()` to synchronously retrieve the current size of the terminal
* Added new overloads so that you can specify a separate font to use for bold text in `SwingTerminal`
* `SwingTerminal` will now render underlined text
* `SwingTerminal` will expose its internal `JFrame` through a new method `getJFrame()`, allowing you to set a custom title, icon, image list etc.

### Changed
* `queryTerminalSize()` has been marked as deprecated but will still work as before
* `TextBox` and `PasswordBox` constructors that did not take a width parameter were broken, fixed and changed so that the initial size (unless forced) will be at least 10 columns wide

## 2.0.0
### Added
* Added a new facade class, `TerminalFacade`, which provides some convenience methods for creating terminal objects
* Added experimental, but not very functional, support for Cygwin
* Expanded `Interactable.Result` and `Interactable.FocusChangeDirection` to allow focus switching in four directions instead of only two
* Introduced `AbstractListBox` which has standardized the format and the methods of the list-based GUI elements
* Mavenized the project, will try to push it to Maven Central somehow

### Changed
* ~~Moved `com.googlecode.lanterna.TerminalFactory` to `com.googlecode.lanterna.terminal` where it belongs~~
* Moved `Terminal.addInputProfile(...)` to `InputProvider`
* Moved `Terminal.Style` to an outer class in `com.googlecode.lanterna.screen`
* Moved `SwingTerminal` to `com.googlecode.lanterna.terminal.swing`
* Moved `Terminal.setCBreak(...)` and `Terminal.setEcho(...)` into `ANSITerminal`. You probably don't need to call these directly anyway, since they are automatically called for the `UnixTerminal` when entering private mode
* Rearranged the `Terminal` hierarchy. This is mostly internal but you might have been using `CommonUnixTerminal` before which is now known as `com.googlecode.lanterna.terminal.text.UnixTerminal`
* Renamed the project's package name from `org.lantern` to `com.googlecode.lanterna`
* Renamed `LanternException` to `LanternaException` for consistency
* `LanternaException` is now a `RuntimeException` since `IOException`s coming from stdin and stdout are quite rare
* Renamed some enums and an internal class in `Theme`. You probably will not be affected by this unless you have defined your own theme

### Removed
* Removed `LanternTerminal` and `TerminalFactory` as they were quite confusing and not really necessary
* Removed `ListBox` as there is not much purpose for it in this environment
* Removed `RadioCheckBox` and `RadioCheckBoxGroup`. `RadioCheckBoxList` acts as a replacement
* Removed `TermInfo` classes (they did not really work so hopefully no one was using them)

### Maven
Starting with the 2.0.0 release, Lanterna has been using Maven and the Sonatype OSS repository which is synchronized with Maven Central. Please see the [Maven information page](Maven.md) for more details
