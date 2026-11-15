package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.ExactFinitePolynomialTraceLearner.LearnedPlan;
import de.regelsuche.evolution.ExactFinitePolynomialTraceLearner.Limits;
import de.regelsuche.evolution.FinitePolynomialStrategySearch.Grammar;
import de.regelsuche.evolution.FinitePolynomialStrategySearch.Outcome;
import de.regelsuche.evolution.FinitePolynomialStrategySearch.TrainingInput;
import de.regelsuche.evolution.FinitePolynomialTemplate.Applicability;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Public development comparison fixed before running either learned matcher on the new forms. */
@Timeout(60)
class TracePlanTransferTest {
    private static final Limits LIMITS = new Limits(3, 8, 4, 4, 0, 12, 10_000);
    private static final long ASSIGNMENT_BUDGET = 2_000;
    private static final long PATH_BUDGET = 1_000;
    private static final List<TrainingInput> SELECTION = List.of(
        new TrainingInput("select-one", "x^2+10*x+16"),
        new TrainingInput("select-two", "y^2+12*y+35"));
    private static final List<Case> TRANSFER = List.of(
        new Case("product-plus", "u*(u+16)+55",
            Outcome.OBJECTIVE_REACHED, Outcome.OBJECTIVE_REACHED, "(u + 5)", "(u + 11)"),
        new Case("shifted-square", "(v+3)^2+10*v+54",
            Outcome.OBJECTIVE_REACHED, Outcome.OBJECTIVE_REACHED, "(v + 7)", "(v + 9)"),
        new Case("no-integer-square-offset", "w*(w+11)+30",
            Outcome.COMPLETE_NO_SOLUTION, Outcome.OBJECTIVE_REACHED, "(w + 5)", "(w + 6)"),
        new Case("no-factor-in-domain", "n*(n+14)+50",
            Outcome.COMPLETE_NO_SOLUTION, Outcome.COMPLETE_NO_SOLUTION, "", ""),
        new Case("different-support", "q*(q^2+1)+2",
            Outcome.APPLICABILITY_MISMATCH, Outcome.COMPLETE_NO_SOLUTION, "", ""),
        new Case("already-factored", "(r+2)*(r+7)",
            Outcome.ALREADY_SATISFIED, Outcome.ALREADY_SATISFIED, "", ""));

    private record Case(String id, String input, Outcome learned, Outcome direct,
                        String leftFactor, String rightFactor) {}

    @Test
    void frozenPlanReusesACheckedIntermediateOnBothWithheldForms() {
        var learner = new ExactFinitePolynomialTraceLearner();
        var traces = FinitePolynomialTraceFixtures.training();
        var ordered = learner.learn(traces, LIMITS);
        var support = learner.learnWithMonomialSupport(traces, LIMITS);
        assertNotEquals(ordered.contentHash(), support.contentHash());
        assertEquals("0,1,2", support.stages().getFirst().sourceShape());
        assertEquals(Applicability.EXACT_MONOMIAL_SUPPORT, support.stages().getFirst().applicability());
        assertEquals(Applicability.EXACT_SOURCE_SHAPE, support.stages().getLast().applicability());
        assertEquals(ordered.formationStateIdentities(), support.formationStateIdentities());
        var search = new FinitePolynomialStrategySearch();
        var orderedPlan = select(search, ordered);
        var supportPlan = select(search, support);
        var direct = FinitePolynomialTemplate.declared("direct-factors", "(@v+${left})*(@v+${right})",
            List.of(HoleDomain.integerRange("left", 0, 12), HoleDomain.integerRange("right", 0, 12)));
        var directPlan = search.train(new Grammar(List.of(direct), 1, ASSIGNMENT_BUDGET, PATH_BUDGET), SELECTION);
        var frozenHash = supportPlan.contentHash();
        long formationAssignments = traces.stream().flatMap(trace -> trace.evidence().stream()).mapToLong(evidence -> {
            // The fixture executed generation, receipt replay and the confirmed exact replay.
            var work = evidence.data().canonicalWork();
            return work.evaluatedAssignmentsPerReplay() * (2L + work.exactReplayExecutions());
        }).sum();
        System.out.printf("formation assignments=%d, input projection work ordered=%d support=%d%n",
            formationAssignments, ordered.trainingIdentityWorkUnits(), support.trainingIdentityWorkUnits());
        System.out.printf("selection assignments ordered=%d support=%d direct=%d%n",
            orderedPlan.totalTrainingAssignmentEvaluations(), supportPlan.totalTrainingAssignmentEvaluations(),
            directPlan.totalTrainingAssignmentEvaluations());
        for (var example : TRANSFER) {
            var orderedApplication = search.apply(orderedPlan, example.input());
            var supportApplication = search.apply(supportPlan, example.input());
            var directApplication = search.apply(directPlan, example.input());
            var orderedTrial = orderedApplication.trial();
            var supportTrial = supportApplication.trial();
            var directTrial = directApplication.trial();
            assertEquals(example.learned() == Outcome.ALREADY_SATISFIED
                ? Outcome.ALREADY_SATISFIED : Outcome.APPLICABILITY_MISMATCH, orderedTrial.outcome(), example.id());
            assertEquals(example.learned(), supportTrial.outcome(), example.id());
            assertEquals(example.direct(), directTrial.outcome(), example.id());
            assertEquals(0L, orderedTrial.assignmentEvaluations(), example.id());
            if (example.learned() == Outcome.OBJECTIVE_REACHED) {
                verifyFactors(supportTrial, example, 2);
            }
            if (example.direct() == Outcome.OBJECTIVE_REACHED) {
                verifyFactors(directTrial, example, 1);
            }
            if (example.learned() == Outcome.APPLICABILITY_MISMATCH) {
                assertFalse(supportTrial.applicabilityChecks().getFirst().matches());
                assertTrue(supportTrial.attempts().isEmpty());
            }
            System.out.printf("%s ordered=%s support=%s direct=%s%n", example.id(),
                counters(orderedApplication), counters(supportApplication), counters(directApplication));
        }
        assertEquals(frozenHash, supportPlan.contentHash());
    }

    @Test
    void monomialSupportKeepsTheTrainingExclusionAndBoundedIntegerDomain() {
        var plan = new ExactFinitePolynomialTraceLearner().learnWithMonomialSupport(
            FinitePolynomialTraceFixtures.training(), LIMITS);
        assertThrows(IllegalArgumentException.class, () -> plan.instantiate(0, "z*(z+6)+5"));
        assertThrows(IllegalArgumentException.class, () -> plan.instantiate(0, "r^2+2.5*r+1"));
        assertTrue(plan.instantiate(0, "q*(q^2+1)+2").isEmpty());
        assertTrue(plan.instantiate(1, "u*(u+16)+55").isEmpty());
        assertTrue(plan.instantiate(0, "u*(u+16)+55").orElseThrow().sourceExpression().contains("u * (u + 16)"));

        var search = new FinitePolynomialStrategySearch();
        var selected = select(search, plan);
        assertThrows(IllegalArgumentException.class, () -> search.apply(selected, "z*(z+6)+5"));
        assertThrows(IllegalArgumentException.class, () -> search.apply(selected, "z*(z+10)+16"));
        var outOfDomain = search.apply(selected, "r^2+2.5*r+1").trial();
        assertEquals(Outcome.APPLICABILITY_MISMATCH, outOfDomain.outcome());
        assertTrue(outOfDomain.attempts().isEmpty());
    }

    private static FinitePolynomialStrategySearch.FrozenSelection select(
            FinitePolynomialStrategySearch search, LearnedPlan plan) {
        return search.train(new Grammar(plan.stages(), 2, ASSIGNMENT_BUDGET, PATH_BUDGET), SELECTION);
    }

    private static String counters(FinitePolynomialStrategySearch.Application application) {
        var trial = application.trial();
        return "%s(assignments=%d,viewWork=%d,shapeVisits=%d,pathWork=%d)".formatted(trial.outcome(),
            trial.assignmentEvaluations(),
            application.inputViewWork() + trial.applicabilityViewWork() + trial.objectiveViewWork(),
            trial.applicabilityShapeNodeVisits(), trial.pathWork());
    }

    private static void verifyFactors(FinitePolynomialStrategySearch.Trial trial, Case example, long steps) {
        var path = trial.execution().orElseThrow().candidates().getFirst();
        assertEquals(steps, path.exactTheorySteps());
        assertEquals(trial.input(), path.sourceExpression());
        String left = example.leftFactor();
        String right = example.rightFactor();
        assertTrue(List.of(left + " * " + right, right + " * " + left)
                .contains(path.transformedExpression()), path.transformedExpression());
        assertTrue(trial.attempts().stream().allMatch(attempt -> attempt.candidate().isPresent()));
    }
}
