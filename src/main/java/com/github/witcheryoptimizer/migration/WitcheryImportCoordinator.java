package com.github.witcheryoptimizer.migration;

import java.util.HashMap;
import java.util.Map;

import com.github.witcheryoptimizer.registry.PoppetWorldData;

public final class WitcheryImportCoordinator {

    private final Map<Integer, Integer> outstanding = new HashMap<>();
    private boolean failed;
    private boolean finalized;

    public boolean inspect(int dimension, boolean[] plausible, int maximum) {
        if (failed || finalized || outstanding.containsKey(dimension)) return false;
        boolean valid = plausible.length <= maximum;
        for (boolean value : plausible) if (!value) valid = false;
        if (!valid) {
            failed = true;
            return false;
        }
        outstanding.put(dimension, plausible.length);
        return true;
    }

    public void finish(int dimension, int imported, int offered) {
        Integer expected = outstanding.remove(dimension);
        if (failed || expected == null || expected != offered || imported != offered) failed = true;
    }

    public boolean finalizeStartup() {
        if (failed || finalized || !outstanding.isEmpty()) return false;
        finalized = true;
        return true;
    }

    public PoppetWorldData.ImportState state() {
        if (failed) return PoppetWorldData.ImportState.FAILED;
        if (finalized) return PoppetWorldData.ImportState.COMPLETE;
        return PoppetWorldData.ImportState.IN_PROGRESS;
    }

    public void fail() {
        failed = true;
    }

    public void resetForServerStop() {
        outstanding.clear();
        failed = false;
        finalized = false;
    }

    public void resume(PoppetWorldData.ImportState persisted) {
        if (!outstanding.isEmpty()) throw new IllegalStateException("Cannot recover import state after inspection");
        failed = false;
        finalized = false;
        if (persisted == PoppetWorldData.ImportState.IN_PROGRESS || persisted == PoppetWorldData.ImportState.FAILED)
            failed = true;
        else if (persisted == PoppetWorldData.ImportState.COMPLETE) finalized = true;
    }
}
