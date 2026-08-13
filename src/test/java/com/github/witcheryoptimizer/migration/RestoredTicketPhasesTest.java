package com.github.witcheryoptimizer.migration;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class RestoredTicketPhasesTest {

    @Test
    public void orderedPhaseRetainsMutableProcessableTicketsWithoutRelease() {
        List<Integer> releases = new ArrayList<>();
        List<Integer> retained = RestoredTicketPhases.retain(Arrays.asList(1, 2, 3), 2, value -> value != 2);

        retained.add(4);
        assertEquals(Arrays.asList(1, 3, 4), retained);
        assertEquals("ordered phase must never release non-live tickets", 0, releases.size());
    }

    @Test
    public void ordinaryPhaseReleasesEveryNowLiveTicketAfterImportAttempt() {
        List<String> events = new ArrayList<>();
        try {
            RestoredTicketPhases.importAndRelease(Arrays.asList(1, 2), new RestoredTicketPhases.LiveAction<Integer>() {

                @Override
                public void importTicket(Integer ticket) {
                    events.add("import" + ticket);
                    if (ticket == 2) throw new IllegalStateException("failed import");
                }

                @Override
                public void release(Integer ticket) {
                    events.add("release" + ticket);
                }
            });
        } catch (IllegalStateException expected) {
            assertEquals("failed import", expected.getMessage());
        }
        assertEquals(Arrays.asList("import1", "release1", "import2", "release2"), events);
    }
}
