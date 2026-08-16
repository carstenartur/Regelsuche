package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.PrimaryStatus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Relation;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Role;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.TargetRelation;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.SearchProfile;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalMetrics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.strategy.StructuralDiversitySearchStrategy;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchEventType;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class HistoricalRediscoveryAtlasTest {
    private static final String TEST_SHA256 = "0".repeat(64);

    @Test
    void targetBlindStructuralCellsRetainAStarvedExpansion() {
        String root = "x + 0";
        String target = "x + y - y";
        TransformationEngine engine = expression -> switch (expression) {
            case "x + 0" -> List.of(
                transformation("simplify-root", "x",
                    RewriteKind.SIMPLIFY, false, -2),
                transformation("expand-root", target,
                    RewriteKind.EXPAND, true, 5));
            case "x" -> List.of(
                transformation("reintroduce-neutral", "x * 1",
                    RewriteKind.NORMALIZE, false, 0));
            default -> List.of();
        };
        SearchProblem problem = new SearchProblem(
            root,
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(2, 3, 1, 2, 8, 2));

        List<SearchState> scalar = new BestFirstSearchStrategy().search(problem);
        List<SearchState> diverse =
            new StructuralDiversitySearchStrategy().search(problem);
        assertFalse(containsExpression(scalar, target), scalar.toString());
        assertTrue(containsExpression(diverse, target), diverse.toString());
        assertEquals(diverse,
            new StructuralDiversitySearchStrategy().search(problem));
        assertInstanceOf(StructuralDiversitySearchStrategy.class,
            SearchProfile.DIVERSITY_DISCOVERY.newStrategy());
        assertNull(problem.target(), "the diversity control must remain target-blind");
    }

    @Test
    void frozenHistoricalCorpusFailsClosed() {
        Corpus corpus = HistoricalRediscoveryCorpus.load();
        assertEquals(HistoricalRediscoveryCorpus.SCHEMA, corpus.schema());
        assertEquals("FROZEN_DIAGNOSTIC_CORPUS", corpus.evidenceStatus());
        assertEquals(14, corpus.cases().size());
        assertEquals(corpus.cases().size(),
            new HashSet<>(corpus.cases().stream()
                .map(HistoricalRediscoveryCorpus.Case::id)
                .toList()).size());
        assertTrue(corpus.cases().stream().anyMatch(value ->
            value.id().equals("sophie-germain")));
        assertTrue(corpus.cases().stream().anyMatch(value ->
            value.role() == Role.SEARCH_POLICY_CONTROL));
        assertTrue(corpus.cases().stream().anyMatch(value ->
            value.role() == Role.NEGATIVE_CONTROL
                && value.relation() == Relation.NOT_EQUIVALENT));

        String valid = minimalCorpusCase(
            "NOT_EQUIVALENT", "NEGATIVE_CONTROL", "1");
        HistoricalRediscoveryCorpus.parse(valid, TEST_SHA256);
        assertThrows(IllegalArgumentException.class,
            () -> HistoricalRediscoveryCorpus.parse(
                valid.replace("\"claimBoundary\":\"bounded\",", ""),
                TEST_SHA256));
        assertThrows(IllegalArgumentException.class,
            () -> HistoricalRediscoveryCorpus.parse(
                valid.replace("\"family\":\"TEST\",",
                    "\"family\":\"TEST\",\"unknown\":true,"),
                TEST_SHA256));
        assertThrows(IllegalArgumentException.class,
            () -> HistoricalRediscoveryCorpus.parse(
                minimalCorpusCase("EQUIVALENT", "NEGATIVE_CONTROL", "1"),
                TEST_SHA256));
        assertThrows(IllegalArgumentException.class,
            () -> HistoricalRediscoveryCorpus.parse(
                minimalCorpusCase("NOT_EQUIVALENT", "NEGATIVE_CONTROL", "1.5"),
                TEST_SHA256));
    }

    @Test
    @Timeout(240)
    void atlasRetainsControlsAndWritesFalsifiableEvidence(@TempDir Path directory)
            throws Exception {
        HistoricalRediscoveryAtlas atlas = new HistoricalRediscoveryAtlas();
        HistoricalRediscoveryAtlas.AtlasReport report = atlas.run(subset());
        Map<String, HistoricalRediscoveryAtlas.CaseResult> cases = byId(report);

        assertTrue(cases.get("difference-of-squares-powers")
            .production().oracle().reachable());
        assertTrue(cases.get("complete-square")
            .curatedControl().oracle().reachable());
        assertTrue(cases.get("sophie-germain")
            .genericBridge().guided().reached());
        assertTrue(cases.get("distribution-fitness-valley-control")
            .production().oracle().reachable());
        assertEquals(PrimaryStatus.NEGATIVE_CONTROL_CONFIRMED,
            cases.get("inconsistent-near-miss").status());
        assertFalse(report.assessment().statusCounts().containsKey(
            PrimaryStatus.CORRECTNESS_REGRESSION));
        assertEquals(report.cases().size(),
            report.assessment().statusCounts().values().stream()
                .mapToInt(Integer::intValue)
                .sum());
        assertNotNull(report.assessment().decision());
        assertEquals(report.toJson(), report.toJson());
        assertTrue(report.toJson().startsWith(
            "{\"schema\":\"regelsuche.historical-rediscovery-atlas/v1\""));
        assertTrue(report.toMarkdown().contains(
            "Historical rediscovery and reachability atlas"));

        HistoricalRediscoveryAtlas.WrittenArtifacts artifacts =
            atlas.write(directory, report);
        assertEquals(report.toJson(),
            Files.readString(artifacts.json(), StandardCharsets.UTF_8));
        assertEquals(report.toMarkdown(),
            Files.readString(artifacts.markdown(), StandardCharsets.UTF_8));
    }

    @Test
    @Timeout(240)
    void witnessPruningRetainsFirstLossAndStableArtifact(
            @TempDir Path directory) throws Exception {
        Corpus corpus = singleCase("distribution-fitness-valley-control");
        HistoricalRediscoveryAtlas.AtlasReport atlas =
            new HistoricalRediscoveryAtlas().run(corpus);
        HistoricalWitnessPruningDiagnostic diagnostic =
            new HistoricalWitnessPruningDiagnostic();
        List<HistoricalWitnessPruningDiagnostic.CaseDiagnostic> cases =
            diagnostic.run(corpus, atlas);

        assertEquals(1, cases.size());
        HistoricalWitnessPruningDiagnostic.CaseDiagnostic result = cases.get(0);
        assertEquals(
            HistoricalWitnessPruningDiagnostic.WITNESS_PREFIX_LOST,
            result.status());
        assertNotNull(result.firstLossIndex());
        assertFalse(result.firstLossReason().isBlank());
        assertTrue(result.exploredPrefixLength() < result.witnessStepCount());

        Path first = diagnostic.write(
            directory.resolve("first"), corpus, atlas, cases);
        Path second = diagnostic.write(
            directory.resolve("second"), corpus, atlas, cases);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
        assertTrue(diagnostic.contentHash(corpus, atlas, cases)
            .matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void witnessPruningClassifiesCandidateBudgetBeforeWitnessEdge() {
        HistoricalRediscoveryCorpus.Case benchmarkCase = diagnosticCase(
            "candidate-budget", "x", "x + 1", 5);
        GoalSearchResult search = diagnosticSearch(
            List.of(diagnosticState("x")),
            diagnosticMetrics(1, 1));
        SearchEvent budget = diagnosticEvent(
            1,
            SearchEventType.STATE_PRUNED_BUDGET,
            "x",
            "",
            "",
            "max-candidates-per-state");

        HistoricalWitnessPruningDiagnostic.CaseDiagnostic result =
            new HistoricalWitnessPruningDiagnostic().analyzeWitness(
                benchmarkCase,
                diagnosticOracle("x + 1", "z-witness"),
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
            result.firstLossReason());
        assertEquals("CANDIDATE_BUDGET", result.searchTerminalStatus());
    }

    @Test
    void witnessPruningClassifiesQueuedButUnexploredWitnessState() {
        HistoricalRediscoveryCorpus.Case benchmarkCase = diagnosticCase(
            "state-budget", "x + 0", "x + y - y", 2);
        GoalSearchResult search = diagnosticSearch(
            List.of(diagnosticState("x + 0"), diagnosticState("x")),
            diagnosticMetrics(2, 0));
        SearchEvent generated = diagnosticEvent(
            1,
            SearchEventType.TRANSFORMATION_GENERATED,
            "x + y - y",
            "x + 0",
            "expand-root",
            "");
        SearchEvent enqueued = diagnosticEvent(
            2,
            SearchEventType.STATE_ENQUEUED,
            "x + y - y",
            "x + 0",
            "expand-root",
            "");

        HistoricalWitnessPruningDiagnostic.CaseDiagnostic result =
            new HistoricalWitnessPruningDiagnostic().analyzeWitness(
                benchmarkCase,
                diagnosticOracle("x + y - y", "expand-root"),
                search,
                List.of(generated, enqueued),
                1,
                2);

        assertEquals(
            HistoricalWitnessPruningDiagnostic
                .STATE_ENQUEUED_BUT_NOT_EXPLORED,
            result.firstLossReason());
        assertEquals("STATE_BUDGET", result.searchTerminalStatus());
    }

    @Test
    void witnessPruningRecognizesACompletelyExploredWitness() {
        HistoricalRediscoveryCorpus.Case benchmarkCase = diagnosticCase(
            "complete-witness", "x", "x + 1", 5);
        GoalSearchResult search = diagnosticSearch(
            List.of(diagnosticState("x"), diagnosticState("x + 1")),
            diagnosticMetrics(2, 0));

        HistoricalWitnessPruningDiagnostic.CaseDiagnostic result =
            new HistoricalWitnessPruningDiagnostic().analyzeWitness(
                benchmarkCase,
                diagnosticOracle("x + 1", "witness"),
                search,
                List.of(),
                1,
                1);

        assertEquals(
            HistoricalWitnessPruningDiagnostic.WITNESS_COMPLETELY_EXPLORED,
            result.status());
        assertEquals(1, result.exploredPrefixLength());
        assertNull(result.firstLossIndex());
        assertEquals("FRONTIER_EXHAUSTED", result.searchTerminalStatus());
    }

    @Test
    void experimentRunnerRejectsUnknownCommands() {
        assertThrows(
            IllegalArgumentException.class,
            () -> DiscoveryExperimentRunner.main(new String[0]));
        assertThrows(
            IllegalArgumentException.class,
            () -> DiscoveryExperimentRunner.main(new String[] {"unknown"}));
    }

    @Test
    @Timeout(240)
    void equivalenceDiscriminationRequiresANegativeControl() {
        Corpus full = HistoricalRediscoveryCorpus.load();
        HistoricalRediscoveryCorpus.Case positive =
            full.cases().stream()
                .filter(value -> value.id().equals(
                    "difference-of-squares-powers"))
                .findFirst()
                .orElseThrow();
        Corpus positiveOnly = new Corpus(
            full.schema(),
            full.evidenceStatus(),
            full.inventoryRevision(),
            full.claimBoundary(),
            full.contentSha256(),
            List.of(positive));

        HistoricalRediscoveryAtlas.AtlasReport report =
            new HistoricalRediscoveryAtlas().run(positiveOnly);

        assertTrue(report.cases().get(0).equivalence().equivalent());
        assertFalse(
            report.assessment().equivalenceLayerDiscriminates());
    }

    @Test
    @Timeout(240)
    void curatedHitDoesNotBecomeCapabilityGapBeforeProductionClosureCompletes() {
        Corpus full = HistoricalRediscoveryCorpus.load();
        HistoricalRediscoveryCorpus.Case benchmarkCase = full.cases().stream()
            .filter(value -> value.id().equals("regrouped-square"))
            .findFirst()
            .orElseThrow();
        Corpus subset = new Corpus(
            full.schema(),
            full.evidenceStatus(),
            full.inventoryRevision(),
            full.claimBoundary(),
            full.contentSha256(),
            List.of(benchmarkCase));

        HistoricalRediscoveryAtlas.CaseResult result =
            new HistoricalRediscoveryAtlas().run(subset).cases().get(0);

        assertTrue(result.production().oracle().inconclusive());
        assertTrue(result.curatedControl().oracle().reachable());
        assertEquals(PrimaryStatus.BUDGET_INCONCLUSIVE, result.status());
    }

    private static HistoricalRediscoveryAtlas.OracleEvidence diagnosticOracle(
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

    private static GoalSearchResult diagnosticSearch(
        List<SearchState> states,
        GoalMetrics metrics
    ) {
        return new GoalSearchResult(
            states,
            null,
            null,
            -1,
            GoalStatus.UNTARGETED,
            metrics);
    }

    private static GoalMetrics diagnosticMetrics(
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

    private static SearchState diagnosticState(String expression) {
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

    private static SearchEvent diagnosticEvent(
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

    private static HistoricalRediscoveryCorpus.Case diagnosticCase(
        String id,
        String source,
        String target,
        int maxVisitedStates
    ) {
        return new HistoricalRediscoveryCorpus.Case(
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

    private static Transformation transformation(
        String rule,
        String target,
        RewriteKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta
    ) {
        return new Transformation(
            rule,
            target,
            kind,
            mayIncreaseComplexity,
            estimatedCostDelta,
            true,
            rule + ":root");
    }

    private static boolean containsExpression(
        List<SearchState> states,
        String expression
    ) {
        return states.stream().anyMatch(state ->
            state.expression().equals(expression));
    }

    private static String minimalCorpusCase(
        String relation,
        String role,
        String maxDepth
    ) {
        return """
            {
              "schema":"regelsuche.historical-rediscovery-corpus/v1",
              "evidenceStatus":"FROZEN_DIAGNOSTIC_CORPUS",
              "inventoryRevision":"test/v1",
              "claimBoundary":"bounded",
              "cases":[{
                "id":"case",
                "family":"TEST",
                "source":"x",
                "target":"y",
                "relation":"%s",
                "role":"%s",
                "diagnosticPurpose":"CONTROL",
                "provenance":"TEST_FIXTURE",
                "targetRelation":"SYNTAX_EXACT",
                "oracleMaxDepth":%s,
                "oracleMaxVisitedStates":2,
                "searchMaxDepth":1,
                "searchMaxVisitedStates":2,
                "maxCandidatesPerState":1,
                "maxExpandingSteps":1,
                "beamWidth":1
              }]
            }
            """.formatted(relation, role, maxDepth);
    }

    private static Corpus subset() {
        Corpus full = HistoricalRediscoveryCorpus.load();
        Set<String> selected = Set.of(
            "complete-square",
            "expand-binomial-square",
            "difference-of-squares",
            "reverse-difference-of-squares",
            "difference-of-squares-powers",
            "sophie-germain",
            "distribution-fitness-valley-control",
            "inconsistent-near-miss");
        List<HistoricalRediscoveryCorpus.Case> cases = full.cases().stream()
            .filter(value -> selected.contains(value.id()))
            .toList();
        assertEquals(selected.size(), cases.size());
        return new Corpus(
            full.schema(),
            full.evidenceStatus(),
            full.inventoryRevision(),
            full.claimBoundary(),
            full.contentSha256(),
            cases);
    }

    private static Corpus singleCase(String id) {
        Corpus full = HistoricalRediscoveryCorpus.load();
        HistoricalRediscoveryCorpus.Case selected = full.cases().stream()
            .filter(value -> value.id().equals(id))
            .findFirst()
            .orElseThrow();
        return new Corpus(
            full.schema(),
            full.evidenceStatus(),
            full.inventoryRevision(),
            full.claimBoundary(),
            full.contentSha256(),
            List.of(selected));
    }

    private static Map<String, HistoricalRediscoveryAtlas.CaseResult> byId(
        HistoricalRediscoveryAtlas.AtlasReport report
    ) {
        return report.cases().stream().collect(Collectors.toMap(
            result -> result.benchmarkCase().id(),
            Function.identity()));
    }
}
