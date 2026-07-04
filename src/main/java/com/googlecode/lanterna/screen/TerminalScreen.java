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
package com.googlecode.lanterna.screen;

import com.googlecode.lanterna.*;
import com.googlecode.lanterna.graphics.Scrollable;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.TerminalResizeListener;

import java.io.IOException;
import java.util.EnumSet;

/**
 * This is the default concrete implementation of the Screen interface, a buffered layer sitting on top of a Terminal.
 * If you want to get started with the Screen layer, this is probably the class you want to use. Remember to start the
 * screen before you can use it and stop it when you are done with it. This will place the terminal in private mode
 * during the screen operations and leave private mode afterwards.
 * @author martin
 */
public class TerminalScreen extends AbstractScreen {
    private final Terminal terminal;
    private boolean isStarted;
    private boolean fullRedrawHint;
    private ScrollHint scrollHint;

    /**
     * Creates a new Screen on top of a supplied terminal, will query the terminal for its size. The screen is initially
     * blank. The default character used for unused space (the newly initialized state of the screen and new areas after
     * expanding the terminal size) will be a blank space in 'default' ANSI front- and background color.
     * <p>
     * Before you can display the content of this buffered screen to the real underlying terminal, you must call the
     * {@code startScreen()} method. This will ask the terminal to enter private mode (which is required for Screens to
     * work properly). Similarly, when you are done, you should call {@code stopScreen()} which will exit private mode.
     *
     * @param terminal Terminal object to create the DefaultScreen on top of
     * @throws java.io.IOException If there was an underlying I/O error when querying the size of the terminal
     */
    public TerminalScreen(Terminal terminal) throws IOException {
        this(terminal, DEFAULT_CHARACTER);
    }

    /**
     * Creates a new Screen on top of a supplied terminal, will query the terminal for its size. The screen is initially
     * blank. The default character used for unused space (the newly initialized state of the screen and new areas after
     * expanding the terminal size) will be a blank space in 'default' ANSI front- and background color.
     * <p>
     * Before you can display the content of this buffered screen to the real underlying terminal, you must call the
     * {@code startScreen()} method. This will ask the terminal to enter private mode (which is required for Screens to
     * work properly). Similarly, when you are done, you should call {@code stopScreen()} which will exit private mode.
     *
     * @param terminal Terminal object to create the DefaultScreen on top of.
     * @param defaultCharacter What character to use for the initial state of the screen and expanded areas
     * @throws java.io.IOException If there was an underlying I/O error when querying the size of the terminal
     */
    public TerminalScreen(Terminal terminal, TextCharacter defaultCharacter) throws IOException {
        super(terminal.getTerminalSize(), defaultCharacter);
        this.terminal = terminal;
        this.terminal.addResizeListener(new TerminalScreenResizeListener());
        this.isStarted = false;
        this.fullRedrawHint = true;
    }

    @Override
    public synchronized void startScreen() throws IOException {
        if(isStarted) {
            return;
        }

        isStarted = true;
        getTerminal().enterPrivateMode();
        getTerminal().getTerminalSize();
        getTerminal().clearScreen();
        this.fullRedrawHint = true;
        TerminalPosition cursorPosition = getCursorPosition();
        if(cursorPosition != null) {
            getTerminal().setCursorVisible(true);
            getTerminal().setCursorPosition(cursorPosition.getColumn(), cursorPosition.getRow());
        } else {
            getTerminal().setCursorVisible(false);
        }
    }

    @Override
    public void stopScreen() throws IOException {
        stopScreen(true);
    }
    
    public synchronized void stopScreen(boolean flushInput) throws IOException {
        if(!isStarted) {
            return;
        }

        if (flushInput) {
            //Drain the input queue
            KeyStroke keyStroke;
            do {
                keyStroke = pollInput();
            }
            while(keyStroke != null && keyStroke.getKeyType() != KeyType.EOF);
        }

        getTerminal().exitPrivateMode();
        isStarted = false;
    }

    @Override
    public synchronized void refresh(RefreshType refreshType) throws IOException {
        if(!isStarted) {
            return;
        }
        if((refreshType == RefreshType.AUTOMATIC && fullRedrawHint) || refreshType == RefreshType.COMPLETE) {
            refreshFull();
            fullRedrawHint = false;
        }
        else if(refreshType == RefreshType.AUTOMATIC &&
                (scrollHint == null || scrollHint == ScrollHint.INVALID)) {
            double threshold = getTerminalSize().getRows() * getTerminalSize().getColumns() * 0.75;
            if(getBackBuffer().isVeryDifferent(getFrontBuffer(), (int) threshold)) {
                refreshFull();
            }
            else {
                refreshByDelta();
            }
        }
        else {
            refreshByDelta();
        }
        getBackBuffer().copyTo(getFrontBuffer());
        TerminalPosition cursorPosition = getCursorPosition();
        if(cursorPosition != null) {
            getTerminal().setCursorVisible(true);
            //If we are trying to move the cursor to the padding of a double-width character, put it on the actual character instead
            if(cursorPosition.getColumn() > 0 &&
                            getFrontBuffer().getCharacterAt(cursorPosition.withRelativeColumn(-1)).isDoubleWidth()) {
                getTerminal().setCursorPosition(cursorPosition.getColumn() - 1, cursorPosition.getRow());
            }
            else {
                getTerminal().setCursorPosition(cursorPosition.getColumn(), cursorPosition.getRow());
            }
        } else {
            getTerminal().setCursorVisible(false);
        }
        getTerminal().flush();
    }

    private void useScrollHint() throws IOException {
        if (scrollHint == null) { return; }

        try {
            if (scrollHint == ScrollHint.INVALID) { return; }
            Terminal term = getTerminal();
            if (term instanceof Scrollable) {
                // just try and see if it cares:
                scrollHint.applyTo( (Scrollable)term );
                // if that didn't throw, then update front buffer:
                scrollHint.applyTo( getFrontBuffer() );
            }
        }
        catch (UnsupportedOperationException uoe) { /* ignore */ }
        finally { scrollHint = null; }
    }

    /**
     * Cached once: {@code SGR.values()} allocates a fresh array on every call,
     * and the old delta loop called it (plus {@code getModifiers()}'s defensive
     * EnumSet copy) PER CELL — tens of thousands of allocations per repaint.
     */
    private static final SGR[] ALL_SGRS = SGR.values();

    /**
     * Streaming emitter for {@link #refreshByDelta()}: collects horizontally
     * adjacent, same-style changed cells into ONE {@code putString} run instead
     * of one terminal write per cell. Tracks the physical cursor and the active
     * fg/bg/SGR state so escapes are only emitted on actual change, exactly like
     * the old per-cell loop — just per RUN. Nothing (not even the initial
     * {@code resetColorAndSGR}) is written when no cell changed.
     */
    private final class DeltaEmitter {
        private final StringBuilder run = new StringBuilder(80);
        private boolean emittedAnything = false;
        private int cursorColumn = -1;
        private int cursorRow = -1;
        private TextColor currentForeground = null;
        private TextColor currentBackground = null;
        private EnumSet<SGR> currentSGR = null;
        private TextCharacter runStyle = null;
        private int runColumn = -1;
        private int runRow = -1;
        private int runWidth = 0;

        void emit(int column, int row, TextCharacter character) throws IOException {
            int width = character.isDoubleWidth() ? 2 : 1;
            if(runStyle != null && row == runRow && column == runColumn + runWidth
                    && character.styleEquals(runStyle)) {
                run.append(character.getCharacterString());
                runWidth += width;
                return;
            }
            flushRun();
            runStyle = character;
            runColumn = column;
            runRow = row;
            runWidth = width;
            run.append(character.getCharacterString());
        }

        void flushRun() throws IOException {
            if(runStyle == null) {
                return;
            }
            if(!emittedAnything) {
                emittedAnything = true;
                getTerminal().resetColorAndSGR();
                currentSGR = EnumSet.noneOf(SGR.class);
                // colors left null so the first run always sets them explicitly
            }
            if(cursorColumn != runColumn || cursorRow != runRow) {
                getTerminal().setCursorPosition(runColumn, runRow);
            }
            if(!runStyle.getForegroundColor().equals(currentForeground)) {
                getTerminal().setForegroundColor(runStyle.getForegroundColor());
                currentForeground = runStyle.getForegroundColor();
            }
            if(!runStyle.getBackgroundColor().equals(currentBackground)) {
                getTerminal().setBackgroundColor(runStyle.getBackgroundColor());
                currentBackground = runStyle.getBackgroundColor();
            }
            EnumSet<SGR> wantedSGR = runStyle.getModifiers();   // ONE copy per run
            if(!wantedSGR.equals(currentSGR)) {
                for(SGR sgr: ALL_SGRS) {
                    boolean want = wantedSGR.contains(sgr);
                    boolean have = currentSGR.contains(sgr);
                    if(want && !have) {
                        getTerminal().enableSGR(sgr);
                    }
                    else if(!want && have) {
                        getTerminal().disableSGR(sgr);
                    }
                }
                currentSGR = wantedSGR;
            }
            getTerminal().putString(run.toString());
            cursorColumn = runColumn + runWidth;
            cursorRow = runRow;
            run.setLength(0);
            runStyle = null;
            runWidth = 0;
        }
    }

    private void refreshByDelta() throws IOException {
        TerminalSize terminalSize = getTerminalSize();
        int rows = terminalSize.getRows();
        int columns = terminalSize.getColumns();

        useScrollHint();

        // Single row-major pass: diff back vs front buffer and stream changed
        // cells straight into the run emitter. The old implementation staged
        // every changed cell in a TreeMap<TerminalPosition, TextCharacter>
        // (boxed key + red-black insert per cell) and then re-diffed style
        // PER CELL with SGR.values()+getModifiers() copies — on a transcript
        // shift that was ~10k map inserts and ~90k EnumSet/array allocations
        // per frame. The emitter preserves the exact wire semantics: cursor
        // moves only on run breaks, colors/SGR only on change, and the
        // double-width ghost rules below match the fork's TreeMap-overwrite
        // behaviour.
        DeltaEmitter emitter = new DeltaEmitter();
        for(int y = 0; y < rows; y++) {
            for(int x = 0; x < columns; ) {
                TextCharacter backBufferCharacter = getBackBuffer().getCharacterAt(x, y);
                TextCharacter frontBufferCharacter = getFrontBuffer().getCharacterAt(x, y);
                if(backBufferCharacter == frontBufferCharacter) {
                    // Identity fast path: copyTo() after each refresh aliases the
                    // buffers, so untouched cells are the SAME instance — skip the
                    // field-by-field equals entirely.
                    x++;
                    continue;
                }
                if(backBufferCharacter.isDoubleWidth()) {
                    // The trailing (right) half of a double-width glyph lives in
                    // the next column but is never emitted on its own — the
                    // 2-cell glyph paints over it. BUT if that trailing cell
                    // CHANGED this frame (content scrolled out from under a
                    // now-wide position), the terminal can keep a stale
                    // half-glyph ghost there, so re-emit the wide char whenever
                    // its trailing cell differs.
                    boolean trailingChanged = x + 1 < columns
                            && !getBackBuffer().getCharacterAt(x + 1, y)
                                    .equals(getFrontBuffer().getCharacterAt(x + 1, y));
                    if(trailingChanged || !backBufferCharacter.equals(frontBufferCharacter)) {
                        emitter.emit(x, y, backBufferCharacter);
                    }
                    x += 2;    // Skip the trailing padding
                    continue;
                }
                boolean changed = !backBufferCharacter.equals(frontBufferCharacter);
                if(changed) {
                    emitter.emit(x, y, backBufferCharacter);
                }
                if(frontBufferCharacter.isDoubleWidth() && x + 1 < columns) {
                    // Front was double-width here but back is narrow: the glyph's
                    // stale right half can survive in column x+1. If that cell
                    // repaints anyway (back != front there) the normal emission
                    // handles it next iteration; otherwise blank the ghost with
                    // a space in the old glyph's style.
                    if(getBackBuffer().getCharacterAt(x + 1, y)
                            .equals(getFrontBuffer().getCharacterAt(x + 1, y))) {
                        emitter.emit(x + 1, y, frontBufferCharacter.withCharacter(' '));
                        x += 2;
                        continue;
                    }
                }
                x++;
            }
        }
        emitter.flushRun();
    }


    private void refreshFull() throws IOException {
        getTerminal().setForegroundColor(TextColor.ANSI.DEFAULT);
        getTerminal().setBackgroundColor(TextColor.ANSI.DEFAULT);
        getTerminal().clearScreen();
        getTerminal().resetColorAndSGR();
        scrollHint = null; // discard any scroll hint for full refresh

        EnumSet<SGR> currentSGR = EnumSet.noneOf(SGR.class);
        TextColor currentForegroundColor = TextColor.ANSI.DEFAULT;
        TextColor currentBackgroundColor = TextColor.ANSI.DEFAULT;
        for(int y = 0; y < getTerminalSize().getRows(); y++) {
            getTerminal().setCursorPosition(0, y);
            int currentColumn = 0;
            for(int x = 0; x < getTerminalSize().getColumns(); x++) {
                TextCharacter newCharacter = getBackBuffer().getCharacterAt(x, y);
                if(newCharacter.equals(DEFAULT_CHARACTER)) {
                    continue;
                }

                if(!currentForegroundColor.equals(newCharacter.getForegroundColor())) {
                    getTerminal().setForegroundColor(newCharacter.getForegroundColor());
                    currentForegroundColor = newCharacter.getForegroundColor();
                }
                if(!currentBackgroundColor.equals(newCharacter.getBackgroundColor())) {
                    getTerminal().setBackgroundColor(newCharacter.getBackgroundColor());
                    currentBackgroundColor = newCharacter.getBackgroundColor();
                }
                for(SGR sgr: SGR.values()) {
                    if(currentSGR.contains(sgr) && !newCharacter.getModifiers().contains(sgr)) {
                        getTerminal().disableSGR(sgr);
                        currentSGR.remove(sgr);
                    }
                    else if(!currentSGR.contains(sgr) && newCharacter.getModifiers().contains(sgr)) {
                        getTerminal().enableSGR(sgr);
                        currentSGR.add(sgr);
                    }
                }
                if(currentColumn != x) {
                    getTerminal().setCursorPosition(x, y);
                    currentColumn = x;
                }
                getTerminal().putString(newCharacter.getCharacterString());
                if(newCharacter.isDoubleWidth()) {
                    // Double-width characters take up two columns
                    currentColumn += 2;
                    x++;
                }
                else {
                    // Normal characters take up one column
                    currentColumn += 1;
                }
            }
        }
    }
    
    /**
     * Returns the underlying {@code Terminal} interface that this Screen is using. 
     * <p>
     * <b>Be aware:</b> directly modifying the underlying terminal will most likely result in unexpected behaviour if
     * you then go on and try to interact with the Screen. The Screen's back-buffer/front-buffer will not know about
     * the operations you are going on the Terminal and won't be able to properly generate a refresh unless you enforce
     * a {@code Screen.RefreshType.COMPLETE}, at which the entire terminal area will be repainted according to the 
     * back-buffer of the {@code Screen}.
     * @return Underlying terminal used by the screen
     */
    @SuppressWarnings("WeakerAccess")
    public Terminal getTerminal() {
        return terminal;
    }

    @Override
    public KeyStroke readInput() throws IOException {
        return terminal.readInput();
    }

    @Override
    public KeyStroke pollInput() throws IOException {
        return terminal.pollInput();
    }

    @Override
    public synchronized void clear() {
        super.clear();
        fullRedrawHint = true;
        scrollHint = ScrollHint.INVALID;
    }

    @Override
    public synchronized TerminalSize doResizeIfNecessary() {
        TerminalSize newSize = super.doResizeIfNecessary();
        if(newSize != null) {
            fullRedrawHint = true;
        }
        return newSize;
    }
    
    /**
     * Perform the scrolling and save scroll-range and distance in order
     * to be able to optimize Terminal-update later.
     */
    @Override
    public void scrollLines(int firstLine, int lastLine, int distance) {
        // just ignore certain kinds of garbage:
        if (distance == 0 || firstLine > lastLine) { return; }

        super.scrollLines(firstLine, lastLine, distance);

        // Save scroll hint for next refresh:
        ScrollHint newHint = new ScrollHint(firstLine,lastLine,distance);
        if (scrollHint == null) {
            // no scroll hint yet: use the new one:
            scrollHint = newHint;
        } else //noinspection StatementWithEmptyBody
            if (scrollHint == ScrollHint.INVALID) {
            // scroll ranges already inconsistent since latest refresh!
            // leave at INVALID
        } else if (scrollHint.matches(newHint)) {
            // same range: just accumulate distance:
            scrollHint.distance += newHint.distance;
        } else {
            // different scroll range: no scroll-optimization for next refresh
            this.scrollHint = ScrollHint.INVALID;
        }
    }

    private class TerminalScreenResizeListener implements TerminalResizeListener {
        @Override
        public void onResized(Terminal terminal, TerminalSize newSize) {
            addResizeRequest(newSize);
        }
    }


    private static class ScrollHint {
        public static final ScrollHint INVALID = new ScrollHint(-1,-1,0);
        public final int firstLine;
        public final int lastLine;
        public int distance;

        public ScrollHint(int firstLine, int lastLine, int distance) {
            this.firstLine = firstLine;
            this.lastLine = lastLine;
            this.distance = distance;
        }

        public boolean matches(ScrollHint other) {
            return this.firstLine == other.firstLine
                && this.lastLine == other.lastLine;
        }

        public void applyTo( Scrollable scr ) throws IOException {
            scr.scrollLines(firstLine, lastLine, distance);
        }
    }

}
