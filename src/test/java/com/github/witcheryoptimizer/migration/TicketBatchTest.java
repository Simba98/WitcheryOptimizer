package com.github.witcheryoptimizer.migration;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class TicketBatchTest {

    @Test
    public void falseAndThrowStillReleaseAllAndFinishFailed() {
        List<Integer> released = new ArrayList<>();
        int[] finish = new int[2];
        TicketBatch.process(Arrays.asList(1, 2, 3, 4), new TicketBatch.Actions<Integer>() {

            @Override
            public boolean importTicket(Integer ticket) throws Exception {
                if (ticket == 3) throw new Exception("broken");
                return ticket == 1 || ticket == 4;
            }

            @Override
            public void release(Integer ticket) {
                released.add(ticket);
            }

            @Override
            public void finish(int successes, int offered) {
                finish[0] = successes;
                finish[1] = offered;
            }

            @Override
            public void failure(Integer ticket, Throwable failure) {}
        });
        assertEquals(Arrays.asList(1, 2, 3, 4), released);
        assertArrayEquals(new int[] { 2, 4 }, finish);
    }

    @Test
    public void errorIsRethrownAfterCleanupAndFinish() {
        List<Integer> released = new ArrayList<>();
        boolean[] finished = new boolean[1];
        try {
            TicketBatch.process(Arrays.asList(1, 2), new TicketBatch.Actions<Integer>() {

                @Override
                public boolean importTicket(Integer ticket) {
                    if (ticket == 1) throw new AssertionError();
                    return true;
                }

                @Override
                public void release(Integer ticket) {
                    released.add(ticket);
                }

                @Override
                public void finish(int successes, int offered) {
                    finished[0] = true;
                }

                @Override
                public void failure(Integer ticket, Throwable failure) {}
            });
            fail("error expected");
        } catch (AssertionError expected) {}
        assertEquals(Arrays.asList(1, 2), released);
        assertTrue(finished[0]);
    }

    @Test
    public void releaseThrowDoesNotLeakLaterTicketsAndFinishRunsOnce() {
        List<Integer> attempted = new ArrayList<>();
        int[] finishes = new int[1];
        TicketBatch.process(Arrays.asList(1, 2, 3), new TicketBatch.Actions<Integer>() {

            @Override
            public boolean importTicket(Integer ticket) {
                return true;
            }

            @Override
            public void release(Integer ticket) {
                attempted.add(ticket);
                if (ticket == 1) throw new RuntimeException("release");
            }

            @Override
            public void finish(int successes, int offered) {
                finishes[0]++;
                assertEquals(2, successes);
                assertEquals(3, offered);
            }

            @Override
            public void failure(Integer ticket, Throwable failure) {}
        });
        assertEquals(Arrays.asList(1, 2, 3), attempted);
        assertEquals(1, finishes[0]);
    }
}
