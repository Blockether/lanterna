/*
 * This file is part of lanterna (https://github.com/mabe02/lanterna).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.googlecode.lanterna.gui2;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Double-buffered hit map for controls painted directly into a text graphics surface.
 *
 * <p>Writers stage one complete paint pass and publish it atomically. Readers therefore see the
 * previous complete frame until {@link #commitFrame()} runs, never a partially-painted registry.
 * Rectangles are half-open and lookup scans in reverse paint order so the last painted value wins.</p>
 */
public final class HitRegionMap<T> {
    private record Region<T>(int column, int row, int width, int height, T value) {
        private boolean contains(int pointerColumn, int pointerRow) {
            return pointerColumn >= column
                    && (long) pointerColumn < (long) column + width
                    && pointerRow >= row
                    && (long) pointerRow < (long) row + height;
        }
    }

    private volatile List<Region<T>> published = List.of();
    private List<Region<T>> staging = new ArrayList<>();
    private volatile T hovered;

    /** Starts a paint pass without disturbing the currently published frame. */
    public synchronized void beginFrame() {
        staging = new ArrayList<>();
    }

    /** Stages a rectangular value in paint order. */
    public synchronized void register(int column, int row, int width, int height, T value) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Hit region width and height must be positive");
        }
        staging.add(new Region<>(column, row, width, height, Objects.requireNonNull(value, "value")));
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
            if (region.contains(column, row)) return region.value();
        }
        return null;
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
}
