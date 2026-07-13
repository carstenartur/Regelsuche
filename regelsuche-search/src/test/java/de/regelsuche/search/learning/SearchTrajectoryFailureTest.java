package de.regelsuche.search.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.search.telemetry.SearchEventType;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchTrajectoryFailureTest {
    @Test
    void storesADeadEndDecisionAsFailedExperience() {
        TransformationEngine engine = expression -> expression.equals("x + 1")
            ? List.of(new Transformation(
                "wrong-offset", "x + 2", RewriteKind.NORMALIZE,
                false, 0, true, "wrong-offset:x+2"))
            : List.of();
        SearchTrajectoryCollector collector = new SearchTrajectoryCollector();
        SearchProblem problem = new SearchProblem(
            "x + 1",
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(3, 20, 1, 4, 10, 5))
            .withTarget(SearchTarget.syntaxExact("x"))
            .withObserver(collector);

        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        SearchTrajectoryRun run = collector.finish(
            problem,
            result,
            new SearchTrajectoryContext(
                "failed-run", "offset-failure", "test-producer-v1",
                List.of("wrong-offset"), DatasetSplit.TEST));

        assertFalse(result.reached());
        assertFalse(run.success());
        assertEquals(1, run.decisionCount());
        assertEquals(0, run.selectedDecisionCount());
        assertTrue(run.records().stream()
            .filter(SearchTrajectoryRecord::decision)
            .noneMatch(SearchTrajectoryRecord::eventualSuccess));

        InMemorySearchExperienceRepository repository = new InMemorySearchExperienceRepository();
        repository.store(run);
        assertEquals(1, repository.summary().total());
        assertEquals(0, repository.summary().successfulChoices());
        assertEquals(1, repository.summary().failedAlternatives());

        SearchTrajectoryDataset dataset = new SearchTrajectoryDataset(List.of(run));
        assertEquals(0, dataset.summary().successfulRuns());
        assertEquals(1, dataset.summary().failedRuns());
        assertTrue(dataset.toJsonLines().contains("\"eventualSuccess\":false"));
    }

    @Test
    void repeatedApplicationProducesOneDecisionWithItsPruningReason() {
        TransformationEngine engine = expression -> switch (expression) {
            case "x" -> List.of(new Transformation(
                "repeat", "x + 0", RewriteKind.NORMALIZE,
                false, 0, true, "repeat-key"));
            case "x + 0" -> List.of(new Transformation(
                "repeat", "x + 0 + 0", RewriteKind.NORMALIZE,
                false, 0, true, "repeat-key"));
            default -> List.of();
        };
        SearchTrajectoryCollector collector = new SearchTrajectoryCollector();
        SearchProblem problem = new SearchProblem(
            "x",
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(3, 20, 1, 4, 10, 5))
            .withTarget(SearchTarget.syntaxExact("unreachable"))
            .withObserver(collector);

        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        var decisions = collector.events().stream()
            .filter(event -> event.type() == SearchEventType.TRANSFORMATION_GENERATED)
            .filter(event -> event.ruleId().equals("repeat"))
            .toList();

        assertFalse(result.reached());
        assertEquals(2, result.states().size());
        assertEquals(2, decisions.size(),
            "accepted and guard-rejected transformations each need exactly one decision row");
        assertEquals(1, decisions.stream()
            .filter(event -> event.pruningReason().isBlank())
            .count());
        assertEquals(1, decisions.stream()
            .filter(event -> event.pruningReason().equals("repeated-rule-application"))
            .count());

        SearchTrajectoryRun run = collector.finish(
            problem,
            result,
            new SearchTrajectoryContext(
                "repeat-run", "repeat-failure", "test-producer-v1",
                List.of("repeat"), DatasetSplit.TEST));
        assertEquals(2, run.decisionCount());
        assertTrue(run.records().stream()
            .filter(SearchTrajectoryRecord::decision)
            .anyMatch(record -> record.pruningReason().equals("repeated-rule-application")));

        InMemorySearchExperienceRepository repository = new InMemorySearchExperienceRepository();
        repository.store(run);
        assertEquals(2, repository.summary().total());
    }
}
