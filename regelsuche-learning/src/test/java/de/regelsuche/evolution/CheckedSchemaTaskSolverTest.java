package de.regelsuche.evolution;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CheckedSchemaTaskSolverTest {
    private static CheckedSchemaTaskSolverContract contract;
    @BeforeAll static void learnAndRestore() { contract = new CheckedSchemaTaskSolverContract(); }
    @Test void learnedProcedureReplacesSixPrimitiveSteps() {
        contract.learnedProcedureReplacesSixPrimitiveStepsWithoutOpeningPrimitiveAlternatives();
        contract.printDevelopmentComparison();
    }
    @Test void onlyUnfinishedRemainderUsesPrimitiveFallback() {
        contract.primitiveFallbackSolvesOnlyTheUnfinishedRemainder();
    }
    @Test void stopsWithoutPretendingToEnumerateEverything() {
        contract.taskCompletionDoesNotPretendToBeExhaustiveExploration();
    }
    @Test void preparationIsReusableButQueryStateIsNot() {
        contract.freshInvocationsAndReloadedModelsHaveTheSameResults();
    }
    @Test void alreadySatisfiedInputDoesNotOpenProviders() {
        contract.alreadySatisfiedTasksDoNotOpenProviders();
    }
    @Test void finalReplayAndResourceLimitsPreventFalseSuccess() {
        contract.finalReplayAndOtherLimitsCanNeverBeHiddenByASolvedFlag();
    }
    @Test void missingPremisesCannotLeakBetweenQueries() {
        contract.missingAssumptionsDoNotAuthorizeLearnedApplications();
    }
    @Test void unsupportedAndNearMissInputsRemainUnsolved() {
        contract.unsupportedAndNearMissTasksRemainExplicitlyUnsolved();
    }
    @Test void invalidRequestsAreRejected() {
        contract.invalidGoalsAndInputsAreRejectedBeforeExecution();
    }
}
