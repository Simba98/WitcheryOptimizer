package com.github.witcheryoptimizer.registry;

import static org.junit.Assert.*;

import org.junit.Test;

public class PlacementAuthorizationsTest {

    private final ShelfLocation location = new ShelfLocation(2, 3, 4, 5);

    @Test
    public void exactObjectConsumesOnlyOnce() {
        PlacementAuthorizations<Object> values = new PlacementAuthorizations<>();
        Object exact = new Object();
        values.authorize(exact, location, 2);
        assertTrue(values.consume(exact, location, 1));
        assertFalse(values.consume(exact, location, 1));
    }

    @Test
    public void differentObjectCannotStealAuthorization() {
        PlacementAuthorizations<Object> values = new PlacementAuthorizations<>();
        Object exact = new Object();
        values.authorize(exact, location, 2);
        assertFalse(values.consume(new Object(), location, 1));
        assertTrue(values.consume(exact, location, 1));
    }

    @Test
    public void expiryAndRemovalClearAuthorization() {
        PlacementAuthorizations<Object> values = new PlacementAuthorizations<>();
        Object exact = new Object();
        values.authorize(exact, location, 1);
        values.expire(2);
        assertFalse(values.consume(exact, location, 2));
        values.authorize(exact, location, 3);
        values.removeLocation(location);
        assertFalse(values.consume(exact, location, 2));
    }
}
