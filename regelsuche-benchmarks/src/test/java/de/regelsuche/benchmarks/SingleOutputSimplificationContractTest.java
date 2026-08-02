package de.regelsuche.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.benchmarks.ComparativeBenchmark.Case;
import de.regelsuche.benchmarks.ComparativeBenchmark.ExpectedVerdict;
import de.regelsuche.benchmarks.ComparativeBenchmark.ObservedVerdict;
import de.regelsuche.benchmarks.ComparativeBenchmark.Track;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.SimplificationSystem;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SingleOutputSimplificationContractTest {

    @Test
    void aVisitedReferenceDoesNotCountWhenTheTargetFreePolicySelectsAnotherOutput() {
        SimplificationSystem system = SimplificationSystem.internal(
            "single-output-test",
            "1",
            problem -> List.of(state("pinnedReference", 1), state("x", 2)),
            List.of());
        Case benchmarkCase = Case.create(
            "single-output-contract",
            Track.SIMPLIFICATION_COMPETITION,
            "contract",
            "input",
            "pinnedReference",
            List.of(),
            ExpectedVerdict.TARGET_REACHED);
        var parity = ComparativeBenchmarkCatalog.simplificationParity(
            List.of(benchmarkCase));
        var configuration =
            ComparativeBenchmarkCatalog.simplificationConfiguration(
                system, parity);

        var result = new ComparativeBenchmarkExecutor().runSimplification(
            system, configuration, benchmarkCase);

        assertEquals(ObservedVerdict.UNKNOWN, result.observedVerdict());
        assertEquals(1L, result.trackMetrics().get("producedExpression"));
        assertEquals(0L, result.trackMetrics().get("referenceFormReached"));
    }

    private static SearchState state(String expression, int depth) {
        return new SearchState(
            expression,
            depth,
            new ExpressionScorer().score(expression),
            List.of(expression),
            List.of(),
            Set.of(),
            0,
            expression,
            "",
            "",
            RewriteKind.SIMPLIFY,
            false,
            0,
            true,
            0
        );
    }
}
