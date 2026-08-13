package com.github.witcheryoptimizer.registry;

import static org.junit.Assert.*;

import org.junit.Test;

public class WritebackStateTest {

    @Test
    public void synchronousCompletionDoesNotCountLease() {
        assertFalse(PoppetRegistry.leaseSurvivedSynchronousLoad(false, false, false));
    }

    @Test
    public void removedJobDoesNotCountLease() {
        assertFalse(PoppetRegistry.leaseSurvivedSynchronousLoad(false, true, true));
    }

    @Test
    public void releasedTicketDoesNotCountLease() {
        assertFalse(PoppetRegistry.leaseSurvivedSynchronousLoad(true, false, true));
    }

    @Test
    public void clearedPendingDoesNotCountLease() {
        assertFalse(PoppetRegistry.leaseSurvivedSynchronousLoad(true, true, false));
    }

    @Test
    public void survivingLeaseCounts() {
        assertTrue(PoppetRegistry.leaseSurvivedSynchronousLoad(true, true, true));
    }
}
