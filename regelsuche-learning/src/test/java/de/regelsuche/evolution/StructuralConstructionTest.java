package de.regelsuche.evolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(180)
class StructuralConstructionTest {
    private final StructuralConstructionContract contract = new StructuralConstructionContract();
    @Test void structuralGoalsRejectTrivialAndFalseShapes() { contract.structuralGoalsRejectTrivialAndFalseShapes(); }
    @Test void connectsHistoricalConstructionsToTheSameTaskSolver() { contract.connectsHistoricalConstructionsToTheSameTaskSolver(); }
    @Test void learnsStructuralSolutionsAndRestoresThem() { contract.learnsStructuralSolutionsAndRestoresThem(); }
    @Test void learnsVerifiedPartialProgressWithoutInventingCompletion() { contract.learnsVerifiedPartialProgressWithoutInventingCompletion(); }
    @Test void learnsResidualCompositionAndPreservesBudgetAndProfile() throws Exception { contract.learnsResidualCompositionAndPreservesBudgetAndProfile(); }
}
