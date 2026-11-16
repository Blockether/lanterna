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
 * Copyright (C) 2010-2020 Martin Berglund
 */
package com.googlecode.lanterna.input;

import com.googlecode.lanterna.input.CharacterPattern.Matching;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.*;

/**
 * Used to read the input stream character by character and generate {@code Key} objects to be put in the input queue.
 *
 * @author Martin, Andreas
 */
public class InputDecoder {
    /**
     * Matched by a {@link CharacterPattern} for input that carries no key stroke, such as a key
     * release report. The decoder consumes that input and never returns this key stroke.
     */
    static final KeyStroke IGNORED = new KeyStroke(KeyType.Unknown);

    private final Reader source;
    private final List<CharacterPattern> bytePatterns;
    private final List<Character> currentMatching;
    private boolean seenEOF;
    private int timeoutMillis;
    private int replyTimeoutMillis;
    private long replyDeadlineNanos;
    private volatile long replyDueNanos;
    private long escapeDeadlineNanos;
    private boolean escapeAlone;

    /**
     * Creates a new input decoder using a specified Reader as the source to read characters from
     * @param source Reader to read characters from, will be wrapped by a BufferedReader
     */
    public InputDecoder(final Reader source) {
        this.source = new BufferedReader(source);
        this.bytePatterns = new ArrayList<>();
        this.currentMatching = new ArrayList<>();
        this.seenEOF = false;
        this.timeoutMillis = 50;
        this.replyTimeoutMillis = 1000;
        this.replyDeadlineNanos = 0;
    }

    /**
     * Adds another key decoding profile to this InputDecoder, which means all patterns from the profile will be used
     * when decoding input.
     * @param profile Profile to add
     */
    public void addProfile(KeyDecodingProfile profile) {
        for (CharacterPattern pattern : profile.getPatterns()) {
            synchronized(bytePatterns) {
                //If an equivalent pattern already exists, remove it first
                bytePatterns.remove(pattern);
                bytePatterns.add(pattern);
            }
        }
    }

    /**
     * Returns a collection of all patterns registered in this InputDecoder.
     * @return Collection of patterns in the InputDecoder
     */
    public synchronized Collection<CharacterPattern> getPatterns() {
        synchronized(bytePatterns) {
            return new ArrayList<>(bytePatterns);
        }
    }

    /**
     * Removes one pattern from the list of patterns in this InputDecoder
     * @param pattern Pattern to remove
     * @return {@code true} if the supplied pattern was found and was removed, otherwise {@code false}
     */
    public boolean removePattern(CharacterPattern pattern) {
        synchronized(bytePatterns) {
            return bytePatterns.remove(pattern);
        }
    }

    /**
     * Sets how long an ambiguous escape prefix waits for the rest of its control sequence.
     * A small non-zero default keeps split mouse and paste reports atomic without making Escape feel delayed.
     */
    public void setEscapeSequenceTimeoutMillis(int millis) {
        timeoutMillis = Math.max(0, Math.min(60_000, millis));
    }

    public int getEscapeSequenceTimeoutMillis() {
        return timeoutMillis;
    }

    /**
     * Sets how long the start of a terminal reply waits for the rest of that reply. A reply to a query, such as the
     * default background color, can come in parts on a slow line. No key sends such a start, so the wait is longer
     * than {@link #setEscapeSequenceTimeoutMillis(int)}. A reply that does not end in this time is still given as a
     * {@link TerminalResponse}, never as typed text.
     */
    public void setTerminalReplyTimeoutMillis(int millis) {
        replyTimeoutMillis = Math.max(0, Math.min(60_000, millis));
    }

    public int getTerminalReplyTimeoutMillis() {
        return replyTimeoutMillis;
    }

    /**
     * Says that the reply to a query is due for at most the given time. While it is due, a lone escape character
     * waits for its rest: it is probably the start of the reply, which came in parts. Without a due reply it is the
     * Escape key after {@link #setEscapeSequenceTimeoutMillis(int)}. A key that comes while it waits, later than
     * that timeout, makes it the Escape key at once, and the key stays a key. Call this before the query is
     * written, and with 0 when the reply has come. Another thread than the reader can call it.
     * @param millis Time in milliseconds, or 0 when no reply is due
     */
    public void setTerminalReplyDue(long millis) {
        replyDueNanos = millis <= 0 ? 0 : System.nanoTime() + Math.min(60_000, millis) * 1_000_000L;
    }

    /** Sets the legacy timeout in quarter-second units. */
    public void setTimeoutUnits(int units) {
        setEscapeSequenceTimeoutMillis(Math.max(0, Math.min(240, units)) * 250);
    }

    /** Returns the legacy timeout rounded up to quarter-second units. */
    public int getTimeoutUnits() {
        return (timeoutMillis + 249) / 250;
    }

    /**
     * Reads and decodes the next key stroke from the input stream
     * @param blockingIO If set to {@code true}, the call will not return until it has read at least one {@link KeyStroke}
     * @return Key stroke read from the input stream, or {@code null} if none
     * @throws IOException If there was an I/O error when reading from the input stream
     */
    public synchronized KeyStroke getNextCharacter(boolean blockingIO) throws IOException {
        while (true) {
            KeyStroke keyStroke = decodeNextCharacter(blockingIO);
            if (keyStroke != IGNORED) {
                return keyStroke;
            }
        }
    }

    private KeyStroke decodeNextCharacter(boolean blockingIO) throws IOException {

        KeyStroke bestMatch = null;
        int bestLen = 0;
        int curLen = 0;

        while(true) {

            if ( curLen < currentMatching.size() ) {
                // (re-)consume characters previously read:
                curLen++;
            }
            else {
                // If we already have a bestMatch but a chance for a longer match
                //   then we poll for the configured number of timeout units:
                // It would be much better, if we could just read with a timeout,
                //   but lacking that, we wait 1/4s units and check for readiness.
                // The start of a terminal reply waits longer for its rest: no key sends such a start.
                boolean replyStart = bestMatch != null && TerminalResponsePattern.isReplyStart(currentMatching);
                // A lone escape character while a reply is due is probably the start of that reply.
                long dueNanos = replyDueNanos;
                boolean escapeOfReply = !replyStart && bestMatch != null && currentMatching.size() == 1
                        && currentMatching.get(0) == 0x1b && System.nanoTime() < dueNanos;
                if (replyStart) {
                    if (replyDeadlineNanos == 0) {
                        replyDeadlineNanos = System.nanoTime() + replyTimeoutMillis * 1_000_000L;
                    }
                    if (!source.ready() && System.nanoTime() < replyDeadlineNanos) {
                        if (!blockingIO) {
                            // The characters stay in currentMatching for the next call.
                            return null;
                        }
                        waitForInput(replyDeadlineNanos);
                    }
                }
                else if (escapeOfReply) {
                    // The rest of an escape sequence comes in the escape time. Later input is the rest of the
                    // reply, or a new key after the Escape key.
                    if (escapeDeadlineNanos == 0) {
                        escapeDeadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L;
                    }
                    waitForInput(escapeDeadlineNanos);
                    if (!source.ready()) {
                        escapeAlone = true;
                        if (!blockingIO) {
                            // The character stays in currentMatching for the next call.
                            return null;
                        }
                        waitForInput(dueNanos);
                    }
                }
                else if (bestMatch != null) {
                    waitForInput(System.nanoTime() + timeoutMillis * 1_000_000L);
                }
                // if input is available, we can just read a char without waiting,
                // otherwise, for readInput() with no bestMatch found yet,
                //  we have to wait blocking for more input:
                if ( source.ready() || ( blockingIO && bestMatch == null ) ) {
                    int readChar = source.read();
                    if (readChar == -1) {
                        seenEOF = true;
                        if(currentMatching.isEmpty()) {
                            return new KeyStroke(KeyType.EOF);
                        }
                        break;
                    }
                    currentMatching.add( (char)readChar );
                    if (escapeAlone && currentMatching.size() == 2) {
                        escapeAlone = false;
                        if (!restOfReplyFollows((char)readChar)) {
                            // The Escape key, then a new key. The new key stays for the next call.
                            break;
                        }
                    }
                    curLen++;
                } else { // no more available input at this time.
                    // already found something:
                    if (bestMatch != null) {
                        if (replyStart) {
                            // The reply did not end in time. It is still a reply, not typed text.
                            bestMatch = new TerminalResponse(text(currentMatching));
                            bestLen = curLen;
                        }
                        break; // it's something...
                    }
                    // otherwise: no KeyStroke yet
                    return null;
                }
            }

            List<Character> curSub = currentMatching.subList(0, curLen);
            Matching matching = getBestMatch( curSub );

            // fullMatch found...
            if (matching.fullMatch != null) {
                bestMatch = matching.fullMatch;
                bestLen = curLen;

                if (! matching.partialMatch) {
                    // that match and no more
                    break;
                } else {
                    // that match, but maybe more

                    //noinspection UnnecessaryContinue
                    continue;
                }
            }
            // No match found yet, but there's still potential...
            else if ( matching.partialMatch ) {
                //noinspection UnnecessaryContinue
                continue;
            }
            // no longer match possible at this point:
            else {
                if (bestMatch != null ) {
                    // A reply that another sequence cuts off is still a reply. The other sequence starts at
                    // the character that ended the match, or at the escape character before it.
                    int replyLen = curLen - 1;
                    if (replyLen > bestLen && currentMatching.get(replyLen - 1) == 0x1b) {
                        replyLen--;
                    }
                    if (replyLen > bestLen
                            && TerminalResponsePattern.isReplyStart(currentMatching.subList(0, replyLen))) {
                        bestMatch = new TerminalResponse(text(currentMatching.subList(0, replyLen)));
                        bestLen = replyLen;
                    }
                    // there was already a previous full-match, use it:
                    break;
                } else { // invalid input!
                    // remove the whole fail and re-try finding a KeyStroke...
                    curSub.clear(); // or just 1 char?  currentMatching.remove(0);
                    curLen = 0;
                    escapeDeadlineNanos = 0;
                    escapeAlone = false;
                    //noinspection UnnecessaryContinue
                    continue;
                }
            }
        }

        //Did we find anything? Otherwise return null
        if(bestMatch == null) {
            if(seenEOF) {
                currentMatching.clear();
                return new KeyStroke(KeyType.EOF);
            }
            return null;
        }

        List<Character> bestSub = currentMatching.subList(0, bestLen );
        bestSub.clear(); // remove matched characters from input
        replyDeadlineNanos = 0;
        escapeDeadlineNanos = 0;
        escapeAlone = false;
        return bestMatch;
    }

    /** Tells if the character after a lone escape character continues a reply: its rest comes with no gap. */
    private boolean restOfReplyFollows(char second) throws IOException {
        if (!TerminalResponsePattern.isReplyKind(second)) {
            return false;
        }
        waitForInput(System.nanoTime() + timeoutMillis * 1_000_000L);
        return source.ready();
    }

    private void waitForInput(long deadlineNanos) throws IOException {
        while (!source.ready() && System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(1);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static String text(List<Character> characters) {
        StringBuilder builder = new StringBuilder(characters.size());
        for (char c : characters) {
            builder.append(c);
        }
        return builder.toString();
    }

    private Matching getBestMatch(List<Character> characterSequence) {
        boolean partialMatch = false;
        KeyStroke bestMatch = null;
        synchronized(bytePatterns) {
            for(CharacterPattern pattern : bytePatterns) {
                Matching res = pattern.match(characterSequence);
                if (res != null) {
                    if (res.partialMatch) { partialMatch = true; }
                    if (res.fullMatch != null) { bestMatch = res.fullMatch; }
                }
            }
        }
        return new Matching(partialMatch, bestMatch);
    }
}
