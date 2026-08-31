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
package com.googlecode.lanterna.input;

import com.googlecode.lanterna.TerminalPosition;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * MouseAction, a KeyStroke in disguise, this class contains the information of a single mouse action event.
 */
public class MouseAction extends KeyStroke {
    private final MouseActionType actionType;
    private final int button;
    private final TerminalPosition position;
    private final int count;

    /** Maximum directional wheel momentum retained by {@link #mergeWheelDelta(int, int)}. */
    public static final int WHEEL_MOMENTUM_CAP = 12;

    /** Idle window during which wheel direction remains locked against inertia-tail jitter. */
    public static final long WHEEL_MOMENTUM_HOLD_MILLIS = 150L;

    /** Internal canonical input plus the first different event consumed while draining its run. */
    record CoalescedInput(KeyStroke key, KeyStroke nextKey) {
    }

    /** Updated directional momentum and the effective wheel delta, which is null when absorbed. */
    public record WheelMomentum(int momentum, Integer delta) {
    }

    /**
     * Constructs a MouseAction based on an action type, a button and a location on the screen
     * @param actionType The kind of mouse event
     * @param button Which button is involved (no button = 0, left button = 1, middle (wheel) button = 2,
     *               right button = 3, scroll wheel up = 4, scroll wheel down = 5)
     * @param position Where in the terminal is the mouse cursor located
     */
    public MouseAction(MouseActionType actionType, int button, TerminalPosition position) {
        this(actionType, button, position, 1);
    }

    /**
     * Constructs a mouse event with an explicit coalesced event count.
     * Button identity and count are separate: wheel buttons remain 4/5 even when several events are merged.
     */
    public MouseAction(MouseActionType actionType, int button, TerminalPosition position, int count) {
        super(KeyType.MouseEvent, false, false);
        if (count <= 0) throw new IllegalArgumentException("MouseAction count must be positive: " + count);
        this.actionType = Objects.requireNonNull(actionType, "actionType");
        this.button = button;
        this.position = Objects.requireNonNull(position, "position");
        this.count = count;
    }

    /**
     * Returns the mouse action type so the caller can determine which kind of action was performed.
     * @return The action type of the mouse event
     */
    public MouseActionType getActionType() {
        return actionType;
    }

    /**
     * Which button was involved in this event. Please note that for CLICK_RELEASE events, there is no button
     * information available (getButton() will return 0). The standard xterm mapping is:
     * <ul>
     *     <li>No button = 0</li>
     *     <li>Left button = 1</li>
     *     <li>Middle (wheel) button = 2</li>
     *     <li>Right button = 3</li>
     *     <li>Wheel up = 4</li>
     *     <li>Wheel down = 5</li>
     * </ul>
     * @return The button which is clicked down when this event was generated
     */
    public int getButton() {
        return button;
    }

    /**
     * The location of the mouse cursor when this event was generated.
     * @return Location of the mouse cursor
     */
    public TerminalPosition getPosition() {
        return position;
    }

    /** Returns how many equivalent input events were coalesced into this action. */
    public int getCount() {
        return count;
    }

    /** Returns signed wheel steps, or zero when this is not a wheel action. */
    public int getScrollDelta() {
        if (actionType == MouseActionType.SCROLL_UP) return -count;
        if (actionType == MouseActionType.SCROLL_DOWN) return count;
        return 0;
    }

    /**
     * Merges one signed wheel delta into directional momentum. Opposing inertia-tail jitter brakes the stored direction
     * without leaking through; only opposition beyond the remaining momentum starts a real reversal.
     */
    public static WheelMomentum mergeWheelDelta(int momentum, int rawDelta) {
        if (rawDelta == 0) return new WheelMomentum(momentum, null);

        if (momentum == 0 || Integer.signum(rawDelta) == Integer.signum(momentum)) {
            long accumulated = (long) momentum + rawDelta;
            return new WheelMomentum(capWheelMomentum(accumulated), rawDelta);
        }

        long after = (long) momentum + rawDelta;
        if (after == 0) return new WheelMomentum(0, null);
        if (Long.signum(after) == Integer.signum(momentum)) {
            return new WheelMomentum(capWheelMomentum(after), null);
        }

        int reversal = Math.toIntExact(after);
        return new WheelMomentum(reversal, reversal);
    }

    /**
     * Returns wheel momentum after an idle interval. The direction survives the complete hold window, then expires.
     */
    public static int decayWheelMomentum(int momentum, long idleMillis) {
        if (momentum == 0) return 0;
        long idle = Math.max(0L, idleMillis);
        if (idle >= WHEEL_MOMENTUM_HOLD_MILLIS) return 0;

        int scaled = (int) Math.round(
                momentum * (1.0 - ((double) idle / (double) WHEEL_MOMENTUM_HOLD_MILLIS)));
        return scaled == 0 ? Integer.signum(momentum) : scaled;
    }

    private static int capWheelMomentum(long value) {
        return (int) Math.max(-WHEEL_MOMENTUM_CAP, Math.min(WHEEL_MOMENTUM_CAP, value));
    }
    /**
     * Coalesces consecutive queued wheel or drag events into one canonical {@link MouseAction}. Wheel counts are netted
     * into a signed action at the latest pointer position; drag input keeps the latest position and summed count. The
     * first different event is returned for replay.
     */
    static CoalescedInput coalesceQueued(
            KeyStroke first, Supplier<? extends KeyStroke> pollNext) {
        Objects.requireNonNull(pollNext, "pollNext");
        if (!(first instanceof MouseAction firstMouse)) {
            return new CoalescedInput(first, null);
        }

        int firstScroll = firstMouse.getScrollDelta();
        if (firstScroll != 0) {
            long scroll = firstScroll;
            MouseAction latest = firstMouse;
            KeyStroke next = null;
            while ((next = pollNext.get()) != null) {
                if (next instanceof MouseAction mouse && mouse.getScrollDelta() != 0) {
                    scroll += mouse.getScrollDelta();
                    latest = mouse;
                }
                else {
                    break;
                }
            }
            if (scroll == 0) return new CoalescedInput(null, next);

            boolean up = scroll < 0;
            MouseAction coalesced = new MouseAction(
                    up ? MouseActionType.SCROLL_UP : MouseActionType.SCROLL_DOWN,
                    up ? 4 : 5,
                    latest.getPosition(),
                    Math.toIntExact(Math.abs(scroll)));
            return new CoalescedInput(coalesced, next);
        }

        if (firstMouse.getActionType() == MouseActionType.DRAG) {
            MouseAction latest = firstMouse;
            int dragCount = firstMouse.getCount();
            KeyStroke next = null;
            while ((next = pollNext.get()) != null) {
                if (next instanceof MouseAction mouse && mouse.getActionType() == MouseActionType.DRAG) {
                    latest = mouse;
                    dragCount = Math.addExact(dragCount, mouse.getCount());
                }
                else {
                    break;
                }
            }
            MouseAction coalesced = new MouseAction(
                    MouseActionType.DRAG, latest.getButton(), latest.getPosition(), dragCount);
            return new CoalescedInput(coalesced, next);
        }

        return new CoalescedInput(first, null);
    }
    
    public boolean isMouseDown() {
        return actionType == MouseActionType.CLICK_DOWN;
    }
    
    public boolean isMouseDrag() {
        return actionType == MouseActionType.DRAG;
    }
    
    public boolean isMouseMove() {
        return actionType == MouseActionType.MOVE;
    }
    
    public boolean isMouseUp() {
        return actionType == MouseActionType.CLICK_RELEASE;
    }

    @Override
    public String toString() {
        return "MouseAction{actionType=" + actionType + ", button=" + button + ", position=" + position + ", count=" + count + '}';
    }
}
