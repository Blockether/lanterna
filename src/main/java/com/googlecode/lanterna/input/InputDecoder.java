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
    private final Reader source;
    private final List<CharacterPattern> bytePatterns;
    private final List<Character> currentMatching;
    private boolean seenEOF;
    private int timeoutMillis;

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
                if (bestMatch != null) {
                    long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
                    while (!source.ready() && System.nanoTime() < deadline) {
                        try {
                            Thread.sleep(1);
                        }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
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
                    curLen++;
                } else { // no more available input at this time.
                    // already found something:
                    if (bestMatch != null) {
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
                    // there was already a previous full-match, use it:
                    break;
                } else { // invalid input!
                    // remove the whole fail and re-try finding a KeyStroke...
                    curSub.clear(); // or just 1 char?  currentMatching.remove(0);
                    curLen = 0;
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
        return bestMatch;
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
