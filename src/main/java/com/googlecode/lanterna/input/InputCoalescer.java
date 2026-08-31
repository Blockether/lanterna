/*
 * This file is part of lanterna (https://github.com/mabe02/lanterna).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * lanterna is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2010-2024 Martin Berglund
 */
package com.googlecode.lanterna.input;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Stateful input reader that canonicalizes queued pointer bursts and retains the first different
 * event for the next read. One instance belongs to one logical input loop.
 */
public final class InputCoalescer {
    private final Deque<KeyStroke> pending = new ArrayDeque<>();

    /**
     * Returns the next canonical input. {@code nextInput} supplies the first event when no lookahead
     * is retained; {@code queuedInput} drains only input already available without blocking.
     */
    public synchronized KeyStroke next(
            Supplier<? extends KeyStroke> nextInput,
            Supplier<? extends KeyStroke> queuedInput) {
        Objects.requireNonNull(nextInput, "nextInput");
        Objects.requireNonNull(queuedInput, "queuedInput");

        KeyStroke first = pending.pollFirst();
        if (first == null) first = nextInput.get();

        while (true) {
            MouseAction.CoalescedInput coalesced = MouseAction.coalesceQueued(
                    first,
                    () -> {
                        KeyStroke retained = pending.pollFirst();
                        return retained != null ? retained : queuedInput.get();
                    });
            if (coalesced.nextKey() != null) pending.addFirst(coalesced.nextKey());
            if (coalesced.key() != null) return coalesced.key();

            first = pending.pollFirst();
            if (first == null) return null;
        }
    }

    /**
     * Returns whether input is pending, polling and retaining one event when necessary.
     */
    public synchronized boolean inputPending(Supplier<? extends KeyStroke> queuedInput) {
        Objects.requireNonNull(queuedInput, "queuedInput");
        if (!pending.isEmpty()) return true;
        KeyStroke input = queuedInput.get();
        if (input != null) pending.addLast(input);
        return input != null;
    }

    /** Replays an application-retained input before any queued terminal input. */
    public synchronized void replay(KeyStroke input) {
        pending.addFirst(Objects.requireNonNull(input, "input"));
    }
}
