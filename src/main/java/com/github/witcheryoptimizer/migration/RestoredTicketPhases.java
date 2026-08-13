package com.github.witcheryoptimizer.migration;

import java.util.ArrayList;
import java.util.List;

final class RestoredTicketPhases {

    private RestoredTicketPhases() {}

    interface OrderedAction<T> {

        boolean processable(T ticket);
    }

    interface LiveAction<T> {

        void importTicket(T ticket);

        void release(T ticket);
    }

    static <T> List<T> retain(List<T> offered, int maximum, OrderedAction<T> action) {
        List<T> retained = new ArrayList<>();
        for (T ticket : offered) {
            if (retained.size() >= maximum) break;
            if (action.processable(ticket)) retained.add(ticket);
        }
        return retained;
    }

    static <T> void importAndRelease(List<T> live, LiveAction<T> action) {
        for (T ticket : live) try {
            action.importTicket(ticket);
        } finally {
            action.release(ticket);
        }
    }
}
