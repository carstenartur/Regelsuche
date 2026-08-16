package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.Assessment;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.AssessmentDecision;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.AtlasReport;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.CaseResult;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.EngineEvidence;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.EngineProfile;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.EquivalenceEvidence;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.EvidenceExecution;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.OracleEvidence;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.PrimaryStatus;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.RepresentationEvidence;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.SearchEvidence;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Case;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Relation;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Role;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.TargetRelation;
import de.regelsuche.benchmark.HistoricalWitnessPruningReport.CaseStatus;
import de.regelsuche.benchmark.HistoricalWitnessPruningReport.LossReason;
import de.regelsuche.benchmark.HistoricalWitnessPruningReport.TargetBlindTerminalStatus;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoricalWitnessPruningDiagnosticTest {
    private static final String CORPUS_HASH = "0".repeat(64);

    @Test
    void candidateBudgetStopsBeforeTheWitnessEdge() {
        Case benchmarkCase = benchmarkCase(
            "candidate-budget", "x", "x + 1", 5, 1);
        TransformationEngine engine = expression -> expression.equals("x")
            ? List.of(
                transformation("a-distractor", "x + 2", 0),
                transformation("z-witness", "x + 1", 0))
            : List.of();
        CaseResult atlasCase = caseResult(
            benchmarkCase,
            engine,
            List.of("x + 1"),
            List.of("z-witness"));

        HistoricalWitnessPruningReport.CaseDiagnostic result =
            new HistoricalWitnessPruningDiagnostic(ignored -> engine)
                .diagnose(benchmarkCase, atlasCase);

        assertEquals(CaseStatus.WITNESS_PREFIX_LOST, result.status());
        assertEquals(
            LossReason.CANDIDATE_BUDGET_BEFORE_WITNESS_EDGE,
            result.firstLoss().reason());
        assertEquals(
            TargetBlindTerminalStatus.CANDIDATE_BUDGET,
            result.searchTerminalStatus());
        assertEquals("max-candidates-per-state",
            result.firstLoss().event().pruningReason());
    }

    @Test
    void stateBudgetLeavesTheWitnessQueuedButUnexplored() {
        Case benchmarkCase = benchmarkCase(
            "state-budget", "x + 0", "x + y - y", 2, 8);
        TransformationEngine engine = expression -> switch (expression) {
            case "x + 0" -> List.of(
                transformation("expand-root", "x + y - y", 5),
                transformation("simplify-root", "x", -2));
            default -> List.of();
        };
        CaseResult atlasCase = caseResult(
            benchmarkCase,
            engine,
            List.of("x + y - y"),
            List.of("expand-root"));

        HistoricalWitnessPruningReport.CaseDiagnostic result =
            new HistoricalWitnessPruningDiagnostic(ignored -> engine)
                .diagnose(benchmarkCase, atlasCase);

        assertEquals(CaseStatus.WITNESS_PREFIX_LOST, result.status());
        assertEquals(
            LossReason.STATE_ENQUEUED_BUT_NOT_EXPLORED,
            result.firstLoss().reason());
        assertEquals(
            TargetBlindTerminalStatus.STATE_BUDGET,
            result.searchTerminalStatus());
        assertEquals("STATE_ENQUEUED", result.firstLoss().event().type());
    }

    @Test
    void reportIsContentAddressedAndByteStable(@TempDir Path directory)
            throws Exception {
        Case benchmarkCase = benchmarkCase(
            "stable-report", "x", "x + 1", 5, 1);
        TransformationEngine engine = expression -> expression.equals("x")
            ? List.of(
                transformation("a-distractor", "x + 2", 0),
                transformation("z-witness", "x + 1", 0))
            : List.of();
        CaseResult atlasCase = caseResult(
            benchmarkCase,
            engine,
            List.of("x + 1"),
            List.of("z-witness"));
        Corpus corpus = corpus(benchmarkCase);
        AtlasReport atlas = atlas(corpus, atlasCase);
        HistoricalWitnessPruningDiagnostic diagnostic =
            new HistoricalWitnessPruningDiagnostic(ignored -> engine);

        HistoricalWitnessPruningReport first = diagnostic.run(corpus, atlas);
        HistoricalWitnessPruningReport second = diagnostic.run(corpus, atlas);
        Path firstDirectory = directory.resolve("first");
        Path secondDirectory = directory.resolve("second");
        HistoricalWitnessPruningDiagnostic.WrittenArtifact firstArtifact =
            diagnostic.write(firstDirectory, first);
        diagnostic.write(secondDirectory, second);

        assertEquals(first.contentHash(), second.contentHash());
        assertTrue(first.contentHash().matches("sha256:[0-9a-f]{64}"));
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertArrayEquals(
            Files.readAllBytes(firstArtifact.path()),
            Files.readAllBytes(secondDirectory.resolve(
                HistoricalWitnessPruningReport.FILE_NAME)));
        assertNotNull(first.cases().get(0).firstLoss());
    }

    @Test
    void mismatchedAtlasIsRejected() {
        Case benchmarkCase = benchmarkCase(
            "binding", "x", "x + 1", 5, 1);
        TransformationEngine engine = expression -> List.of();
        CaseResult atlasCase = caseResult(
            benchmarkCase,
            engine,
            List.of("x + 1"),
            List.of("witness"));
        Corpus corpus = corpus(benchmarkCase);
        AtlasReport wrong = new AtlasReport(
            HistoricalRediscoveryAtlas.SCHEMA,
            corpus.schema(),
            "1".repeat(64),
            corpus.inventoryRevision(),
            corpus.claimBoundary(),
            List.of(atlasCase),
            List.of(),
            assessment());

        assertThrows(
            IllegalArgumentException.class,
            () -> new HistoricalWitnessPruningDiagnostic(ignored -> engine)
                .run(corpus, wrong));
    }

    private static CaseResult caseResult(
        Case benchmarkCase,
        TransformationEngine engine,
        List<String> witnessExpressions,
        List<String> witnessRules
    ) {
        ScalarRun scalar = scalarRun(benchmarkCase, engine);
        OracleEvidence oracle = new OracleEvidence(
            EvidenceExecution.EXECUTED,
            "REACHABLE",
            witnessExpressions,
            witnessRules,
            witnessRules.size(),
            witnessRules.size() + 1,
            scalar.generated(),
            witnessRules.size(),
            false,
            false,
            "");
        SearchEvidence scalarEvidence = new SearchEvidence(
            EvidenceExecution.EXECUTED,
            HistoricalWitnessPruningReport.SEARCH_POLICY,
            false,
            scalar.result().status().name(),
            scalar.result().states().size(),
            scalar.calls(),
            scalar.generated(),
            -1,
            List.of(),
            List.of(),
            scalar.result().metrics(),
            "");
        SearchEvidence omitted = new SearchEvidence(
            EvidenceExecution.NOT_EVALUATED,
            "",
            false,
            "NOT_EVALUATED",
            0,
            0,
            0,
            -1,
            List.of(),
            List.of(),
            null,
            "not evaluated");
        EngineEvidence production = new EngineEvidence(
            EngineProfile.PRODUCTION_PRIMITIVES,
            EvidenceExecution.EXECUTED,
            "",
            oracle,
            scalarEvidence,
            omitted,
            omitted);
        return new CaseResult(
            benchmarkCase,
            new RepresentationEvidence(
                true, benchmarkCase.source(), benchmarkCase.target(), ""),
            new EquivalenceEvidence(true, true, ""),
            production,
            production,
            production,
            PrimaryStatus.REACHABLE_BUT_PRODUCTION_SEARCH_MISSED);
    }

    private static ScalarRun scalarRun(
        Case benchmarkCase,
        TransformationEngine engine
    ) {
        CountingEngine counting = new CountingEngine(engine);
        SearchProblem problem = new SearchProblem(
            benchmarkCase.source(),
            counting,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(
                benchmarkCase.searchMaxDepth(),
                benchmarkCase.searchMaxVisitedStates(),
                1,
                benchmarkCase.maxExpandingSteps(),
                benchmarkCase.maxCandidatesPerState(),
                benchmarkCase.beamWidth()));
        GoalSearchResult result = new BestFirstSearchStrategy()
            .searchWithDiagnostics(problem);
        return new ScalarRun(result, counting.calls, counting.generated);
    }

    private static Case benchmarkCase(
        String id,
        String source,
        String target,
        int maxVisitedStates,
        int maxCandidatesPerState
    ) {
        return new Case(
            id,
            "SYNTHETIC_CONTROL",
            source,
            target,
            Relation.EQUIVALENT,
            Role.SEARCH_POLICY_CONTROL,
            "FIRST_LOST_WITNESS_PREFIX",
            "TEST_FIXTURE",
            TargetRelation.SYNTAX_EXACT,
            2,
            20,
            2,
            maxVisitedStates,
            maxCandidatesPerState,
            2,
            2);
    }

    private static Corpus corpus(Case benchmarkCase) {
        return new Corpus(
            HistoricalRediscoveryCorpus.SCHEMA,
            "FROZEN_DIAGNOSTIC_CORPUS",
            "test-inventory/v1",
            "bounded test claim",
            CORPUS_HASH,
            List.of(benchmarkCase));
    }

    private static AtlasReport atlas(Corpus corpus, CaseResult result) {
        return new AtlasReport(
            HistoricalRediscoveryAtlas.SCHEMA,
            corpus.schema(),
            corpus.contentSha256(),
            corpus.inventoryRevision(),
            corpus.claimBoundary(),
            List.of(result),
            List.of(),
            assessment());
    }

    private static Assessment assessment() {
        return new Assessment(
            AssessmentDecision.INSUFFICIENT_SIGNAL,
            true,
            true,
            true,
            false,
            false,
            false,
            true,
            1,
            Map.of(PrimaryStatus.REACHABLE_BUT_PRODUCTION_SEARCH_MISSED, 1),
            List.of("test fixture"));
    }

    private static Transformation transformation(
        String rule,
        String target,
        int estimatedCostDelta
    ) {
        return new Transformation(
            rule,
            target,
            RewriteKind.NORMALIZE,
            estimatedCostDelta > 0,
            estimatedCostDelta,
            true,
            rule + ":root");
    }

    private record ScalarRun(
        GoalSearchResult result,
        int calls,
        long generated
    ) {
    }

    private static final class CountingEngine implements TransformationEngine {
        private final TransformationEngine delegate;
        private int calls;
        private long generated;

        private CountingEngine(TransformationEngine delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Transformation> transform(String expression) {
            calls++;
            List<Transformation> transformations = delegate.transform(expression);
            generated += transformations.size();
            return transformations;
        }
    }
}
