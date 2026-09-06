package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.FinitePolynomialStrategySearch.FrozenSelection;
import de.regelsuche.evolution.FinitePolynomialStrategySearch.Grammar;
import de.regelsuche.evolution.FinitePolynomialStrategySearch.Outcome;
import de.regelsuche.evolution.FinitePolynomialStrategySearch.Template;
import de.regelsuche.evolution.FinitePolynomialStrategySearch.TrainingInput;
import de.regelsuche.evolution.FinitePolynomialStrategySearch.Trial;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Development characterization, not a sealed FINAL TEST or learned grammar. */
@Timeout(30)
class FinitePolynomialStrategySearchTest {
    private final FinitePolynomialStrategySearch search = new FinitePolynomialStrategySearch();

    @Test
    void enumeratesCompleteTrainingMatrixAndSelectsDirectRatherThanForcedComposition() {
        FrozenSelection selection = search.train(grammar(), training());
        assertEquals(18, selection.rows().size());
        assertEquals(6, selection.scores().size());
        assertEquals(List.of("factors"), selection.selectedSequence().orElseThrow());
        var direct = selection.scores().getFirst();
        var composed = selection.scores().stream().filter(score ->
            score.sequence().equals(List.of("completion", "factors"))).findFirst().orElseThrow();
        assertEquals(2, direct.hits());
        assertEquals(direct.hits(), composed.hits());
        assertTrue(direct.assignmentEvaluations() < composed.assignmentEvaluations());
        assertTrue(direct.pathWork() < composed.pathWork());
        assertEquals(12012L, selection.totalTrainingAssignmentEvaluations());
        assertEquals(6L, selection.rows().stream().filter(row -> row.inputId().equals("train-negative")).count());
        for (var row : selection.rows()) {
            long actual = row.trial().attempts().stream().mapToLong(attempt -> attempt.assignmentEvaluations()).sum();
            assertEquals(actual, row.trial().assignmentEvaluations());
            assertEquals(selection.grammar().contentHash(), row.trial().grammarHash());
        }
    }

    @Test
    void frozenSequenceRunsOnFreshCoefficientsAndVariableWithoutRetainingTrainingCandidate() {
        FrozenSelection selection = search.train(grammar(), training());
        String frozen = selection.toCanonicalJson();
        String hash = selection.contentHash();
        var first = search.apply(selection, "y^2+y-6");
        var second = search.apply(selection, "z^2+11*z+30");
        for (var application : List.of(first, second)) {
            assertEquals(hash, application.selectionHash());
            assertEquals(Outcome.OBJECTIVE_REACHED, application.trial().outcome());
            assertEquals(List.of("factors"), application.trial().sequence());
            var result = application.trial().execution().orElseThrow().candidates().getFirst();
            assertEquals(0L, result.primitiveRewriteSteps());
            assertEquals(1L, result.exactTheorySteps());
            var evidence = application.trial().attempts().getFirst().candidate().orElseThrow();
            assertEquals(evidence.evidenceHash(), result.steps().getFirst().transition().evidenceHash());
            assertTrue(selection.rows().stream().flatMap(row -> row.trial().attempts().stream())
                .flatMap(attempt -> attempt.candidate().stream())
                .noneMatch(old -> old.evidenceHash().equals(evidence.evidenceHash())));
        }
        assertEquals(Outcome.COMPLETE_NO_SOLUTION, search.apply(selection, "t^2+2").trial().outcome());
        assertEquals(frozen, selection.toCanonicalJson());
        assertEquals(hash, selection.contentHash());
    }

    @Test
    void differentTrainingDataCanSelectADifferentSchema() {
        Template scaled = new Template("scaled", "${scale}*@v*(@v+${shift})",
            List.of(HoleDomain.integerRange("scale", 2, 3), HoleDomain.integerRange("shift", 1, 3)));
        Grammar grammar = new Grammar(List.of(factors(), scaled), 1, 2000, 1000);
        var monic = search.train(grammar, List.of(new TrainingInput("monic", "x^2+3*x+2")));
        var nonmonic = search.train(grammar, List.of(new TrainingInput("nonmonic", "2*x^2+4*x")));
        assertEquals(List.of("factors"), monic.selectedSequence().orElseThrow());
        assertEquals(List.of("scaled"), nonmonic.selectedSequence().orElseThrow());
        assertNotEquals(monic.contentHash(), nonmonic.contentHash());
        assertEquals(Outcome.OBJECTIVE_REACHED, search.apply(nonmonic, "3*z^2+9*z").trial().outcome());
    }

    @Test
    void repeatedOrAlphaEquivalentTrainingAndApplicationInputsAreRejected() {
        var selection = search.train(grammar(), training());
        for (String duplicate : List.of("x*x+6*x+5", "y^2+6*y+5", " (x + 1)*(x + 5) ")) {
            assertThrows(IllegalArgumentException.class, () -> search.apply(selection, duplicate));
            assertThrows(IllegalArgumentException.class, () -> search.train(grammar(), List.of(
                new TrainingInput("original", "x^2+6*x+5"), new TrainingInput("duplicate", duplicate))));
        }
        assertThrows(IllegalArgumentException.class, () -> search.train(grammar(), List.of(
            new TrainingInput("same", "x^2+1"), new TrainingInput("same", "x^2+2"))));
    }

    @Test
    void zeroAndOneShortAssignmentBudgetsDoNotRunTheSolverOrPretendUnreachability() {
        for (long budget : List.of(0L, 350L)) {
            var selection = search.train(new Grammar(grammar().templates(), 2, budget, 1000), training());
            assertTrue(selection.selectedSequence().isEmpty());
            assertEquals(18, selection.rows().size());
            assertTrue(selection.rows().stream().allMatch(row ->
                row.trial().outcome() == Outcome.ASSIGNMENT_BUDGET_INCONCLUSIVE));
            assertEquals(0L, selection.totalTrainingAssignmentEvaluations());
            assertTrue(selection.rows().stream().allMatch(row -> row.trial().attempts().isEmpty()));
            assertTrue(selection.rows().stream().allMatch(row -> row.trial().requiredNextAssignmentEvaluations() > budget));
            assertThrows(IllegalStateException.class, () -> search.apply(selection, "y^2+y-6"));
        }
    }

    @Test
    void assignmentBudgetIsNotResetAfterTheFirstSequenceStep() {
        var selection = search.train(new Grammar(grammar().templates(), 2, 900, 1000), training());
        Trial repeated = trial(selection, "train-one", List.of("factors", "factors"));
        assertEquals(Outcome.ASSIGNMENT_BUDGET_INCONCLUSIVE, repeated.outcome());
        assertEquals(1, repeated.attempts().size());
        assertEquals(507L, repeated.assignmentEvaluations());
        assertEquals(507L, repeated.requiredNextAssignmentEvaluations());
        assertEquals(Outcome.OBJECTIVE_REACHED,
            trial(selection, "train-one", List.of("completion", "factors")).outcome());
    }

    @Test
    void pathBudgetOneUnitShortRetainsTheRealInterpreterBudgetBlock() {
        var exact = search.train(new Grammar(grammar().templates(), 2, 2000, 301), training());
        var shortBudget = search.train(new Grammar(grammar().templates(), 2, 2000, 300), training());
        assertEquals(Outcome.OBJECTIVE_REACHED, trial(exact, "train-one", List.of("completion", "factors")).outcome());
        Trial blocked = trial(shortBudget, "train-one", List.of("completion", "factors"));
        assertEquals(Outcome.PATH_BUDGET_INCONCLUSIVE, blocked.outcome());
        var block = blocked.execution().orElseThrow().budgetBlocks().getFirst();
        assertEquals(178L, block.availableWorkUnits());
        assertEquals(179L, block.requiredWorkUnits());
        assertEquals(858L, blocked.assignmentEvaluations());
        assertEquals(Outcome.OBJECTIVE_REACHED, trial(shortBudget, "train-one", List.of("factors")).outcome());
    }

    @Test
    void noSolutionNoChangeAndFailedObjectiveRemainDifferent() {
        var selection = search.train(grammar(), training());
        assertEquals(Outcome.COMPLETE_NO_SOLUTION, trial(selection, "train-negative", List.of("factors")).outcome());
        assertEquals(Outcome.NO_CHANGE, trial(selection, "train-one", List.of("completion", "completion")).outcome());
        assertEquals(Outcome.OBJECTIVE_MISS, trial(selection, "train-one", List.of("completion")).outcome());
    }

    @Test
    void factorizationAlreadyPresentAtDepthZeroGetsNoDiscoveryCredit() {
        var selection = search.train(grammar(), List.of(new TrainingInput("factored", "(x+1)*(x+2)")));
        assertTrue(selection.selectedSequence().isEmpty());
        assertTrue(selection.rows().stream().allMatch(row -> row.trial().outcome() == Outcome.ALREADY_SATISFIED));
        assertEquals(0L, selection.totalTrainingAssignmentEvaluations());
    }

    @Test
    void multiplyingByAConstantOrAnAlgebraicallyConstantFactorGetsNoCredit() {
        for (String prefix : List.of("${unit}", "(@v-@v+${unit})")) {
            var fake = new Template("fake", prefix + "*(@v^2+2*@v+1)", List.of(HoleDomain.integerRange("unit", 1, 1)));
            var selection = search.train(new Grammar(List.of(fake), 1, 30, 100),
                List.of(new TrainingInput("polynomial", "x^2+2*x+1")));
            assertTrue(selection.selectedSequence().isEmpty());
            assertEquals(Outcome.OBJECTIVE_MISS, selection.rows().getFirst().trial().outcome());
        }
    }

    @Test
    void retainedSolverTruncationIsNotErasedBySuccessfulSelectedExecution() {
        var padded = new Template("padded", "(@v+${left}+${pad}-${pad})*(@v+${right})",
            List.of(HoleDomain.integerRange("left", 1, 2), HoleDomain.integerRange("right", 1, 2),
                HoleDomain.integerRange("pad", 0, 2)));
        var selection = search.train(new Grammar(List.of(padded), 1, 100, 100),
            List.of(new TrainingInput("polynomial", "x^2+3*x+2")));
        Trial trial = selection.rows().getFirst().trial();
        assertEquals(Outcome.OBJECTIVE_REACHED, trial.outcome());
        assertEquals("COMPLETE_RESOLUTION_SET_TRUNCATED", trial.attempts().getFirst().run().status().name());
        assertEquals(6L, trial.attempts().getFirst().run().solverResult().matchingAssignments());
        assertEquals(36L, trial.assignmentEvaluations());
    }

    @Test
    void exactInputIdentityDoesNotCollapseAdjacentIntegersBeyondBinary64() {
        var selection = search.train(new Grammar(List.of(factors()), 1, 0, 0), List.of(
            new TrainingInput("large-one", "x+9007199254740992"),
            new TrainingInput("large-two", "x+9007199254740993")));
        assertEquals(2, selection.rows().size());
        assertNotEquals(selection.rows().getFirst().trial().inputAlphaPolynomialHash(),
            selection.rows().getLast().trial().inputAlphaPolynomialHash());
    }

    @Test
    void orderingAndFreshTrainingReproduceButChangingBudgetChangesTheCommitment() {
        var inputs = new ArrayList<>(training());
        var templates = new ArrayList<>(grammar().templates());
        Collections.reverse(inputs);
        Collections.reverse(templates);
        var first = search.train(grammar(), training());
        var second = search.train(new Grammar(templates, 2, 2000, 1000), inputs);
        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(search.apply(first, "y^2+y-6").contentHash(), search.apply(second, "y^2+y-6").contentHash());
        assertNotEquals(first.grammar().contentHash(), new Grammar(templates, 2, 1999, 1000).contentHash());
        assertThrows(UnsupportedOperationException.class, () -> first.rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.selectedSequence().orElseThrow().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.grammar().templates().clear());
    }

    @Test
    void invalidUnsupportedAndOversizedRequestsAbortInsteadOfBecomingMathematicalFailures() {
        assertThrows(IllegalArgumentException.class, () -> new Grammar(List.of(factors(), factors()), 2, 1000, 1000));
        assertThrows(IllegalArgumentException.class, () -> new Grammar(List.of(factors()), 4, 1000, 1000));
        assertThrows(IllegalArgumentException.class, () -> new Grammar(List.of(factors()), 1, -1, 1000));
        assertThrows(IllegalArgumentException.class, () -> new Template("bad", "x+${bad}", List.of(HoleDomain.signs("bad"))));
        assertThrows(IllegalArgumentException.class, () -> new Template("large", "@v+${left}+${right}",
            List.of(HoleDomain.integerRange("left", 1, 128), HoleDomain.integerRange("right", 1, 128))));
        for (String input : List.of("sin(x)", "x+y", "x/y", "1", "x\uD800", "(".repeat(200)+"x"+")".repeat(200))) {
            assertThrows(IllegalArgumentException.class, () -> search.train(grammar(), List.of(new TrainingInput("bad-input", input))));
        }
        assertThrows(IllegalArgumentException.class, () -> search.train(grammar(), List.of()));
    }

    @Test
    void finiteMatrixCeilingIsCheckedBeforeAnySolverWork() {
        List<Template> templates = new ArrayList<>();
        for (int i = 0; i < 4; i++) templates.add(new Template("template-"+i,
            factors().expression(), factors().domains()));
        List<TrainingInput> inputs = new ArrayList<>();
        for (int i = 0; i < 7; i++) inputs.add(new TrainingInput("input-"+i, "x^2+"+i));
        assertThrows(IllegalArgumentException.class, () -> search.train(new Grammar(templates, 3, 1000, 1000), inputs));
    }

    @Test
    void exportsTheActualFrozenSelectionAndEveryTrainingTrialAsDevelopmentEvidence() throws Exception {
        var selection = search.train(grammar(), training());
        Path output = Path.of("build/reports/finite-polynomial-strategy-selection");
        Files.createDirectories(output);
        Files.writeString(output.resolve("grammar.json"), selection.grammar().toCanonicalJson()+"\n");
        Files.writeString(output.resolve("selection.json"), selection.toCanonicalJson()+"\n");
        for (int i = 0; i < selection.rows().size(); i++) {
            Files.writeString(output.resolve("trial-"+i+".json"), selection.rows().get(i).trial().toCanonicalJson()+"\n");
        }
        assertFalse(selection.toCanonicalJson().contains("EXTERNALLY_NOVEL"));
        assertEquals(selection.toCanonicalJson()+"\n", Files.readString(output.resolve("selection.json")));
    }

    private static Trial trial(FrozenSelection selection, String inputId, List<String> sequence) {
        return selection.rows().stream().filter(row -> row.inputId().equals(inputId)
            && row.trial().sequence().equals(sequence)).findFirst().orElseThrow().trial();
    }
    private static Template factors() {
        return new Template("factors", "(@v+${left})*(@v+${right})",
            List.of(HoleDomain.integerRange("left", -6, 6), HoleDomain.integerRange("right", -6, 6)));
    }
    private static Grammar grammar() {
        var completion = new Template("completion", "(@v+${shift})^2+${constant}",
            List.of(HoleDomain.integerRange("shift", -4, 4), HoleDomain.integerRange("constant", -6, 6)));
        return new Grammar(List.of(completion, factors()), 2, 2000, 1000);
    }
    private static List<TrainingInput> training() {
        return List.of(new TrainingInput("train-one", "x^2+6*x+5"),
            new TrainingInput("train-two", "x^2-2*x-3"), new TrainingInput("train-negative", "x^2+1"));
    }
}
