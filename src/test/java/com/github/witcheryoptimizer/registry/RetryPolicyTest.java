package com.github.witcheryoptimizer.registry;

import static org.junit.Assert.*;

import org.junit.Test;

public class RetryPolicyTest {

    @Test
    public void exponentialDelaysReachBothCaps() {
        assertEquals(1000L, RetryPolicy.delay(1, false));
        assertEquals(2000L, RetryPolicy.delay(2, false));
        assertEquals(300000L, RetryPolicy.delay(30, false));
        assertEquals(30000L, RetryPolicy.delay(1, true));
        assertEquals(60000L, RetryPolicy.delay(2, true));
        assertEquals(3600000L, RetryPolicy.delay(30, true));
    }

    @Test
    public void deadlineIsExplicitAndInclusive() {
        assertFalse(RetryPolicy.due(999L, 1000L));
        assertTrue(RetryPolicy.due(1000L, 1000L));
        assertTrue(RetryPolicy.due(1001L, 1000L));
    }

    @Test
    public void invalidDeadlinesClampToNow() {
        long now = 100000L;
        assertEquals(now, RetryPolicy.clampDeadline(now, 0L, false));
        assertEquals(now, RetryPolicy.clampDeadline(now, now - 1, false));
        assertEquals(now, RetryPolicy.clampDeadline(now, now + RetryPolicy.TRANSIENT_CAP + 1, false));
        assertEquals(now + 5, RetryPolicy.clampDeadline(now, now + 5, true));
        assertEquals(now, RetryPolicy.clampDeadline(now, Long.MAX_VALUE, true));
    }

    @Test
    public void initializationBackoffIsBounded() {
        assertEquals(20L, RetryPolicy.initializationDelayTicks(1));
        assertEquals(40L, RetryPolicy.initializationDelayTicks(2));
        assertEquals(5120L, RetryPolicy.initializationDelayTicks(20));
    }
}
