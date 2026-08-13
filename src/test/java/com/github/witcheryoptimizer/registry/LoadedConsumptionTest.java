package com.github.witcheryoptimizer.registry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class LoadedConsumptionTest {

    @Test
    public void loadedConsumptionSynchronizesImmediatelyWithoutRepairQueue() {
        AtomicInteger mirrors = new AtomicInteger();
        AtomicInteger repairs = new AtomicInteger();

        PoppetRegistry.synchronizeLoaded(mirrors::incrementAndGet, repairs::incrementAndGet);

        assertEquals(1, mirrors.get());
        assertEquals("loaded success must create zero queue or ticket work", 0, repairs.get());
    }

    @Test
    public void loadedSynchronizationFailureQueuesOneRepair() {
        AtomicInteger repairs = new AtomicInteger();
        try {
            PoppetRegistry.synchronizeLoaded(
                () -> { throw new IllegalStateException("markDirty failed"); },
                repairs::incrementAndGet);
            fail("synchronization failure must propagate for ERROR logging");
        } catch (IllegalStateException expected) {
            assertEquals("markDirty failed", expected.getMessage());
        }
        assertEquals(1, repairs.get());
    }
}
