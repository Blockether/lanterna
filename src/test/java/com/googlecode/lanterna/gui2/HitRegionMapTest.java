package com.googlecode.lanterna.gui2;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class HitRegionMapTest {
    @Test
    public void publishesWholeFramesAndKeepsThePreviousFrameUntilCommit() {
        HitRegionMap<String> regions = new HitRegionMap<>();
        regions.beginFrame();
        regions.register(2, 3, 5, 1, "previous");
        regions.commitFrame();

        regions.beginFrame();
        regions.register(2, 3, 5, 1, "next");
        assertEquals("previous", regions.lookup(4, 3));
        assertEquals(List.of("previous"), regions.current());

        regions.commitFrame();
        assertEquals("next", regions.lookup(4, 3));
        assertEquals(List.of("next"), regions.current());
    }

    @Test
    public void lookupUsesHalfOpenRectanglesAndLastPaintedRegionWins() {
        HitRegionMap<String> regions = new HitRegionMap<>();
        regions.beginFrame();
        regions.register(5, 4, 4, 2, "under");
        regions.register(7, 5, 4, 2, "over");
        regions.commitFrame();

        assertEquals("under", regions.lookup(5, 4));
        assertEquals("over", regions.lookup(7, 5));
        assertEquals("over", regions.lookup(10, 6));
        assertNull(regions.lookup(11, 6));
        assertNull(regions.lookup(7, 7));
    }

    @Test
    public void hoverIsStableAcrossFramesAndResetClearsEverything() {
        HitRegionMap<String> regions = new HitRegionMap<>();
        assertTrue(regions.setHovered("item"));
        assertFalse(regions.setHovered("item"));
        regions.beginFrame();
        regions.register(0, 0, 1, 1, "item");
        regions.commitFrame();
        assertEquals("item", regions.hovered());

        regions.reset();
        assertNull(regions.hovered());
        assertTrue(regions.current().isEmpty());
        assertNull(regions.lookup(0, 0));
    }

    @Test
    public void rejectsEmptyBoundsAndNullValues() {
        HitRegionMap<String> regions = new HitRegionMap<>();
        assertThrows(IllegalArgumentException.class, () -> regions.register(0, 0, 0, 1, "item"));
        assertThrows(IllegalArgumentException.class, () -> regions.register(0, 0, 1, 0, "item"));
        assertThrows(NullPointerException.class, () -> regions.register(0, 0, 1, 1, null));
    }
    @Test
    public void extractedRectanglesAndMouseActionsNeedNoApplicationHitTesting() {
        record Item(com.googlecode.lanterna.TerminalRectangle bounds, String name) {}
        HitRegionMap<Item> regions = new HitRegionMap<>(Item::bounds);
        Item item = new Item(new com.googlecode.lanterna.TerminalRectangle(4, 5, 3, 2), "item");
        regions.beginFrame();
        regions.register(item);
        regions.commitFrame();

        com.googlecode.lanterna.input.MouseAction mouse = new com.googlecode.lanterna.input.MouseAction(
                com.googlecode.lanterna.input.MouseActionType.MOVE, 0,
                new com.googlecode.lanterna.TerminalPosition(6, 6));
        assertEquals(item, regions.lookup(mouse));
        assertTrue(regions.updateHovered(mouse));
        assertEquals(item, regions.hovered());
        assertFalse(regions.updateHovered(mouse));
    }
}
