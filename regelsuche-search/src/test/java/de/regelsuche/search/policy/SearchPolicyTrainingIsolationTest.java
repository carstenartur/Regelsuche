package de.regelsuche.search.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.learning.SearchTrajectoryCollector;
import de.regelsuche.search.learning.SearchTrajectoryContext;
import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import de.regelsuche.search.learning.SearchTrajectoryDataset;
import de.regelsuche.search.learning.SearchTrajectoryRun;
import de.regelsuche.search.policy.SearchPolicyModel.Mode;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchPolicyTrainingIsolationTest {
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();

    @Test
    void heldOutRowsDoNotInfluenceWeightsOrModelIdentity() {
        SearchTrajectoryRun train = run(
            "train-a", "train-family", DatasetSplit.TRAIN,
            "p + 0", "p", "train-simplify");
        SearchTrajectoryRun heldOutA = run(
            "test-a", "test-family-a", DatasetSplit.TEST,
            "q * q", "q^2", "test-power");
        SearchTrajectoryRun heldOutB = run(
            "test-b", "test-family-b", DatasetSplit.TEST,
            "r + r", "2*r", "test-double");

        SearchPolicyTrainer trainer = new SearchPolicyTrainer();
        SearchPolicyModel first = trainer.train(
            new SearchTrajectoryDataset(List.of(train, heldOutA)), Mode.LINEAR, 1);
        SearchPolicyModel second = trainer.train(
            new SearchTrajectoryDataset(List.of(train, heldOutB)), Mode.LINEAR, 1);

        assertEquals(first, second,
            "test/validation trajectories must not influence a trained model or its identity");
        assertEquals(first.datasetHash(), second.datasetHash());
        assertEquals(first.modelVersion(), second.modelVersion());
    }

    @Test
    void changingTrainingRowsChangesTheModelIdentity() {
        SearchTrajectoryRun firstTrain = run(
            "train-a", "train-family", DatasetSplit.TRAIN,
            "p + 0", "p", "train-simplify");
        SearchTrajectoryRun secondTrain = run(
            "train-b", "train-family", DatasetSplit.TRAIN,
            "p * 1", "p", "train-multiply-one");

        SearchPolicyTrainer trainer = new SearchPolicyTrainer();
        SearchPolicyModel first = trainer.train(
            new SearchTrajectoryDataset(List.of(firstTrain)), Mode.LINEAR, 1);
        SearchPolicyModel second = trainer.train(
            new SearchTrajectoryDataset(List.of(secondTrain)), Mode.LINEAR, 1);

        assertNotEquals(first.datasetHash(), second.datasetHash());
        assertNotEquals(first.modelVersion(), second.modelVersion());
        assertNotEquals(first.rules(), second.rules());
    }

    private SearchTrajectoryRun run(
        String runId,
        String family,
        DatasetSplit split,
        String root,
        String target,
        String ruleId
    ) {
        TransformationEngine engine = expression -> expression.equals(root)
            ? List.of(new Transformation(
                ruleId,
                target,
                RewriteKind.NORMALIZE,
                false,
                0,
                true,
                ruleId + ":" + target))
            : List.of();
        SearchTrajectoryCollector collector = new SearchTrajectoryCollector();
        SearchProblem problem = new SearchProblem(
            root,
            engine,
            scorer,
            canonicalizer,
            new SearchHeuristic(2, 10, 1, 2, 4, 4))
            .withTarget(SearchTarget.syntaxExact(target))
            .withObserver(collector);
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        assertTrue(result.reached(), result.toString());
        return collector.finish(
            problem,
            result,
            new SearchTrajectoryContext(
                runId,
                family,
                "training-isolation-test/v1",
                List.of(ruleId),
                split));
    }
}
