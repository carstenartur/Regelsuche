package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MoveSearchVisitsTest {
    @Test void everyResourceDimensionRetainsAnIncomparableAlternative() {
        var visits = new MoveSearchVisits(SearchContinuationContract.DECLARED_STATE_LOCAL, ignored -> {});
        visits.add(state(4, 4, 4), 4);
        assertNull(visits.rejection(state(3, 5, 5), 5));
        assertNull(visits.rejection(state(5, 3, 5), 5));
        assertNull(visits.rejection(state(5, 5, 3), 5));
        assertNull(visits.rejection(state(5, 5, 5), 3));
        assertEquals(MoveSearch.Decision.DOMINATED, visits.rejection(state(5, 5, 5), 5));
        assertEquals(MoveSearch.Decision.DUPLICATE, visits.rejection(state(4, 4, 4), 4));
    }

    @Test void assumptionsAndCapabilitiesAreNotCollapsed() {
        var visits = new MoveSearchVisits(SearchContinuationContract.DECLARED_STATE_LOCAL, ignored -> {});
        visits.add(state(1, 1, 0), 0);
        assertNull(visits.rejection(new MoveState("x", 2, 2, "different", List.of("x != 0"), Set.of(), 0), 0));
        assertNull(visits.rejection(new MoveState("x", 2, 2, "different", List.of(), Set.of("division"), 0), 0));
        assertNull(visits.rejection(new MoveState("y", 2, 2, "different", List.of(), Set.of(), 0), 0));
        assertEquals(MoveSearch.Decision.DOMINATED,
            visits.rejection(new MoveState("x", 2, 2, "different", List.of(), Set.of(), 0), 0));
    }

    @Test void admittingBetterLabelInvalidatesOnlyItsDominatedPredecessors() {
        var visits = new MoveSearchVisits(SearchContinuationContract.DECLARED_STATE_LOCAL, ignored -> {});
        var expensive = state(5, 5, 5);
        var incomparable = state(1, 8, 1);
        var better = state(2, 2, 2);
        visits.add(expensive, 5);
        visits.add(incomparable, 1);
        assertTrue(visits.current(expensive, 5));
        visits.add(better, 2);
        assertFalse(visits.current(expensive, 5));
        assertTrue(visits.current(incomparable, 1));
        assertTrue(visits.current(better, 2));
    }

    @Test void indexChargesAreExplicitWhileLegacyAccountingIsUnchanged() {
        var charged = new AtomicLong();
        var visits = new MoveSearchVisits(SearchContinuationContract.DECLARED_STATE_LOCAL, charged::addAndGet);
        visits.add(state(2, 2, 2), 2);
        assertEquals(1, charged.get());
        visits.rejection(state(3, 3, 3), 3);
        assertEquals(3, charged.get());
        visits.current(state(2, 2, 2), 2);
        assertEquals(4, charged.get());
        visits.add(state(1, 1, 1), 1);
        assertEquals(7, charged.get());
        charged.set(0);
        var legacy = new MoveSearchVisits(SearchContinuationContract.PATH_SENSITIVE, charged::addAndGet);
        legacy.add(state(2, 2, 2), 2);
        assertNull(legacy.rejection(state(3, 3, 3), 3));
        assertEquals(MoveSearch.Decision.DUPLICATE, legacy.rejection(state(2, 2, 2), 2));
        assertTrue(legacy.current(state(2, 2, 2), 2));
        assertEquals(0, charged.get());
    }
    private static MoveState state(int depth, int primitives, int debt) {
        return new MoveState("x", depth, primitives, "same", List.of(), Set.of(), debt);
    }
}
