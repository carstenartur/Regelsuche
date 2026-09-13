package de.regelsuche.math.algorithms.polynomial;

import org.junit.jupiter.api.Test;

class PolynomialWorkBudgetSnapshotTest {
    @Test void unchangedWorkReusesAnImmutableSnapshot() {
        EvidenceAllocationChecks.unchangedWorkReusesAnImmutableSnapshot();
    }
    @Test void everySnapshotMatchesAnIndependentLedger() {
        EvidenceAllocationChecks.everySnapshotMatchesAnIndependentLedger();
    }
}
