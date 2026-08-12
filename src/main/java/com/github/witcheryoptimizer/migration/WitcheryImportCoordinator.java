package com.github.witcheryoptimizer.migration;

import java.util.HashMap;
import java.util.Map;

import com.github.witcheryoptimizer.registry.PoppetWorldData;

public final class WitcheryImportCoordinator {

    private final Map<Integer, Integer> outstanding = new HashMap<>();
    private boolean gaps;
    private boolean finalized;

    public boolean inspect(int dimension, boolean[] plausible, int maximum) {
        if (finalized || outstanding.containsKey(dimension)) return false;
        boolean valid = plausible.length <= maximum;
        for (boolean value : plausible) if (!value) valid = false;
        if (!valid) {
            gaps = true;
            return false;
        }
        outstanding.put(dimension, plausible.length);
        return true;
    }

    public void finish(int dimension, int imported, int offered, int releaseFailures) {
        Integer expected = outstanding.remove(dimension);
        if (expected == null || expected != offered || imported != offered || releaseFailures != 0) gaps = true;
    }

    public boolean finalizeStartup() {
        if (finalized || !outstanding.isEmpty()) return false;
        finalized = true;
        return true;
    }

    public PoppetWorldData.ImportState state() {
        if (finalized)
            return gaps ? PoppetWorldData.ImportState.DRAINED_WITH_GAPS : PoppetWorldData.ImportState.DRAINED_CLEAN;
        return PoppetWorldData.ImportState.IN_PROGRESS;
    }

    public void fail() {
        gaps = true;
    }

    public void resetForServerStop() {
        outstanding.clear();
        gaps = false;
        finalized = false;
    }

    public void resume(PoppetWorldData.ImportState persisted) {
        if (!outstanding.isEmpty()) throw new IllegalStateException("Cannot recover import state after inspection");
        gaps = false;
        finalized = false;
        if (persisted == PoppetWorldData.ImportState.IN_PROGRESS || persisted == PoppetWorldData.ImportState.FAILED)
            gaps = true;
        else if (persisted == PoppetWorldData.ImportState.COMPLETE
            || persisted == PoppetWorldData.ImportState.DRAINED_CLEAN
            || persisted == PoppetWorldData.ImportState.DRAINED_WITH_GAPS) {
                finalized = true;
                gaps = persisted == PoppetWorldData.ImportState.DRAINED_WITH_GAPS;
            }
    }
}
