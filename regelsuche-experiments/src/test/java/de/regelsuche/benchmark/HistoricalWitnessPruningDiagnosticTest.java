package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.Assessment;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.AssessmentDecision;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.AtlasReport;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Case;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Relation;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Role;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.TargetRelation;
import de.regelsuche.benchmark.HistoricalWitnessPruningDiagnostic.CaseDiagnostic;
import de.regelsuche.benchmark.HistoricalWitnessPruningDiagnostic.LostStep;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalMetrics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchEventType;
import de.regelsuche.transform.RewriteKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoricalWitnessPruningDiagnosticTest {
    private static final String HASH = "0".repeat(64);

    @Test
    void candidateBudgetStopsBeforeTheWitnessEdge() {
        Case benchmarkCase = benchmarkCase(
            "candidate-budget", "x", "x + 1", 5);
        GoalSearchResult search = search(
            List.of(state("x")), metrics(1, 1));
        SearchEvent budget = event(
            1,
            SearchEventType.STATE_PRUNED_BUDGET,
            "x",
            "",
            "",
            "max-candidates-per-state");

        CaseDiagnostic result = new HistoricalWitnessPruningDiagnostic()
            .analyzeWitness(
                benchmarkCase,
                oracle("x + 1", "z-witness"),
                search,
                List.of(budget),
                1,
                2);

        assertEquals(
            HistoricalWitnessPruningDiagnostic.WITNESS_PREFIX_LOST,
            result.status());
        assertEquals(
            HistoricalWitnessPruningDiagnostic
                .CANDIDATE_BUDGET_BEFORE_WITNESS_EDGE,
            result.firstLoss().reason());
        assertEquals("CANDIDATE_BUDGET", result.searchTerminalStatus());
    }

    @Test
    void stateBudgetLeavesTheWitnessQueuedButUnexplored() {
        Case benchmarkCase = benchmarkCase(
            "state-budget", "x + 0", "x + y - y", 2);
        GoalSearchResult search = search(
            List.of(state("x + 0"), state("x")), metrics(2, 0));
        SearchEvent generated = event(
            1,
            SearchEventType.TRANSFORMATION_GENERATED,
            "x + y - y",
            "x + 0",
            "expand-root",
            "");
        SearchEvent enqueued = event(
            2,
            SearchEventType.STATE_ENQUEUED,
            "x + y - y",
            "x + 0",
            "expand-root",
            "");

        CaseDiagnostic result = new HistoricalWitnessPruningDiagnostic()
            .analyzeWitness(
                benchmarkCase,
                oracle("x + y - y", "expand-root"),
                search,
                List.of(generated, enqueued),
                1,
                2);

        assertEquals(
            HistoricalWitnessPruningDiagnostic
                .STATE_ENQUEUED_BUT_NOT_EXPLORED,
            result.firstLoss().reason());
        assertEquals("STATE_BUDGET", result.searchTerminalStatus());
    }

    @Test
    void reportIsContentAddressedAndByteStable(@TempDir Path directory)
            throws Exception {
        Case benchmarkCase = benchmarkCase("stable", "x", "x + 1", 5);
        SearchEvent event = event(
            1,
            SearchEventType.STATE_PRUNED_BUDGET,
            "x",
            "",
            "",
            "max-candidates-per-state");
        CaseDiagnostic diagnostic = new CaseDiagnostic(
            benchmarkCase.id(),
            HistoricalWitnessPruningDiagnostic.WITNESS_PREFIX_LOST,
            "REACHABLE",
            1,
            0,
            "CANDIDATE_BUDGET",
            1,
            1,
            2,
            new LostStep(
                0,
                "x",
                "x + 1",
                "witness",
                HistoricalWitnessPruningDiagnostic
                    .CANDIDATE_BUDGET_BEFORE_WITNESS_EDGE,
                event,
                "candidate budget"),
            "first loss");
        Corpus corpus = new Corpus(
            HistoricalRediscoveryCorpus.SCHEMA,
            "FROZEN_DIAGNOSTIC_CORPUS",
            "test-inventory/v1",
            "bounded test claim",
            HASH,
            List.of(benchmarkCase));
        AtlasReport atlas = new AtlasReport(
            HistoricalRediscoveryAtlas.SCHEMA,
            corpus.schema(),
            corpus.contentSha256(),
            corpus.inventoryRevision(),
            corpus.claimBoundary(),
            List.of(),
            List.of(),
            assessment());
        HistoricalWitnessPruningDiagnostic writer =
            new HistoricalWitnessPruningDiagnostic();
        List<CaseDiagnostic> cases = List.of(diagnostic);
        String firstHash = writer.contentHash(corpus, atlas, cases);
        String secondHash = writer.contentHash(corpus, atlas, cases);
        Path firstPath = writer.write(
            directory.resolve("first"), corpus, atlas, cases);
        Path secondPath = writer.write(
            directory.resolve("second"), corpus, atlas, cases);

        assertEquals(firstHash, secondHash);
        assertTrue(firstHash.matches("sha256:[0-9a-f]{64}"));
        assertArrayEquals(
            Files.readAllBytes(firstPath),
            Files.readAllBytes(secondPath));
    }

    private static HistoricalRediscoveryAtlas.OracleEvidence oracle(
        String expression,
        String rule
    ) {
        return new HistoricalRediscoveryAtlas.OracleEvidence(
            HistoricalRediscoveryAtlas.EvidenceExecution.EXECUTED,
            "REACHABLE",
            List.of(expression),
            List.of(rule),
            1,
            2,
            2,
            1,
            false,
            false,
            "");
    }

    private static GoalSearchResult search(
        List<SearchState> states,
        GoalMetrics metrics
    ) {
        return new GoalSearchResult(
            states, null, null, -1, GoalStatus.UNTARGETED, metrics);
    }

    private static GoalMetrics metrics(
        int exploredStates,
        int candidateBudgetPrunes
    ) {
        return new GoalMetrics(
            exploredStates,
            1,
            2,
            1,
            0,
            0,
            0,
            0,
            candidateBudgetPrunes,
            0,
            0,
            0,
            0,
            0);
    }

    private static SearchState state(String expression) {
        return new SearchState(
            expression,
            0,
            null,
            List.of(),
            List.of(),
            Set.of(),
            0,
            "hash-" + expression,
            "",
            "",
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            0);
    }

    private static SearchEvent event(
        long sequence,
        SearchEventType type,
        String expression,
        String parent,
        String rule,
        String reason
    ) {
        return new SearchEvent(
            sequence,
            type,
            expression,
            "hash-" + expression,
            0,
            0,
            "hash-" + parent,
            parent,
            rule,
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            List.of(),
            1,
            1,
            1,
            reason);
    }

    private static Case benchmarkCase(
        String id,
        String source,
        String target,
        int maxVisitedStates
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
            8,
            2,
            2);
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
            0,
            Map.of(),
            List.of("test fixture"));
    }
}
