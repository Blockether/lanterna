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
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.graphics.TextGraphics;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class TextGraphicsComponentTest {
    @Test
    public void gridLaysOutCallerOwnedPaintersOnOrdinaryGraphics() {
        AtomicReference<TerminalSize> firstPaintSize = new AtomicReference<>();
        TextGraphicsComponent first = new TextGraphicsComponent(
                new TerminalSize(2, 1),
                (graphics, component) -> {
                    firstPaintSize.set(graphics.getSize());
                    graphics.putString(0, 0, "AA");
                });
        TextGraphicsComponent second = new TextGraphicsComponent(
                new TerminalSize(3, 1),
                (graphics, component) -> graphics.putString(0, 0, "BBB"));

        GridLayout layout = new GridLayout(2)
                .setLeftMarginSize(0)
                .setRightMarginSize(0)
                .setHorizontalSpacing(1);
        Panel panel = new Panel(layout);
        panel.addComponent(first);
        panel.addComponent(second);

        BasicTextImage image = new BasicTextImage(6, 1);
        panel.draw(TextGUIGraphics.from(image.newTextGraphics()));

        assertEquals("AA BBB", rowText(image, 0));
        assertEquals(new TerminalPosition(0, 0), first.getPosition());
        assertEquals(new TerminalPosition(3, 0), second.getPosition());
        assertEquals(new TerminalSize(2, 1), firstPaintSize.get());
    }

    @Test
    public void graphicsAdapterPreservesAnExistingGuiGraphicsInstance() {
        BasicTextImage image = new BasicTextImage(1, 1);
        TextGUIGraphics wrapped = TextGUIGraphics.from(image.newTextGraphics());
        assertNull(wrapped.getTextGUI());
        assertSame(wrapped, TextGUIGraphics.from(wrapped));
    }

    private static String rowText(BasicTextImage image, int row) {
        StringBuilder result = new StringBuilder(image.getSize().getColumns());
        for (int column = 0; column < image.getSize().getColumns(); column++) {
            TextCharacter character = image.getCharacterAt(column, row);
            result.append(character.getCharacterString());
        }
        return result.toString();
    }
}
