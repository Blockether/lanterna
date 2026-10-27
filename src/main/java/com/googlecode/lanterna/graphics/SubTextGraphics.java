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
package com.googlecode.lanterna.graphics;

import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;

/**
 * This implementation of TextGraphics will take a 'proper' object and composite a view on top of it, by using a
 * top-left position and a size. Any attempts to put text outside of this area will be dropped.
 * @author Martin
 */
class SubTextGraphics extends AbstractTextGraphics {
    private final TextGraphics underlyingTextGraphics;
    private final TerminalPosition topLeft;
    private final TerminalSize writableAreaSize;

    SubTextGraphics(TextGraphics underlyingTextGraphics, TerminalPosition topLeft, TerminalSize writableAreaSize) {
        this.underlyingTextGraphics = underlyingTextGraphics;
        this.topLeft = topLeft;
        this.writableAreaSize = writableAreaSize;
    }

    private TerminalPosition project(int column, int row) {
        return topLeft.withRelative(column, row);
    }

    @Override
    public TextGraphics setCharacter(int columnIndex, int rowIndex, TextCharacter textCharacter) {
        TerminalSize writableArea = getSize();
        if(columnIndex < 0 || columnIndex >= writableArea.getColumns() ||
                rowIndex < 0 || rowIndex >= writableArea.getRows()) {
            return this;
        }
        // Pass the projected cell as ints: a position object per cell and nesting level was
        // the main allocation in a full-screen fill through nested views.
        underlyingTextGraphics.setCharacter(topLeft.getColumn() + columnIndex, topLeft.getRow() + rowIndex, textCharacter);
        return this;
    }

    @Override
    public TextGraphics fillRectangle(TerminalPosition rectangleTopLeft, TerminalSize size, TextCharacter character) {
        // A double-width fill depends on the requested right edge, so only single-width fills
        // are clipped here and passed down as one rectangle instead of one write per cell.
        if(character.isDoubleWidth()) {
            return super.fillRectangle(rectangleTopLeft, size, character);
        }
        int left = Math.max(0, rectangleTopLeft.getColumn());
        int top = Math.max(0, rectangleTopLeft.getRow());
        int right = Math.min(writableAreaSize.getColumns(), rectangleTopLeft.getColumn() + size.getColumns());
        int bottom = Math.min(writableAreaSize.getRows(), rectangleTopLeft.getRow() + size.getRows());
        if(left >= right || top >= bottom) {
            return this;
        }
        underlyingTextGraphics.fillRectangle(project(left, top), new TerminalSize(right - left, bottom - top), character);
        return this;
    }

    @Override
    public TerminalSize getSize() {
        return writableAreaSize;
    }

    @Override
    public TextCharacter getCharacter(int column, int row) {
        TerminalPosition projectedPosition = project(column, row);
        return underlyingTextGraphics.getCharacter(projectedPosition.getColumn(), projectedPosition.getRow());
    }
}
