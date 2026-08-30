/*
 * This file is part of lanterna (https://github.com/mabe02/lanterna).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.googlecode.lanterna.gui2;

import com.googlecode.lanterna.TerminalSize;

import java.util.Objects;

/**
 * A GUI2 component backed by a caller-supplied {@link TextGUIGraphics} painter.
 *
 * <p>This is the bridge for applications that already own cell painting but want
 * Lanterna's layout managers to measure and place that painting. It remains a real
 * component, so it can be nested in {@link GridLayout}, {@link LinearLayout}, and
 * any other GUI2 container and rendered by every terminal backend.</p>
 */
public final class TextGraphicsComponent extends AbstractComponent<TextGraphicsComponent> {
    /** Draws one component after layout has resolved its origin and size. */
    @FunctionalInterface
    public interface Painter {
        void paint(TextGUIGraphics graphics, TextGraphicsComponent component);
    }

    private final TerminalSize preferredSize;
    private final Painter painter;

    /**
     * Creates a paint component with a fixed preferred size.
     *
     * @param preferredSize size requested from its parent layout
     * @param painter painter invoked with component-local coordinates
     */
    public TextGraphicsComponent(TerminalSize preferredSize, Painter painter) {
        this.preferredSize = Objects.requireNonNull(preferredSize, "preferredSize");
        this.painter = Objects.requireNonNull(painter, "painter");
    }

    /** Paints this component without asking a container to draw the full tree. */
    public void paint(TextGUIGraphics graphics) {
        painter.paint(Objects.requireNonNull(graphics, "graphics"), this);
    }

    @Override
    protected ComponentRenderer<TextGraphicsComponent> createDefaultRenderer() {
        return new ComponentRenderer<>() {
            @Override
            public TerminalSize getPreferredSize(TextGraphicsComponent component) {
                return preferredSize;
            }

            @Override
            public void drawComponent(TextGUIGraphics graphics, TextGraphicsComponent component) {
                component.paint(graphics);
            }
        };
    }
}
