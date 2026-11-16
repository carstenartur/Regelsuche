package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MoveSearchVisitsTest {
    private static final long UNLIMITED = Long.MAX_VALUE;

    @Test void everyResourceDimensionRetainsAnIncomparableAlternative() {
        var visits = new MoveSearchVisits<MoveState>(SearchContinuationContract.DECLARED_STATE_LOCAL, ignored -> {});
        visits.add(state(4, 4, 4), 4, UNLIMITED);
        assertNull(visits.rejection(state(3, 5, 5), 5, UNLIMITED));
        assertNull(visits.rejection(state(5, 3, 5), 5, UNLIMITED));
        assertNull(visits.rejection(state(5, 5, 3), 5, UNLIMITED));
        assertNull(visits.rejection(state(5, 5, 5), 3, UNLIMITED));
        assertEquals(MoveSearch.Decision.DOMINATED, visits.rejection(state(5, 5, 5), 5, UNLIMITED));
        assertEquals(MoveSearch.Decision.DUPLICATE, visits.rejection(state(4, 4, 4), 4, UNLIMITED));
    }

    @Test void assumptionsAndCapabilitiesAreNotCollapsed() {
        var visits = new MoveSearchVisits<MoveState>(SearchContinuationContract.DECLARED_STATE_LOCAL, ignored -> {});
        visits.add(state(1, 1, 0), 0, UNLIMITED);
        assertNull(visits.rejection(new MoveState("x", 2, 2, "different", List.of("x != 0"), Set.of(), 0), 0, UNLIMITED));
        assertNull(visits.rejection(new MoveState("x", 2, 2, "different", List.of(), Set.of("division"), 0), 0, UNLIMITED));
        assertNull(visits.rejection(new MoveState("y", 2, 2, "different", List.of(), Set.of(), 0), 0, UNLIMITED));
        assertEquals(MoveSearch.Decision.DOMINATED,
            visits.rejection(new MoveState("x", 2, 2, "different", List.of(), Set.of(), 0), 0, UNLIMITED));
    }

    @Test void admittingBetterLabelInvalidatesOnlyItsDominatedPredecessors() {
        var visits = new MoveSearchVisits<MoveState>(SearchContinuationContract.DECLARED_STATE_LOCAL, ignored -> {});
        var expensive = state(5, 5, 5);
        var incomparable = state(1, 8, 1);
        var better = state(2, 2, 2);
        visits.add(expensive, 5, UNLIMITED);
        visits.add(incomparable, 1, UNLIMITED);
        assertTrue(visits.current(expensive, 5));
        visits.add(better, 2, UNLIMITED);
        assertFalse(visits.current(expensive, 5));
        assertTrue(visits.current(incomparable, 1));
        assertTrue(visits.current(better, 2));
    }

    @Test void indexChargesAreExplicitWhileLegacyAccountingIsUnchanged() {
        var charged = new AtomicLong();
        var visits = new MoveSearchVisits<MoveState>(SearchContinuationContract.DECLARED_STATE_LOCAL, charged::addAndGet);
        visits.add(state(2, 2, 2), 2, UNLIMITED);
        assertEquals(1, charged.get());
        visits.rejection(state(3, 3, 3), 3, UNLIMITED);
        assertEquals(3, charged.get());
        visits.current(state(2, 2, 2), 2);
        assertEquals(4, charged.get());
        visits.add(state(1, 1, 1), 1, UNLIMITED);
        assertEquals(7, charged.get());
        charged.set(0);
        var legacy = new MoveSearchVisits<MoveState>(SearchContinuationContract.PATH_SENSITIVE, charged::addAndGet);
        assertTrue(legacy.add(state(2, 2, 2), 2, 0));
        assertNull(legacy.rejection(state(3, 3, 3), 3, 0));
        assertEquals(MoveSearch.Decision.DUPLICATE, legacy.rejection(state(2, 2, 2), 2, 0));
        assertTrue(legacy.current(state(2, 2, 2), 2));
        assertEquals(0, charged.get());
    }

    @Test void scanningAndReplacingAnAntichainRespectTheirOwnAllowance() {
        var charged = new AtomicLong();
        var visits = new MoveSearchVisits<MoveState>(SearchContinuationContract.DECLARED_STATE_LOCAL, charged::addAndGet);
        var shortPath = state(1, 6, 0);
        var frugalPath = state(6, 1, 0);
        var better = state(1, 1, 0);
        assertTrue(visits.add(shortPath, 0, UNLIMITED));
        assertTrue(visits.add(frugalPath, 0, UNLIMITED));

        charged.set(0);
        assertEquals(MoveSearch.Decision.WORK_LIMIT, visits.rejection(state(3, 3, 0), 0, 2));
        assertEquals(2, charged.get(), "the second comparison was not performed");
        assertNull(visits.rejection(state(3, 3, 0), 0, 3));

        charged.set(0);
        assertFalse(visits.add(better, 0, 4), "both invalidations must fit before replacing either label");
        assertEquals(3, charged.get(), "only the lookup and comparisons ran");
        assertTrue(visits.current(shortPath, 0));
        assertTrue(visits.current(frugalPath, 0));
        assertFalse(visits.current(better, 0));

        charged.set(0);
        assertTrue(visits.add(better, 0, 5));
        assertEquals(5, charged.get());
        assertFalse(visits.current(shortPath, 0));
        assertFalse(visits.current(frugalPath, 0));
        assertTrue(visits.current(better, 0));
    }

    private static MoveState state(int depth, int primitives, int debt) {
        return new MoveState("x", depth, primitives, "same", List.of(), Set.of(), debt);
    }
}
