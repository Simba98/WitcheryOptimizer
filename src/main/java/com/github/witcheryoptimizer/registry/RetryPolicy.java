package com.github.witcheryoptimizer.registry;

/** Persistable, deterministic bounded exponential retry scheduling. */
public final class RetryPolicy {

    public static final long TRANSIENT_BASE = 1000L, TRANSIENT_CAP = 300000L;
    public static final long CORRUPTION_BASE = 30000L, CORRUPTION_CAP = 3600000L;

    private RetryPolicy() {}

    public static long delay(int attempt, boolean corruption) {
        long base = corruption ? CORRUPTION_BASE : TRANSIENT_BASE;
        long cap = corruption ? CORRUPTION_CAP : TRANSIENT_CAP;
        int shifts = Math.max(0, Math.min(attempt - 1, 30));
        return Math.min(cap, base * (1L << shifts));
    }

    public static long clampDeadline(long now, long deadline, boolean corruption) {
        long cap = corruption ? CORRUPTION_CAP : TRANSIENT_CAP;
        if (deadline < now || deadline > saturatedAdd(now, cap)) return now;
        return deadline;
    }

    public static boolean due(long now, long deadline, boolean corruption) {
        return now >= clampDeadline(now, deadline, corruption);
    }

    public static boolean due(long now, long deadline) {
        return now >= deadline;
    }

    public static long initializationDelayTicks(int attempt) {
        return Math.min(6000L, 20L << Math.min(8, Math.max(0, attempt - 1)));
    }

    private static long saturatedAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }
}
