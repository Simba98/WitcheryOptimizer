package com.github.witcheryoptimizer.registry;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

final class PlacementAuthorizations<T> {

    private final Map<T, Authorization> entries = new IdentityHashMap<>();

    void authorize(T identity, ShelfLocation location, long expiresAfterTick) {
        if (identity != null) entries.put(identity, new Authorization(location, expiresAfterTick));
    }

    boolean consume(T identity, ShelfLocation location, long tick) {
        Authorization authorization = entries.get(identity);
        if (authorization == null || tick > authorization.expiresAfterTick || !authorization.location.equals(location))
            return false;
        entries.remove(identity);
        return true;
    }

    void remove(T identity) {
        entries.remove(identity);
    }

    void removeLocation(ShelfLocation location) {
        Iterator<Authorization> iterator = entries.values()
            .iterator();
        while (iterator.hasNext()) if (iterator.next().location.equals(location)) iterator.remove();
    }

    void expire(long tick) {
        Iterator<Authorization> iterator = entries.values()
            .iterator();
        while (iterator.hasNext()) if (tick > iterator.next().expiresAfterTick) iterator.remove();
    }

    void clear() {
        entries.clear();
    }

    private static final class Authorization {

        final ShelfLocation location;
        final long expiresAfterTick;

        Authorization(ShelfLocation location, long expiresAfterTick) {
            this.location = location;
            this.expiresAfterTick = expiresAfterTick;
        }
    }
}
