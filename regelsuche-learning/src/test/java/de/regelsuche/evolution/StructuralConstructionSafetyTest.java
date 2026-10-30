package de.regelsuche.evolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(180)
class StructuralConstructionSafetyTest {
    private final StructuralConstructionSafetyContract contract = new StructuralConstructionSafetyContract();
    @Test void rejectsForeignSourcesAndRetainsUnsuccessfulWork() { contract.rejectsForeignSourcesAndRetainsUnsuccessfulWork(); }
    @Test void incrementalConstructionCannotHideAnAtomicOverrun() { contract.incrementalConstructionCannotHideAnAtomicOverrun(); }
    @Test void unhelpfulPartialCandidatesAreNotPromoted() { contract.unhelpfulPartialCandidatesAreNotPromoted(); }
}
