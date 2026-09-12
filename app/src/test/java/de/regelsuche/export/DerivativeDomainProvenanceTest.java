package de.regelsuche.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.api.PathReplayDto;
import de.regelsuche.api.TransformationStepDto;
import de.regelsuche.api.searchgraph.SearchGraphAssembler;
import de.regelsuche.calculus.CalculusDerivativeRules;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.explain.ExplanationService;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.program.RewritePrograms;
import de.regelsuche.search.strategy.SearchExpansionSource;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RecordedExecution;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DerivativeDomainProvenanceTest {
    private static final ExpressionScorer SCORER = new ExpressionScorer();

    @ParameterizedTest
    @CsvSource({"ln,1 / x", "log,1 / (x * ln(10))"})
    void calculusDomainsSurviveRealSearchGraphExportAndFreshReplay(String function, String target) {
        String source = "diff(" + function + "(x), x)";
        var search = new WorkBudgetBestFirstSearchStrategy().search(problem(source, target));
        assertTrue(search.reached());
        var reached = search.reachedState();
        assertEquals(List.of("x > 0"), reached.assumptions());
        var actual = reached.transformations().getFirst();
        var execution = RecordedExecution.capture(source, reached.transformations());
        var before = SCORER.score(source);
        var after = SCORER.score(target);
        var step = new TransformationStep(0, source, target, actual.rule(), actual.kind(),
            before.weightedTotal(), after.weightedTotal(), actual.equivalencePreservingByConstruction(),
            actual.rule(), actual.assumptions(), execution);
        var path = new DiscoveredTransformation("derivative-" + function, source, target, List.of(step),
            before, after, before.improvementTo(after), CandidateProofStatus.OBSERVED, Instant.EPOCH,
            new ExpressionCanonicalizer().stableHash(target));

        var store = new InMemoryExpressionGraphStore();
        store.saveNode(source, before.weightedTotal());
        store.saveNode(target, after.weightedTotal());
        store.saveDiscoveredTransformation(path);
        store.saveEdge(new GraphEdge(source, target, actual.rule(), 1, path.totalImprovement(), path.id(),
            path.canonicalHash(), before.weightedTotal(), after.weightedTotal(), actual.kind(),
            actual.mayIncreaseComplexity(), actual.estimatedCostDelta(),
            actual.equivalencePreservingByConstruction(), CandidateProofStatus.OBSERVED, null, execution));
        var graph = new SearchGraphAssembler().assemble(store.snapshot(), List.of());
        assertEquals(List.of("x > 0"), graph.edges().getFirst().assumptions());

        String json = new DefaultTransformationExportService().exportJson(store.discoveredTransformations(), List.of());
        var imported = new DefaultTransformationImportService().importJson(json).transformations().getFirst();
        assertEquals(List.of("x > 0"), imported.steps().getFirst().assumptions());
        assertEquals(List.of("x > 0"), TransformationStepDto.from(imported.steps().getFirst()).assumptions());
        var replayView = PathReplayDto.from(imported, new ExplanationService());
        assertEquals(List.of("x > 0"), replayView.steps().getFirst().execution().assumptions());
        var replay = RecordedPathReplay.verify(imported, () -> problem(source, target));
        assertEquals(List.of("x > 0"), replay.reachedState().assumptions());

        assertThrows(IllegalArgumentException.class, () -> new TransformationStep(0, source, target,
            actual.rule(), actual.kind(), before.weightedTotal(), after.weightedTotal(),
            actual.equivalencePreservingByConstruction(), actual.rule(), List.of(), execution));
    }

    private static WorkBudgetBestFirstSearchStrategy.Problem problem(String source, String target) {
        return new WorkBudgetBestFirstSearchStrategy.Problem(source, target,
            new SearchExpansionSource.Program(RewritePrograms.source("calculus",
                new AstRewriteTransformationEngine(CalculusDerivativeRules.rules()))),
            SCORER, new ExpressionCanonicalizer(),
            WorkBudgetBestFirstSearchStrategy.Budget.primitive(1, 8, 8, 1, 100_000));
    }
}
