package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenTargetConjectureMinerTest {
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final OpenTargetConjectureMiner miner = new OpenTargetConjectureMiner();

    @Test
    void minesParameterizedConjectureFromRealUntargetedAlphaDistinctConvergence() {
        var variable = convergentObservation("obs-variable", "neutral-chain", "x");
        var product = convergentObservation("obs-product", "neutral-chain", "a * b");

        var report = miner.mine(List.of(variable, product));
        var reversed = miner.mine(List.of(product, variable));

        assertEquals(OpenTargetConjectureMiner.SCHEMA, report.schema());
        assertFalse(report.targetProvided());
        assertEquals(report, reversed, "input order must not change canonical evidence");
        assertEquals(1, report.conjectures().size(), report.rejectedClusters().toString());
        var conjecture = report.conjectures().getFirst();
        assertEquals(2, conjecture.supportCount());
        assertEquals(2, conjecture.distinctAlphaSupport());
        assertEquals(List.of("obs-product", "obs-variable"), conjecture.supportingObservationIds());
        assertEquals(List.of("neutral-chain"), conjecture.postHocFamilies());
        assertNotEquals(conjecture.leftPattern(), conjecture.rightPattern());
        assertTrue(conjecture.leftPattern().contains("0"), conjecture.leftPattern());
        assertFalse(conjecture.rightPattern().isBlank());
        assertEquals("OBSERVED_CONJECTURE", conjecture.candidateStatus());
        assertEquals("EQUIVALENCE_PRESERVING_CONVERGENT_PATHS", conjecture.evidenceStatus());
        assertTrue(conjecture.evidence().stream().allMatch(item ->
            item.searchStatus() == GoalStatus.UNTARGETED
                && item.paths().size() == 2
                && item.paths().stream().allMatch(path ->
                    path.expressions().getLast().equals(item.outputExpression()))));
    }

    @Test
    void rejectsSupportThatDiffersOnlyByAlphaRenaming() {
        var x = convergentObservation("obs-x", "neutral-chain", "x");
        var y = convergentObservation("obs-y", "neutral-chain", "y");

        var report = miner.mine(List.of(x, y));

        assertTrue(report.conjectures().isEmpty());
        assertTrue(report.rejectedClusters().stream().anyMatch(rejected ->
            rejected.reason().equals("alpha-distinct-support<2")
                && rejected.supportCount() == 2
                && rejected.distinctAlphaSupport() == 1),
            report.rejectedClusters().toString());
    }

    @Test
    void rejectsObservationWithoutIndependentConvergentPaths() {
        String root = "(x + 0) + 0";
        TransformationEngine engine = expression -> expression.equals(root)
            ? List.of(step("open_target_single", "x", expression))
            : List.of();
        SearchProblem problem = problem(root, engine);
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        assertEquals(GoalStatus.UNTARGETED, result.status());

        var report = miner.mine(List.of(OpenTargetConjectureMiner.OpenTargetObservation.from(
            "obs-single", "neutral-chain", root, result)));

        assertTrue(report.conjectures().isEmpty());
        assertEquals("no-independent-equivalence-preserving-convergence",
            report.rejectedClusters().getFirst().reason());
    }

    @Test
    void refusesEvidenceThatClaimsAResolvedTargetedSearch() {
        assertThrows(IllegalArgumentException.class, () ->
            new OpenTargetConjectureMiner.OpenTargetObservation(
                "obs-targeted", "post-hoc-family", "x", GoalStatus.REACHED, List.of()));
    }

    private OpenTargetConjectureMiner.OpenTargetObservation convergentObservation(
        String observationId,
        String family,
        String base
    ) {
        String wrapped = base.contains(" ") ? "(" + base + ")" : base;
        String root = "(" + wrapped + " + 0) + 0";
        String rightBranch = wrapped + " + 0";
        String leftBranch = "0 + " + wrapped;
        TransformationEngine engine = expression -> {
            if (expression.equals(root)) {
                return List.of(
                    step("open_target_branch_right", rightBranch, expression),
                    step("open_target_branch_left", leftBranch, expression));
            }
            if (expression.equals(rightBranch)) {
                return List.of(step("open_target_finish_right", base, expression));
            }
            if (expression.equals(leftBranch)) {
                return List.of(step("open_target_finish_left", base, expression));
            }
            return List.of();
        };
        SearchProblem problem = problem(root, engine);
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        assertEquals(GoalStatus.UNTARGETED, result.status());
        assertTrue(result.states().stream().anyMatch(state -> state.expression().equals(base)));
        return OpenTargetConjectureMiner.OpenTargetObservation.from(
            observationId, family, root, result);
    }

    private SearchProblem problem(String root, TransformationEngine engine) {
        return new SearchProblem(
            root,
            engine,
            scorer,
            canonicalizer,
            new SearchHeuristic(3, 40, 1, 2, 8, 8));
    }

    private static Transformation step(String rule, String output, String parent) {
        return new Transformation(
            rule,
            output,
            RewriteKind.SIMPLIFY,
            false,
            -2,
            true,
            rule + ":" + parent + "->" + output);
    }
}