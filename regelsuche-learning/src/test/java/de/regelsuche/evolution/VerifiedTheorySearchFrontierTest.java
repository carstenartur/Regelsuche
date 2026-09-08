package de.regelsuche.evolution;

import static de.regelsuche.evolution.VerifiedPolynomialFixture.*;
import static de.regelsuche.search.program.RewritePrograms.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.evolution.ExactFinitePolynomialPlanCandidateEvidenceVerifier.VerifiedCandidateEvidence;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.search.strategy.SearchExpansionSource;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Budget;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Problem;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Result;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Status;
import de.regelsuche.search.strategy.WorkSearchReplay;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationProvenance;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real verified evidence enters and leaves the same frontier as ordinary rules. */
@Timeout(20)
class VerifiedTheorySearchFrontierTest {
    @Test
    void searchesAcrossPrimitiveTheoryAndPrimitiveEdgesAtExactPathAllowance() {
        var evidence = unitEvidence("frontier-mixed");
        var result = search(mixedChoices(evidence), "x*x + 0", "x ^ 2", budget(2, work(evidence)));
        assertTrue(result.reached());
        assertTrue(result.expansionsComplete());
        var state = result.reachedState();
        assertEquals(List.of("x*x + 0", "x * x", "(1 * x) ^ 2", "x ^ 2"), state.path());
        assertEquals(3, state.edgeDepth());
        assertEquals(2, state.primitiveDepth());
        assertEquals(new ExecutionWork(2, 1, work(evidence)), state.executionWork());
        assertEquals(List.of("ast_add_zero_right", "ast_multiply_one_left"), state.primitiveRuleIds());
        assertInstanceOf(TransformationProvenance.ExactTheoryStep.class, state.transformations().get(1).provenance());
        assertEquals(List.of(2L, 1L, 1L), result.expansions().stream()
            .map(call -> call.execution().pathBudget().primitiveRewriteUnits()).toList());
        assertEquals(List.of(work(evidence), work(evidence), 0L), result.expansions().stream()
            .map(call -> call.execution().pathBudget().exactTheoryWorkUnits()).toList());
        assertEquals(state.executionWork(), result.metrics().transformationWork().candidateWork());
    }

    @Test
    void twoIndependentTheoriesCanExpandWithZeroPrimitiveAllowance() {
        var first = prepare("frontier-completion", "x^2+6*x+5", "(x+${shift})^2+${constant}",
            List.of(HoleDomain.integerRange("shift", 0, 4), HoleDomain.integerRange("constant", -5, 1)), 2)
            .evidence().getFirst();
        var second = prepare("frontier-factorization", first.data().transformedExpression(), "(x+${left})*(x+${right})",
            List.of(HoleDomain.integerRange("left", 0, 6), HoleDomain.integerRange("right", 0, 6)), 2)
            .evidence().getFirst();
        var program = choice("theories", ordinaryTheory("complete", first), ordinaryTheory("factor", second));
        var result = search(program, first.data().sourceExpression(), second.data().transformedExpression(),
            budget(0, work(first) + work(second)));
        assertTrue(result.reached());
        assertEquals(2, result.reachedState().edgeDepth());
        assertEquals(new ExecutionWork(0, 2, work(first) + work(second)), result.reachedState().executionWork());
        assertTrue(result.reachedState().primitiveRuleIds().isEmpty());
        assertTrue(result.toCanonicalJson().contains(first.evidenceHash()));
        assertTrue(result.toCanonicalJson().contains(second.evidenceHash()));
    }

    @Test
    void insufficientTheoryBudgetKeepsEvidenceAndFullAttemptedWorkOutsideFrontier() {
        var evidence = unitEvidence("frontier-theory-block");
        var result = search(mixedChoices(evidence), "x*x + 0", "x ^ 2", budget(2, work(evidence) - 1));
        assertFalse(result.reached());
        assertEquals(Status.PATH_WORK_BUDGET, result.status());
        assertFalse(result.expansionsComplete());
        assertEquals(1, result.metrics().enqueuedStates());
        assertEquals(new ExecutionWork(1, 1, work(evidence)), result.metrics().transformationWork().candidateWork());
        assertTrue(result.exploredStates().stream().allMatch(state -> state.executionWork().exactTheorySteps() == 0));
        var rejected = result.expansions().stream().flatMap(call -> call.execution().sourceObservations().stream())
            .filter(observation -> !observation.admitted()).findFirst().orElseThrow();
        assertEquals(work(evidence) - 1, rejected.availableBudget().exactTheoryWorkUnits());
        assertTrue(rejected.candidate().provenance().toCanonicalJson().contains(evidence.evidenceHash()));
    }

    @Test
    void primitiveAllowanceIsNotResetAfterATheoryEdge() {
        var evidence = unitEvidence("frontier-primitive-block");
        var result = search(mixedChoices(evidence), "x*x + 0", "x ^ 2", budget(1, work(evidence)));
        assertFalse(result.reached());
        assertEquals(Status.PATH_WORK_BUDGET, result.status());
        assertEquals(2, result.metrics().enqueuedStates());
        assertEquals(1, result.exploredStates().getLast().executionWork().primitiveRewrites());
        assertEquals(1, result.exploredStates().getLast().executionWork().exactTheorySteps());
        assertEquals(new ExecutionWork(2, 1, work(evidence)), result.metrics().transformationWork().candidateWork());
    }

    @Test
    void globalWorkIncludesVerifiedTheoryBeforeAnyCandidateIsEnqueued() {
        var evidence = unitEvidence("frontier-global-block");
        var program = ordinaryTheory("theory", evidence);
        var unrestricted = search(program, evidence.data().sourceExpression(), evidence.data().transformedExpression(),
            budget(0, work(evidence)));
        long formation = unrestricted.expansions().getFirst().execution().workMetrics().totalWorkUnitsV2();
        var result = search(program, evidence.data().sourceExpression(), evidence.data().transformedExpression(),
            new Budget(0, work(evidence), 20, 20, 20, formation - 1));
        assertEquals(Status.WORK_BUDGET, result.status());
        assertEquals(0, result.metrics().enqueuedStates());
        assertEquals(work(evidence), result.metrics().transformationWork().candidateWork().exactTheoryWorkUnits());
        assertTrue(result.toCanonicalJson().contains(evidence.evidenceHash()));
    }

    @Test
    void frontierAdministrationIsReservedBeforeInsertion() {
        var evidence = unitEvidence("frontier-admission-block");
        var program = ordinaryTheory("theory", evidence);
        var large = search(program, evidence.data().sourceExpression(), evidence.data().transformedExpression(),
            budget(0, work(evidence)));
        // Root visit + expansion + generated candidate + engine batch + admission check.
        long boundary = large.expansions().getFirst().execution().workMetrics().totalWorkUnitsV2() + 5;
        var blocked = search(program, evidence.data().sourceExpression(), evidence.data().transformedExpression(),
            new Budget(0, work(evidence), 20, 20, 20, boundary));
        assertEquals(Status.WORK_BUDGET, blocked.status());
        assertEquals(0, blocked.metrics().enqueuedStates());
        assertEquals(WorkBudgetBestFirstSearchStrategy.Decision.WORK_BUDGET, blocked.candidateDecisions().getFirst().outcome());
        var admitted = search(program, evidence.data().sourceExpression(), evidence.data().transformedExpression(),
            new Budget(0, work(evidence), 20, 20, 20, boundary + 2));
        assertTrue(admitted.reached());
        assertEquals(boundary + 2, admitted.metrics().chargedSearchWorkUnits());
    }

    @Test
    void composedMixedEdgeRetainsInnerPathAndCannotUseOneUnitForThreeSteps() {
        var evidence = unitEvidence("frontier-composed");
        var result = search(mixedProgram(evidence), "x*x + 0", "x ^ 2", budget(2, work(evidence)));
        assertTrue(result.reached());
        assertEquals(1, result.reachedState().edgeDepth());
        assertEquals(new ExecutionWork(2, 1, work(evidence)), result.reachedState().executionWork());
        var sequence = assertInstanceOf(TransformationProvenance.Sequence.class,
            result.reachedState().transformations().getFirst().provenance());
        assertEquals(3, sequence.steps().size());
        var blocked = search(mixedProgram(evidence), "x*x + 0", "x ^ 2", budget(1, work(evidence)));
        assertEquals(0, blocked.metrics().enqueuedStates());
        assertEquals(Status.PATH_WORK_BUDGET, blocked.status());
        assertEquals(result.metrics().transformationWork().candidateWork(), blocked.metrics().transformationWork().candidateWork());
    }

    @Test
    void sameOutputWithDifferentVerifiedEvidenceRemainsDistinctInFrontier() {
        var first = unitEvidence("frontier-evidence-a");
        var second = unitEvidence("frontier-evidence-b");
        var program = choice("alternatives", ordinaryTheory("a", first), ordinaryTheory("b", second));
        var result = search(program, first.data().sourceExpression(), "unreachable", budget(0, work(first)));
        assertEquals(2, result.metrics().enqueuedStates());
        assertEquals(3, result.exploredStates().size());
        assertEquals(0, result.metrics().duplicatePrunes());
        assertEquals(new ExecutionWork(0, 2, work(first) + work(second)), result.metrics().transformationWork().candidateWork());
        assertNotEquals(result.exploredStates().get(1).transformations(), result.exploredStates().get(2).transformations());
    }

    @Test
    void programFilteringAndDeduplicationRetainAllObservedTheoryWork() {
        var evidence = unitEvidence("frontier-discarded");
        var theory = ordinaryTheory("theory", evidence);
        var duplicate = choice("duplicates", theory, theory);
        var filtered = require("reject", duplicate, "declared rejection", candidate -> false);
        var result = search(filtered, evidence.data().sourceExpression(), evidence.data().transformedExpression(),
            budget(0, work(evidence)));
        assertEquals(0, result.metrics().enqueuedStates());
        assertTrue(result.expansionsComplete());
        assertEquals(new ExecutionWork(0, 2, 2 * work(evidence)), result.metrics().transformationWork().candidateWork());
        assertEquals(2, result.expansions().getFirst().execution().sourceObservations().size());
        assertTrue(result.metrics().transformationWork().duplicateCandidatesDropped() > 0);
    }

    @Test
    void incompleteProgramDoesNotBecomeACompleteSearchResult() {
        var evidence = unitEvidence("frontier-pruned");
        var second = unitEvidence("frontier-pruned-second");
        var program = require("none", prune("one", choice("two", ordinaryTheory("theory", evidence),
            ordinaryTheory("second", second)), 1, "declared pruning"), "reject retained candidate", candidate -> false);
        var result = search(program, evidence.data().sourceExpression(), evidence.data().transformedExpression(),
            budget(0, work(evidence)));
        assertEquals(Status.INCOMPLETE_EXPANSION, result.status());
        assertFalse(result.expansionsComplete());
        assertEquals(0, result.metrics().enqueuedStates());
        assertEquals(work(evidence) + work(second), result.metrics().transformationWork().candidateWork().exactTheoryWorkUnits());
    }

    @Test
    void freshVerifierAndSearchReplayBindEvidenceWorkAndObservations() {
        var first = unitEvidence("frontier-replay");
        var result = search(mixedChoices(first), "x*x + 0", "x ^ 2", budget(2, work(first)));
        var rebuilt = unitEvidence("frontier-replay");
        var problem = problem(mixedChoices(rebuilt), "x*x + 0", "x ^ 2", budget(2, work(rebuilt)));
        assertEquals(result, WorkSearchReplay.verify(result.toCanonicalJson(), problem));
        var json = result.toCanonicalJson();
        assertThrows(IllegalArgumentException.class, () -> WorkSearchReplay.verify(
            json.replace(first.evidenceHash(), "sha256:" + "0".repeat(64)), problem));
        assertThrows(IllegalArgumentException.class, () -> WorkSearchReplay.verify(
            json.replace("\"exactTheorySteps\":1", "\"exactTheorySteps\":0"), problem));
        assertThrows(IllegalArgumentException.class, () -> WorkSearchReplay.verify(
            json.replace("\"admitted\":true", "\"admitted\":false"), problem));
        assertThrows(IllegalArgumentException.class, () -> WorkSearchReplay.verify(
            json.replace("\"maxExploredStates\":20", "\"maxExploredStates\":21"), problem));
        assertThrows(IllegalArgumentException.class, () -> WorkSearchReplay.verify(
            json.replace("\"targetExpression\":\"x ^ 2\"", "\"targetExpression\":\"x ^ 3\""), problem));
    }

    private static RewriteProgram mixedChoices(VerifiedCandidateEvidence evidence) {
        return choice("frontier-rules", primitive("pre", "ast_add_zero_right"), ordinaryTheory("theory", evidence),
            primitive("post", "ast_multiply_one_left"));
    }

    private static Budget budget(int primitives, long theoryWork) { return new Budget(primitives, theoryWork, 20, 20, 20, 100_000); }

    private static Problem problem(RewriteProgram program, String input, String target, Budget budget) {
        return new Problem(input, target, new SearchExpansionSource.Program(program),
            new ExpressionScorer(), new ExpressionCanonicalizer(), budget);
    }

    private static Result search(RewriteProgram program, String input, String target, Budget budget) {
        return new WorkBudgetBestFirstSearchStrategy().search(problem(program, input, target, budget));
    }
}
