package de.regelsuche.search.learning;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.json.JsonReader;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.scoring.ScoreRevision;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import de.regelsuche.search.policy.DescriptorPolicyModel;
import de.regelsuche.search.policy.DescriptorPolicyTrainer;
import de.regelsuche.search.policy.SearchPolicyModel;
import de.regelsuche.search.policy.SearchPolicyTrainer;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchStateReplay;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoringProvenanceTest {
    @Test void builtInProducerRevisionSurvivesEventsSplitJsonAndExperience() {
        var run = run(new ExpressionScorer()).withSplit(DatasetSplit.TRAIN);
        assertTrue(run.records().stream().allMatch(r -> ScoreRevision.CURRENT.equals(r.scoringRevision())));
        String json = new SearchTrajectoryDataset(List.of(run)).toJsonLines();
        assertTrue(json.lines().allMatch(line -> ScoreRevision.CURRENT.equals(new JsonReader(line).readObject().get("scoringRevision"))));
        var repository = new InMemorySearchExperienceRepository();
        repository.store(run);
        var decision = run.records().stream().filter(SearchTrajectoryRecord::decision).findFirst().orElseThrow();
        assertTrue(repository.findByShape("provenance", decision.parent().alphaShapeHash(), 10).stream()
            .allMatch(e -> ScoreRevision.CURRENT.equals(e.scoringRevision())));
        assertTrue(repository.summary().total() > 0);
    }
    @Test void bothTrainersAcceptFreshProducerBoundValues() {
        var dataset = new SearchTrajectoryDataset(List.of(run(new ExpressionScorer()).withSplit(DatasetSplit.TRAIN)));
        assertDoesNotThrow(() -> new SearchPolicyTrainer().train(dataset, SearchPolicyModel.Mode.values()[0], 1));
        assertDoesNotThrow(() -> new DescriptorPolicyTrainer().train(dataset, DescriptorPolicyModel.Mode.LINEAR, 1));
    }
    @Test void rawCustomScoresRemainUnknownAndCannotEnterEitherTrainer() {
        assertNotAdmitted(run(custom(ScoreRevision.UNSPECIFIED)), ScoreRevision.UNSPECIFIED);
    }
    @Test void explicitlyNamedCustomScoresArePreservedRatherThanRelabelled() {
        assertNotAdmitted(run(custom("custom/v1")), "custom/v1");
    }
    @Test void legacySchemasCannotBeAdmittedByAddingTheCurrentLabel() {
        var current = run(new ExpressionScorer()).withSplit(DatasetSplit.TRAIN);
        var legacy = new SearchTrajectoryRun(current.context(), current.root(), current.target(),
            current.taskValueFingerprint(), current.taskAlphaFingerprint(), current.terminalStatus(), current.success(),
            current.records().stream().map(r -> copy(r, "regelsuche.search-trajectory/v2", ScoreRevision.CURRENT)).toList());
        var dataset = new SearchTrajectoryDataset(List.of(legacy));
        assertThrows(IllegalArgumentException.class, () -> new SearchPolicyTrainer().train(dataset, SearchPolicyModel.Mode.values()[0], 1));
        assertThrows(IllegalArgumentException.class, () -> new DescriptorPolicyTrainer().train(dataset, DescriptorPolicyModel.Mode.LINEAR, 1));
        assertFalse(dataset.toJsonLines().contains("scoringRevision"), "legacy export must retain its old schema");
    }
    @Test void unknownRevisionIsNotLostWhenSplittingOrStoringABatch() {
        var current = run(new ExpressionScorer());
        var unknown = new SearchTrajectoryRun(current.context(), current.root(), current.target(),
            current.taskValueFingerprint(), current.taskAlphaFingerprint(), current.terminalStatus(), current.success(),
            current.records().stream().map(r -> copy(r, r.schema(), ScoreRevision.UNSPECIFIED)).toList());
        var split = unknown.withSplit(DatasetSplit.TRAIN);
        assertTrue(split.records().stream().allMatch(r -> ScoreRevision.UNSPECIFIED.equals(r.scoringRevision())));
        var repository = new InMemorySearchExperienceRepository();
        assertThrows(IllegalArgumentException.class, () -> repository.store(split));
        assertEquals(0, repository.summary().total());
    }
    @Test void replaySerializationUsesTheActualScorerContract() {
        var scorer = custom("custom/v1");
        var problem = problem(scorer, new SearchTrajectoryCollector());
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        var json = new JsonReader(SearchStateReplay.toCanonicalJson(result.reachedState())).readObject();
        assertEquals("custom/v1", json.get("scoringRevision"));
    }
    private void assertNotAdmitted(SearchTrajectoryRun run, String revision) {
        var split = run.withSplit(DatasetSplit.TRAIN);
        assertTrue(split.records().stream().allMatch(r -> revision.equals(r.scoringRevision())));
        var dataset = new SearchTrajectoryDataset(List.of(split));
        assertTrue(dataset.toJsonLines().contains(revision));
        assertThrows(IllegalArgumentException.class, () -> new SearchPolicyTrainer().train(dataset, SearchPolicyModel.Mode.values()[0], 1));
        assertThrows(IllegalArgumentException.class, () -> new DescriptorPolicyTrainer().train(dataset, DescriptorPolicyModel.Mode.LINEAR, 1));
    }
    private ExpressionScorer custom(String revision) {
        return new ExpressionScorer() {
            @Override public ExpressionScore score(String expression) {
                var value = super.score(expression);
                return new ExpressionScore(value.stringLength(), value.astNodeCount(), value.operatorCount(),
                    value.nestingDepth(), value.recognizedPatternBonus(), revision);
            }
        };
    }
    private SearchTrajectoryRun run(ExpressionScorer scorer) {
        var collector = new SearchTrajectoryCollector();
        var problem = problem(scorer, collector);
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        assertTrue(result.reached());
        return collector.finish(problem, result, new SearchTrajectoryContext("score-run", "provenance", "test-v1",
            List.of("remove-zero"), DatasetSplit.UNASSIGNED));
    }
    private SearchProblem problem(ExpressionScorer scorer, SearchTrajectoryCollector collector) {
        TransformationEngine engine = expression -> expression.equals("x + 0")
            ? List.of(new Transformation("remove-zero", "x", RewriteKind.NORMALIZE, false, 0, true, "remove-zero:x")) : List.of();
        return new SearchProblem("x + 0", engine, scorer, new ExpressionCanonicalizer(),
            new SearchHeuristic(4, 80, 1, 8, 40, 20))
            .withTarget(SearchProblem.SearchTarget.syntaxExact("x")).withObserver(collector);
    }
    private SearchTrajectoryRecord copy(SearchTrajectoryRecord r, String schema, String revision) {
        return new SearchTrajectoryRecord(schema, r.producerVersion(), r.runId(), r.family(), r.split(), r.ruleInventoryHash(),
            r.sequence(), r.eventType(), r.expression(), r.parent(), r.target(), r.features(), r.transformationDescriptor(),
            r.depth(), r.score(), r.parentScore(), r.frontierSize(), r.visitedCount(), r.generatedCount(), r.ruleId(),
            r.rewriteKind(), r.applicableRuleIds(), r.assumptions(), r.pruningReason(), r.eventualSuccess(),
            r.selectedPath(), r.terminalStatus(), revision);
    }
}
