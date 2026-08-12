package com.github.witcheryoptimizer.migration;

import static org.junit.Assert.*;

import org.junit.Test;

import com.github.witcheryoptimizer.registry.PoppetWorldData;

public class WitcheryImportCoordinatorTest {

    @Test
    public void ticketFreeStartupCompletes() {
        WitcheryImportCoordinator c = new WitcheryImportCoordinator();
        assertTrue(c.finalizeStartup());
        assertEquals(PoppetWorldData.ImportState.DRAINED_CLEAN, c.state());
    }

    @Test
    public void gapsDoNotPreventDrainCompletion() {
        WitcheryImportCoordinator c = new WitcheryImportCoordinator();
        assertFalse(c.inspect(0, new boolean[] { false }, 1));
        assertTrue(c.inspect(1, new boolean[0], 1));
        c.finish(1, 0, 0, 0);
        assertTrue(c.finalizeStartup());
        assertEquals(PoppetWorldData.ImportState.DRAINED_WITH_GAPS, c.state());
    }

    @Test
    public void outstandingDimensionsPreventCompletion() {
        WitcheryImportCoordinator c = new WitcheryImportCoordinator();
        assertTrue(c.inspect(0, new boolean[] { true }, 1));
        assertFalse(c.finalizeStartup());
        c.finish(0, 1, 1, 0);
        assertTrue(c.finalizeStartup());
    }

    @Test
    public void overflowMaxZeroAndMalformedSuffixFail() {
        assertFalse(new WitcheryImportCoordinator().inspect(0, new boolean[] { true }, 0));
        assertFalse(new WitcheryImportCoordinator().inspect(0, new boolean[] { true, false }, 2));
        assertFalse(new WitcheryImportCoordinator().inspect(0, new boolean[] { true, true }, 1));
    }

    @Test
    public void recoveredLegacyFailuresPermitCensusAfterDrain() {
        WitcheryImportCoordinator unknown = new WitcheryImportCoordinator();
        unknown.resume(PoppetWorldData.ImportState.UNKNOWN);
        assertTrue(unknown.finalizeStartup());
        WitcheryImportCoordinator interrupted = new WitcheryImportCoordinator();
        interrupted.resume(PoppetWorldData.ImportState.IN_PROGRESS);
        assertEquals(PoppetWorldData.ImportState.IN_PROGRESS, interrupted.state());
        assertTrue(interrupted.finalizeStartup());
        assertEquals(PoppetWorldData.ImportState.DRAINED_WITH_GAPS, interrupted.state());
        WitcheryImportCoordinator failed = new WitcheryImportCoordinator();
        failed.resume(PoppetWorldData.ImportState.FAILED);
        assertTrue(failed.finalizeStartup());
        assertEquals(PoppetWorldData.ImportState.DRAINED_WITH_GAPS, failed.state());
        WitcheryImportCoordinator complete = new WitcheryImportCoordinator();
        complete.resume(PoppetWorldData.ImportState.COMPLETE);
        assertEquals(PoppetWorldData.ImportState.DRAINED_CLEAN, complete.state());
        assertFalse(complete.finalizeStartup());
    }

    @Test
    public void inspectionCannotBeResetOrRecoveredMidCallback() {
        WitcheryImportCoordinator c = new WitcheryImportCoordinator();
        assertTrue(c.inspect(0, new boolean[] { true }, 1));
        try {
            c.resume(PoppetWorldData.ImportState.UNKNOWN);
            fail("resume must not clear an outstanding callback");
        } catch (IllegalStateException expected) {}
        assertFalse(c.finalizeStartup());
        c.finish(0, 1, 1, 0);
        assertTrue(c.inspect(1, new boolean[] { true, true }, 2));
        c.finish(1, 2, 2, 0);
        assertTrue(c.finalizeStartup());
    }

    @Test
    public void releaseFailurePersistsGapWithoutLosingImportOutcome() {
        WitcheryImportCoordinator c = new WitcheryImportCoordinator();
        assertTrue(c.inspect(4, new boolean[] { true }, 1));
        c.finish(4, 1, 1, 1);
        assertTrue(c.finalizeStartup());
        assertEquals(PoppetWorldData.ImportState.DRAINED_WITH_GAPS, c.state());
    }
}
