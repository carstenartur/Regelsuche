package de.regelsuche.evolution;

import static de.regelsuche.evolution.FinitePolynomialTraceFixtures.prepare;
import static de.regelsuche.evolution.FinitePolynomialTraceFixtures.trace;
import static de.regelsuche.evolution.FinitePolynomialTraceFixtures.training;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.ExactFinitePolynomialTraceLearner.LearnedPlan;
import de.regelsuche.evolution.ExactFinitePolynomialTraceLearner.Limits;
import de.regelsuche.evolution.ExactFinitePolynomialTraceLearner.TrainingTrace;
import de.regelsuche.evolution.FinitePolynomialStrategySearch.Grammar;
import de.regelsuche.evolution.FinitePolynomialStrategySearch.Outcome;
import de.regelsuche.evolution.FinitePolynomialStrategySearch.TrainingInput;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real formation, disjoint selection and fresh reuse; development cases, not FINAL TEST. */
@Timeout(60)
class TraceDerivedPolynomialStrategyTest {
    private static final Limits LIMITS = new Limits(3, 8, 4, 4, 0, 12, 10_000);
    private final ExactFinitePolynomialTraceLearner learner = new ExactFinitePolynomialTraceLearner();
    private final FinitePolynomialStrategySearch search = new FinitePolynomialStrategySearch();

    @Test
    void templateFactoryOwnsTheSharedTextLimitAndNamesMissingDomains() {
        var domains = List.of(HoleDomain.integerRange("coefficient", 0, 1));
        String expression = "@v+${coefficient}";
        String atLimit = expression + " ".repeat(FinitePolynomialTemplate.MAX_EXPRESSION_CHARS - expression.length());
        var template = FinitePolynomialTemplate.declared("at-limit", atLimit, domains);
        assertEquals(expression, template.expression());
        assertEquals(List.of(template), new Grammar(List.of(template), 1, 0, 0).templates());
        assertThrows(IllegalArgumentException.class, () ->
            FinitePolynomialTemplate.declared("too-long", atLimit + " ", domains));
        assertEquals("domains", assertThrows(NullPointerException.class, () ->
            FinitePolynomialTemplate.declared("missing-domains", expression, null)).getMessage());
    }

    @Test
    void commonTemplatesRetainEveryObservedFormationStateAndVerifierRoot() {
        var traces = training();
        var plan = learner.learn(traces, LIMITS);
        assertEquals(15, plan.formationStateObservations());
        assertTrue(plan.trainingIdentityWorkUnits() > 0);
        for (var template : plan.stages()) {
            assertEquals(FinitePolynomialTemplate.Origin.VERIFIED_TRACE_DERIVED, template.origin());
            assertEquals(FinitePolynomialTemplate.Applicability.EXACT_SOURCE_SHAPE, template.applicability());
            assertEquals(plan.contentHash(), template.formationHash());
            assertEquals(plan.trainingRoots(), template.provenanceRoots());
            assertEquals(plan.trainingInputIdentities(), template.formationInputIdentities());
            assertEquals(plan.formationStateIdentities(), template.formationStateIdentities());
            assertTrue(template.expression().contains("@v"));
            for (var trace : traces) {
                var path = trace.execution().candidates().getFirst();
                assertExcluded(template, path.sourceExpression());
                for (var step : path.steps()) {
                    assertExcluded(template, step.transition().sourceExpression());
                    assertExcluded(template, step.transition().transformedExpression());
                }
            }
        }
    }

    @Test
    void learnsSelectsThenExecutesWithFreshVerifiedCoefficients() {
        var plan = learned();
        var selection = search.train(grammar(plan, 2000), selectionInputs());
        assertEquals(12, selection.rows().size());
        assertEquals(ids(plan), selection.selectedSequence().orElseThrow());
        String frozen = selection.toCanonicalJson();
        var application = search.apply(selection, "u^2+14*u+45");
        assertEquals(Outcome.OBJECTIVE_REACHED, application.trial().outcome());
        assertEquals(selection.contentHash(), application.selectionHash());
        var path = application.trial().execution().orElseThrow().candidates().getFirst();
        assertEquals(0L, path.primitiveRewriteSteps());
        assertEquals(2L, path.exactTheorySteps());
        assertTrue(List.of("(u + 5) * (u + 9)", "(u + 9) * (u + 5)").contains(path.transformedExpression()));
        assertEquals(1014L, application.trial().assignmentEvaluations());
        for (var attempt : application.trial().attempts()) {
            var evidence = attempt.candidate().orElseThrow();
            assertTrue(selection.rows().stream().flatMap(row -> row.trial().attempts().stream())
                .flatMap(old -> old.candidate().stream()).noneMatch(old -> old.evidenceHash().equals(evidence.evidenceHash())));
        }
        assertEquals(Outcome.COMPLETE_NO_SOLUTION, search.apply(selection, "v^2+3*v+2").trial().outcome());
        assertEquals(frozen, selection.toCanonicalJson());
    }

    @Test
    void formationRootsAndIntermediateStatesCannotLeakIntoSelectionOrApplication() {
        var traces = training();
        var plan = learner.learn(traces, LIMITS);
        var selected = search.train(grammar(plan, 2000), selectionInputs());
        var intermediate = traces.getFirst().execution().candidates().getFirst().steps().getLast()
            .transition().sourceExpression();
        for (String leaked : List.of("x^2+6*x+5", "5+6*z+z^2", "(z+1)*(z+5)", "z^2+7*z-z+5", intermediate)) {
            assertTrue(assertThrows(IllegalArgumentException.class, () -> search.train(grammar(plan, 0),
                List.of(new TrainingInput("leaked", leaked)))).getMessage().contains("formation state"));
            assertTrue(assertThrows(IllegalArgumentException.class, () -> search.apply(selected, leaked))
                .getMessage().contains("formation state"));
        }
        assertThrows(IllegalArgumentException.class, () -> plan.instantiate(1, intermediate));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> search.apply(selected, "16+10*z+z^2"))
            .getMessage().contains("TRAIN polynomial"));
    }

    @Test
    void formationExclusionIncludesTemplatesThatDidNotWinSelection() {
        var plan = learned();
        var declared = FinitePolynomialTemplate.declared("direct-factors", "(@v+${left})*(@v+${right})",
            List.of(HoleDomain.integerRange("left", 0, 12), HoleDomain.integerRange("right", 0, 12)));
        var grammar = new Grammar(List.of(declared, plan.stages().getFirst()), 1, 2000, 1000);
        var selection = search.train(grammar, selectionInputs());
        assertEquals(List.of("direct-factors"), selection.selectedSequence().orElseThrow());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> search.apply(selection, "(z+2)*(z+6)"))
            .getMessage().contains("formation state"));
        assertEquals(Outcome.OBJECTIVE_REACHED, search.apply(selection, "z^2+14*z+45").trial().outcome());
    }

    @Test
    void sourceMismatchIsRetainedWithoutRunningTheSolver() {
        var plan = learned();
        var grammar = new Grammar(List.of(plan.stages().getLast()), 1, 2000, 1000);
        var selection = search.train(grammar, selectionInputs());
        for (var row : selection.rows()) {
            var trial = row.trial();
            assertEquals(Outcome.APPLICABILITY_MISMATCH, trial.outcome());
            assertTrue(trial.attempts().isEmpty());
            assertEquals(0L, trial.assignmentEvaluations());
            assertFalse(trial.applicabilityChecks().getFirst().matches());
            assertEquals(0L, trial.applicabilityViewWork()); // Input projection is counted once by train().
            assertTrue(trial.applicabilityShapeNodeVisits() > 0);
        }
        assertTrue(selection.inputViewWork() > 0);
        var noSolution = search.train(new Grammar(List.of(plan.stages().getFirst()), 1, 2000, 1000),
            List.of(new TrainingInput("odd", "x^2+3*x+2"))).rows().getFirst().trial();
        assertEquals(Outcome.COMPLETE_NO_SOLUTION, noSolution.outcome());
        assertEquals(507L, noSolution.assignmentEvaluations());
    }

    @Test
    void applicabilityChecksDoNotResetTheRemainingAssignmentBudget() {
        var plan = learned();
        var exact = search.train(grammar(plan, 1014), selectionInputs());
        var shortBudget = search.train(grammar(plan, 1013), selectionInputs());
        assertEquals(ids(plan), exact.selectedSequence().orElseThrow());
        for (var row : shortBudget.rows().stream().filter(row -> row.trial().sequence().equals(ids(plan))).toList()) {
            var trial = row.trial();
            assertEquals(Outcome.ASSIGNMENT_BUDGET_INCONCLUSIVE, trial.outcome());
            assertEquals(507L, trial.assignmentEvaluations());
            assertEquals(507L, trial.requiredNextAssignmentEvaluations());
            assertEquals(2, trial.applicabilityChecks().size());
            assertTrue(trial.applicabilityChecks().getLast().matches());
            assertTrue(trial.applicabilityViewWork() > 0);
        }
        assertTrue(shortBudget.selectedSequence().isEmpty());
        assertEquals(shortBudget.rows().stream().mapToLong(row -> row.trial().applicabilityViewWork()).sum(),
            shortBudget.totalApplicabilityViewWork());
    }

    @Test
    void completeProvenanceAndFiniteDomainsAreBoundIntoTheGrammar() {
        var traces = training();
        var plan = learner.learn(traces, LIMITS);
        Collections.reverse(traces);
        var reordered = learner.learn(traces, LIMITS);
        assertEquals(grammar(plan, 2000).contentHash(), grammar(reordered, 2000).contentHash());
        var changed = learner.learn(traces, new Limits(3, 8, 4, 4, 0, 11, 10_000));
        assertNotEquals(grammar(plan, 2000).contentHash(), grammar(changed, 2000).contentHash());
        var learned = plan.stages().getFirst();
        var declared = FinitePolynomialTemplate.declared(learned.id(), learned.expression(), learned.domains());
        assertNotEquals(learned.contentHash(), declared.contentHash());
        assertNotEquals(new Grammar(List.of(learned), 1, 2000, 1000).contentHash(),
            new Grammar(List.of(declared), 1, 2000, 1000).contentHash());
        assertThrows(UnsupportedOperationException.class, () -> learned.provenanceRoots().clear());
        assertThrows(UnsupportedOperationException.class, () -> learned.formationStateIdentities().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.stages().clear());
    }

    @Test
    void sharedIdentityKeepsAdjacentLargeIntegersDistinctAcrossFormationAndSelection() {
        List<TrainingTrace> traces = new ArrayList<>();
        for (int k : List.of(0, 2)) {
            traces.add(trace(List.of(prepare("large-fixed", "x+9007199254740992+" + k,
                "x+9007199254740992+${constant}+0", List.of(HoleDomain.integerRange("constant", 0, 2)), 1))));
        }
        var plan = learner.learn(traces, new Limits(2, 4, 4, 4, 0, 2, 100));
        var grammar = new Grammar(plan.stages(), 1, 0, 0);
        var fresh = search.train(grammar, List.of(new TrainingInput("fresh", "y+9007199254740993+0")));
        assertEquals(Outcome.ASSIGNMENT_BUDGET_INCONCLUSIVE, fresh.rows().getFirst().trial().outcome());
        for (String leaked : List.of("y+9007199254740992+0", "y+9007199254740993+1")) {
            assertTrue(assertThrows(IllegalArgumentException.class, () -> search.train(grammar,
                List.of(new TrainingInput("leaked", leaked)))).getMessage().contains("formation state"));
        }
    }

    @Test
    void sharedViewExhaustionAbortsInsteadOfClaimingNovelInputOrShapeMismatch() {
        var plan = learned();
        var formation = assertThrows(IllegalArgumentException.class, () -> plan.instantiate(0, "(x^32)^32"));
        var selection = assertThrows(IllegalArgumentException.class, () -> search.train(grammar(plan, 2000),
            List.of(new TrainingInput("large", "(x^32)^32"))));
        assertEquals(formation.getMessage(), selection.getMessage());
        assertTrue(selection.getMessage().contains("BUDGET_INCONCLUSIVE"));
    }

    @Test
    void learnedIntegerSyntaxRestrictionsAreAppliedBeforeCoefficientSolving() {
        var plan = learned();
        for (String input : List.of("x^2+3.5*x+2", "x^3+10*x+16", "x^2+" + "9".repeat(170) + "*x+2")) {
            var result = search.train(new Grammar(List.of(plan.stages().getFirst()), 1, 2000, 1000),
                List.of(new TrainingInput("unsupported-shape", input))).rows().getFirst().trial();
            assertEquals(Outcome.APPLICABILITY_MISMATCH, result.outcome());
            assertTrue(result.attempts().isEmpty());
            assertTrue(result.applicabilityShapeNodeVisits() > 0);
        }
    }

    private LearnedPlan learned() { return learner.learn(training(), LIMITS); }
    private static Grammar grammar(LearnedPlan plan, long assignments) {
        return new Grammar(plan.stages(), 2, assignments, 1000);
    }
    private static List<String> ids(LearnedPlan plan) {
        return plan.stages().stream().map(FinitePolynomialTemplate::id).toList();
    }
    private static List<TrainingInput> selectionInputs() {
        return List.of(new TrainingInput("select-one", "x^2+10*x+16"),
            new TrainingInput("select-two", "y^2+12*y+35"));
    }
    private static void assertExcluded(FinitePolynomialTemplate template, String expression) {
        var projection = ExactFinitePolynomialInput.analyze(new ExpressionParser().parseExactTerm(expression));
        assertTrue(template.formationStateIdentities().contains(projection.identity()));
    }
}
