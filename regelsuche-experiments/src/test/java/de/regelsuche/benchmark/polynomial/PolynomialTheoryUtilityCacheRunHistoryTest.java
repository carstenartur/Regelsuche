package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityCacheRunHistoryTest {
    @Test
    void rejectsARealReplayWhoseEntryWasSeededOutsideTheRun() {
        var fixture = PolynomialTheoryUtilityObservedResultContractTest.mixedReplay();
        var history = new PolynomialTheoryUtilityCacheRunHistory(fixture.result().input().runId());
        var failure = assertThrows(IllegalArgumentException.class, () -> history.accept(fixture));
        assertEquals("cache hit lacks an earlier insertion in this run", failure.getMessage());
    }
}
