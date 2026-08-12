package com.github.witcheryoptimizer.migration;

import java.util.List;

public final class TicketBatch {

    private TicketBatch() {}

    public interface Actions<T> {

        boolean importTicket(T ticket) throws Exception;

        void release(T ticket);

        void finish(BatchResult result);

        void failure(T ticket, Throwable failure);
    }

    public static <T> void process(List<T> tickets, Actions<T> actions) {
        int successes = 0;
        int releaseFailures = 0;
        Throwable firstFatal = null;
        for (T ticket : tickets) {
            boolean imported = false;
            try {
                imported = actions.importTicket(ticket);
            } catch (Throwable failure) {
                report(actions, ticket, failure);
                if (failure instanceof Error && firstFatal == null) firstFatal = failure;
            }
            try {
                actions.release(ticket);
            } catch (Throwable failure) {
                releaseFailures++;
                report(actions, ticket, failure);
                if (failure instanceof Error && firstFatal == null) firstFatal = failure;
            }
            if (imported) successes++;
        }
        try {
            actions.finish(new BatchResult(successes, tickets.size(), releaseFailures));
        } catch (Throwable failure) {
            report(actions, null, failure);
            if (failure instanceof Error && firstFatal == null) firstFatal = failure;
        }

        if (firstFatal instanceof Error) throw (Error) firstFatal;
        if (firstFatal instanceof RuntimeException) throw (RuntimeException) firstFatal;
    }

    private static <T> void report(Actions<T> actions, T ticket, Throwable failure) {
        try {
            actions.failure(ticket, failure);
        } catch (Throwable ignored) {
            // Reporting must not prevent retained-ticket cleanup.
        }
    }

    public static final class BatchResult {

        public final int imported, offered, releaseFailures;

        BatchResult(int imported, int offered, int releaseFailures) {
            this.imported = imported;
            this.offered = offered;
            this.releaseFailures = releaseFailures;
        }
    }
}
