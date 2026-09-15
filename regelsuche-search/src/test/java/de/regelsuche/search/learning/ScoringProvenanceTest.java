package de.regelsuche.search.learning;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.json.JsonReader;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import de.regelsuche.search.policy.*;
import de.regelsuche.search.strategy.*;
import de.regelsuche.transform.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScoringProvenanceTest {
    private static final String CURRENT = "regelsuche.expression-score/v2";
    private static final String UNSPECIFIED = "unspecified";
    @TempDir Path directory;

    @Test void producerRevisionSurvivesTelemetrySplitAndExport() {
        var run = run(new ExpressionScorer());
        for (var split : DatasetSplit.values()) {
            var dataset = new SearchTrajectoryDataset(List.of(run.withSplit(split)));
            dataset.toJsonLines().lines().forEach(line -> {
                var json = new JsonReader(line).readObject();
                assertEquals("regelsuche.search-trajectory/v3", json.get("schema"));
                assertEquals(CURRENT, json.get("scoringRevision"));
            });
        }
        var dataset = new SearchTrajectoryDataset(List.of(run.withSplit(DatasetSplit.TRAIN)));
        assertTrue(new SearchPolicyTrainer().train(dataset, SearchPolicyModel.Mode.FREQUENCY, 1).compatible());
        assertTrue(new DescriptorPolicyTrainer().train(dataset, DescriptorPolicyModel.Mode.FREQUENCY, 1).compatible());
    }

    @ParameterizedTest
    @ValueSource(strings = {"regelsuche.search-trajectory/v1", "regelsuche.search-trajectory/v2"})
    void oldNumericRowsAreNotRelabelledByExportOrAcceptedByEitherTrainer(String legacySchema) {
        var fresh = run(new ExpressionScorer()).withSplit(DatasetSplit.TRAIN);
        var old = new SearchTrajectoryRun(fresh.context(), fresh.root(), fresh.target(), fresh.taskValueFingerprint(),
            fresh.taskAlphaFingerprint(), fresh.terminalStatus(), fresh.success(),
            fresh.records().stream().map(record -> legacyCopy(record, legacySchema)).toList());
        var dataset = new SearchTrajectoryDataset(List.of(old.withSplit(DatasetSplit.TRAIN)));
        dataset.toJsonLines().lines().forEach(line -> {
            var json = new JsonReader(line).readObject();
            assertEquals(legacySchema, json.get("schema"));
            assertFalse(json.containsKey("scoringRevision"));
            if (legacySchema.endsWith("/v1")) assertFalse(json.containsKey("transformationDescriptor"));
            assertNotEquals(CURRENT, json.get("scoringRevision"));
        });
        assertScoringRejection(() -> new SearchPolicyTrainer().train(dataset, SearchPolicyModel.Mode.FREQUENCY, 1));
        assertScoringRejection(() -> new DescriptorPolicyTrainer().train(dataset, DescriptorPolicyModel.Mode.FREQUENCY, 1));
        var repository = new InMemorySearchExperienceRepository();
        assertScoringRejection(() -> repository.store(old));
        assertEquals(0, repository.size());
    }

    @Test void customScoresWithoutADeclaredContractAreNotCalledBuiltInScores() {
        var custom = new ExpressionScorer() {
            @Override public ExpressionScore score(String expression) {
                return new ExpressionScore(expression.length(), 0, 0, 0, 0);
            }
        };
        var dataset = new SearchTrajectoryDataset(List.of(run(custom).withSplit(DatasetSplit.TRAIN)));
        dataset.toJsonLines().lines().forEach(line -> assertEquals(UNSPECIFIED,
            new JsonReader(line).readObject().get("scoringRevision")));
        assertScoringRejection(() -> new SearchPolicyTrainer().train(dataset, SearchPolicyModel.Mode.LINEAR, 1));
        assertScoringRejection(() -> new DescriptorPolicyTrainer().train(dataset, DescriptorPolicyModel.Mode.LINEAR, 1));
    }

    @Test void workReplayRejectsMissingOrDifferentScoreRevisionBeforeRunningSources() throws Exception {
        var calls = new AtomicInteger();
        var problem = workProblem(calls);
        var result = new WorkBudgetBestFirstSearchStrategy().search(problem);
        var json = result.toCanonicalJson();
        assertEquals(CURRENT, new JsonReader(json).readObject().get("scoringRevision"));
        assertEquals(result, WorkSearchReplay.verify(json, problem));
        for (String old : List.of(
                json.replace("\"scoringRevision\":\"" + CURRENT + "\",", ""),
                json.replace(CURRENT, "obsolete-score/v1"),
                json.replace(WorkSearchReplay.SCHEMA, "regelsuche.work-search-replay/v1"))) {
            calls.set(0);
            assertScoringRejection(() -> WorkSearchReplay.verify(old, problem));
            assertEquals(0, calls.get(), "admit score contract before executing a source");
            Path file = directory.resolve("replay.json");
            Files.writeString(file, old);
            var loaded = new AtomicInteger();
            assertScoringRejection(() -> WorkSearchReplay.verifyArtifact(file, SearchReplayArtifact.describe(old), () -> {
                loaded.incrementAndGet(); return problem;
            }));
            assertEquals(0, loaded.get(), "admit before reconstruction of trusted sources");
        }
    }

    @Test void commonStateReplayRetainsRevisionAndRejectsLegacyBeforeReplaySupplier() throws Exception {
        var problem = problem(new ExpressionScorer(), new SearchTrajectoryCollector());
        var state = new BestFirstSearchStrategy().search(problem).getFirst();
        String json = SearchStateReplay.toCanonicalJson(state);
        assertEquals(CURRENT, new JsonReader(json).readObject().get("scoringRevision"));
        Path file = directory.resolve("state.json");
        Files.writeString(file, json);
        assertEquals(state, SearchStateReplay.verifyArtifact(file, SearchReplayArtifact.describe(json), () -> state));
        String old = json.replace("\"scoringRevision\":\"" + CURRENT + "\",", "");
        Files.writeString(file, old);
        var calls = new AtomicInteger();
        assertScoringRejection(() -> SearchStateReplay.verifyArtifact(file, SearchReplayArtifact.describe(old), () -> {
            calls.incrementAndGet(); return state;
        }));
        assertEquals(0, calls.get());
    }

    @Test void oldPolicyFeatureVersionsCannotBeUsedAsCurrentScoreModels() {
        assertFalse(new SearchPolicyModel("old", "hash", "regelsuche.search-policy-features/v1", "rules",
            SearchPolicyModel.Mode.FREQUENCY, 1, Map.of()).compatible());
        assertFalse(new DescriptorPolicyModel("old", "hash", "predictive", TransformationDescriptor.SCHEMA,
            DescriptorPolicyModel.Mode.FREQUENCY, 1, Map.of(), Map.of()).compatible());
    }

    private static SearchTrajectoryRun run(ExpressionScorer scorer) {
        var collector = new SearchTrajectoryCollector();
        var problem = problem(scorer, collector);
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        assertTrue(result.reached());
        return collector.finish(problem, result, new SearchTrajectoryContext(
            "scoring-test", "neutral", "test", List.of("remove-zero"), DatasetSplit.UNASSIGNED));
    }

    private static SearchProblem problem(ExpressionScorer scorer, SearchTrajectoryCollector collector) {
        return new SearchProblem("x + 0", ScoringProvenanceTest::steps, scorer, new ExpressionCanonicalizer(),
            new SearchHeuristic(2, 8, 1, 2, 4, 2))
            .withTarget(SearchProblem.SearchTarget.syntaxExact("x")).withObserver(collector);
    }

    private static WorkBudgetBestFirstSearchStrategy.Problem workProblem(AtomicInteger calls) {
        return new WorkBudgetBestFirstSearchStrategy.Problem("x + 0", "x",
            new SearchExpansionSource.Measured(MeasuredTransformationEngines.counting(expression -> {
                calls.incrementAndGet(); return steps(expression);
            })), new ExpressionScorer(), new ExpressionCanonicalizer(),
            WorkBudgetBestFirstSearchStrategy.Budget.primitive(2, 8, 4, 2, 1000));
    }

    private static List<Transformation> steps(String expression) {
        return expression.equals("x + 0")
            ? List.of(new Transformation("remove-zero", "x", RewriteKind.SIMPLIFY, false, -2, true,
                "remove-zero-at-root", List.of())) : List.of();
    }

    /** The old constructor deliberately cannot infer a score contract from numeric values. */
    private static SearchTrajectoryRecord legacyCopy(SearchTrajectoryRecord r, String legacySchema) {
        return new SearchTrajectoryRecord(legacySchema, r.producerVersion(), r.runId(), r.family(),
            r.split(), r.ruleInventoryHash(), r.sequence(), r.eventType(), r.expression(), r.parent(), r.target(),
            r.features(), r.transformationDescriptor(), r.depth(), r.score(), r.parentScore(), r.frontierSize(),
            r.visitedCount(), r.generatedCount(), r.ruleId(), r.rewriteKind(), r.applicableRuleIds(), r.assumptions(),
            r.pruningReason(), r.eventualSuccess(), r.selectedPath(), r.terminalStatus());
    }

    private static void assertScoringRejection(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(IllegalArgumentException.class, action);
        assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("scor"), failure.getMessage());
    }
}
