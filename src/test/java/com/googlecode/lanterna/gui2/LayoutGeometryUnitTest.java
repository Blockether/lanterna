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
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class LayoutGeometryUnitTest {
    @Test
    public void gridPositionsMarginsSpacingAndSpansInIntegerCells() {
        GridLayout layout = new GridLayout(2)
                .setLeftMarginSize(1)
                .setRightMarginSize(1)
                .setTopMarginSize(1)
                .setBottomMarginSize(1)
                .setHorizontalSpacing(1)
                .setVerticalSpacing(1);
        EmptySpace first = space(3, 1);
        EmptySpace second = space(2, 2);
        EmptySpace spanning = space(4, 1);
        spanning.setLayoutData(GridLayout.createLayoutData(
                GridLayout.Alignment.FILL,
                GridLayout.Alignment.BEGINNING,
                false,
                false,
                2,
                1));
        List<Component> components = List.of(first, second, spanning);

        assertEquals(new TerminalSize(8, 6), layout.getPreferredSize(components));
        layout.doLayout(new TerminalSize(12, 8), components);

        assertGeometry(first, 1, 1, 3, 1);
        assertGeometry(second, 5, 1, 2, 2);
        assertGeometry(spanning, 1, 4, 6, 1);
    }

    @Test
    public void gridGivesExtraSpaceOnlyToGrabbingColumnsAndRows() {
        GridLayout layout = new GridLayout(2)
                .setLeftMarginSize(0)
                .setRightMarginSize(0)
                .setHorizontalSpacing(0);
        EmptySpace growing = space(2, 1);
        growing.setLayoutData(GridLayout.createLayoutData(
                GridLayout.Alignment.FILL,
                GridLayout.Alignment.FILL,
                true,
                true));
        EmptySpace fixed = space(2, 1);

        layout.doLayout(new TerminalSize(10, 4), List.of(growing, fixed));

        assertGeometry(growing, 0, 0, 8, 4);
        assertGeometry(fixed, 8, 0, 2, 1);
    }

    @Test
    public void linearLayoutUsesGrowPolicyAndCounterAxisAlignment() {
        LinearLayout layout = new LinearLayout(Direction.HORIZONTAL).setSpacing(1);
        EmptySpace growing = space(2, 1);
        growing.setLayoutData(LinearLayout.createLayoutData(
                LinearLayout.Alignment.Center,
                LinearLayout.GrowPolicy.CanGrow));
        EmptySpace fixed = space(3, 2);
        fixed.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.End));

        layout.doLayout(new TerminalSize(10, 4), List.of(growing, fixed));

        assertGeometry(growing, 0, 1, 6, 1);
        assertGeometry(fixed, 7, 2, 3, 2);
    }

    @Test
    public void linearLayoutShrinksToTheExactAvailableMainAxis() {
        LinearLayout layout = new LinearLayout(Direction.HORIZONTAL).setSpacing(1);
        EmptySpace first = space(6, 1);
        EmptySpace second = space(4, 1);

        layout.doLayout(new TerminalSize(8, 1), List.of(first, second));

        assertEquals(8, first.getSize().getColumns() + 1 + second.getSize().getColumns());
        assertEquals(first.getSize().getColumns() + 1, second.getPosition().getColumn());
    }

    @Test
    public void invisibleComponentsDoNotConsumeLinearSpace() {
        LinearLayout layout = new LinearLayout(Direction.VERTICAL).setSpacing(1);
        EmptySpace first = space(2, 1);
        EmptySpace hidden = space(20, 10);
        hidden.setVisible(false);
        EmptySpace second = space(3, 2);

        List<Component> components = List.of(first, hidden, second);
        assertEquals(new TerminalSize(3, 4), layout.getPreferredSize(components));
        layout.doLayout(new TerminalSize(5, 4), components);
        assertGeometry(first, 0, 0, 2, 1);
        assertGeometry(second, 0, 2, 3, 2);
    }

    private static EmptySpace space(int columns, int rows) {
        return new EmptySpace(new TerminalSize(columns, rows));
    }

    private static void assertGeometry(
            Component component,
            int column,
            int row,
            int columns,
            int rows) {
        assertEquals(new TerminalPosition(column, row), component.getPosition());
        assertEquals(new TerminalSize(columns, rows), component.getSize());
    }
}
