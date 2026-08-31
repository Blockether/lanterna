/*
 * This file is part of lanterna (https://github.com/mabe02/lanterna).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.googlecode.lanterna.gui2;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalRectangle;
import com.googlecode.lanterna.input.MouseAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Double-buffered hit map for controls painted directly into a text graphics surface.
 *
 * <p>Writers stage one complete paint pass and publish it atomically. Readers therefore see the
 * previous complete frame until {@link #commitFrame()} runs, never a partially-painted registry.
 * Rectangles are half-open and lookup scans in reverse paint order so the last painted value wins.</p>
 */
public final class HitRegionMap<T> {
    private record Region<T>(TerminalRectangle bounds, T value) {
    }

    private final Function<? super T, TerminalRectangle> boundsExtractor;
    private volatile List<Region<T>> published = List.of();
    private List<Region<T>> staging = new ArrayList<>();
    private volatile T hovered;

    /** Creates a map whose values are registered with explicit bounds. */
    public HitRegionMap() {
        this.boundsExtractor = null;
    }

    /** Creates a map that can derive bounds when {@link #register(Object)} is called. */
    public HitRegionMap(Function<? super T, TerminalRectangle> boundsExtractor) {
        this.boundsExtractor = Objects.requireNonNull(boundsExtractor, "boundsExtractor");
    }

    /** Starts a paint pass without disturbing the currently published frame. */
    public synchronized void beginFrame() {
        staging = new ArrayList<>();
    }

    /** Stages a rectangular value in paint order. */
    public synchronized void register(int column, int row, int width, int height, T value) {
        register(new TerminalRectangle(column, row, width, height), value);
    }

    /** Stages a value with explicit bounds in paint order. */
    public synchronized void register(TerminalRectangle bounds, T value) {
        Objects.requireNonNull(bounds, "bounds");
        if (bounds.width <= 0 || bounds.height <= 0) {
            throw new IllegalArgumentException("Hit region width and height must be positive");
        }
        staging.add(new Region<>(bounds, Objects.requireNonNull(value, "value")));
    }

    /** Stages a value using the bounds extractor supplied to the constructor. */
    public synchronized void register(T value) {
        if (boundsExtractor == null) {
            throw new IllegalStateException("HitRegionMap has no bounds extractor");
        }
        register(Objects.requireNonNull(boundsExtractor.apply(value), "extracted bounds"), value);
    }

    /** Publishes the complete staged frame in one atomic assignment. */
    public synchronized void commitFrame() {
        published = List.copyOf(staging);
    }

    /** Clears published, staged and hover state. */
    public synchronized void reset() {
        published = List.of();
        staging = new ArrayList<>();
        hovered = null;
    }

    /** Returns published values in paint order. */
    public List<T> current() {
        List<Region<T>> snapshot = published;
        List<T> values = new ArrayList<>(snapshot.size());
        for (Region<T> region : snapshot) values.add(region.value());
        return List.copyOf(values);
    }

    /** Returns the topmost value containing the point, or {@code null}. */
    public T lookup(int column, int row) {
        List<Region<T>> snapshot = published;
        for (int index = snapshot.size() - 1; index >= 0; index--) {
            Region<T> region = snapshot.get(index);
            if (region.bounds().contains(column, row)) return region.value();
        }
        return null;
    }

    /** Returns the topmost value under a terminal position, or {@code null}. */
    public T lookup(TerminalPosition position) {
        Objects.requireNonNull(position, "position");
        return lookup(position.getColumn(), position.getRow());
    }

    /** Returns the topmost value under a mouse action, or {@code null}. */
    public T lookup(MouseAction action) {
        Objects.requireNonNull(action, "action");
        return lookup(action.getPosition());
    }

    public T hovered() {
        return hovered;
    }

    /** Updates hover and reports whether its value changed. */
    public synchronized boolean setHovered(T value) {
        if (Objects.equals(hovered, value)) return false;
        hovered = value;
        return true;
    }

    /** Looks up a mouse action, updates hover and reports whether it changed. */
    public boolean updateHovered(MouseAction action) {
        return setHovered(lookup(action));
    }
}
