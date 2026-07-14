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
        var variable = factoringObservation(
            "obs-variable", "factor-common", "x", "y", "z");
        var product = factoringObservation(
            "obs-product", "factor-common", "a * b", "c", "d");

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
        assertEquals(List.of("factor-common"), conjecture.postHocFamilies());
        assertNotEquals(conjecture.leftPattern(), conjecture.rightPattern());
        assertTrue(conjecture.leftPattern().contains("+"), conjecture.leftPattern());
        assertTrue(conjecture.leftPattern().contains("*"), conjecture.leftPattern());
        assertTrue(conjecture.rightPattern().contains("+"), conjecture.rightPattern());
        assertTrue(conjecture.rightPattern().contains("*"), conjecture.rightPattern());
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
        var first = factoringObservation("obs-first", "factor-common", "x", "y", "z");
        var renamed = factoringObservation("obs-renamed", "factor-common", "a", "b", "c");

        var report = miner.mine(List.of(first, renamed));

        assertTrue(report.conjectures().isEmpty());
        assertTrue(report.rejectedClusters().stream().anyMatch(rejected ->
            rejected.reason().equals("alpha-distinct-support<2")
                && rejected.supportCount() == 2
                && rejected.distinctAlphaSupport() == 1),
            report.rejectedClusters().toString());
    }

    @Test
    void rejectsObservationWithoutIndependentConvergentPaths() {
        String root = "x * y + x * z";
        String output = "x * (y + z)";
        TransformationEngine engine = expression -> expression.equals(root)
            ? List.of(step("open_target_single", output, expression))
            : List.of();
        SearchProblem problem = problem(root, engine);
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        assertEquals(GoalStatus.UNTARGETED, result.status());

        var report = miner.mine(List.of(OpenTargetConjectureMiner.OpenTargetObservation.from(
            "obs-single", "factor-common", root, result)));

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

    private OpenTargetConjectureMiner.OpenTargetObservation factoringObservation(
        String observationId,
        String family,
        String commonFactor,
        String leftTerm,
        String rightTerm
    ) {
        String factor = commonFactor.contains(" ") ? "(" + commonFactor + ")" : commonFactor;
        String root = factor + " * " + leftTerm + " + " + factor + " * " + rightTerm;
        String output = factor + " * (" + leftTerm + " + " + rightTerm + ")";
        String padded = "(" + output + ") + 0";
        TransformationEngine engine = expression -> {
            if (expression.equals(root)) {
                return List.of(
                    step("open_target_factor_direct", output, expression),
                    step("open_target_factor_padded", padded, expression));
            }
            if (expression.equals(padded)) {
                return List.of(step("open_target_remove_padding", output, expression));
            }
            return List.of();
        };
        SearchProblem problem = problem(root, engine);
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        assertEquals(GoalStatus.UNTARGETED, result.status());
        assertTrue(result.states().stream().anyMatch(state -> state.expression().equals(output)));
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