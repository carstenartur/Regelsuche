package de.regelsuche.search.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.learning.SearchTrajectoryCollector;
import de.regelsuche.search.learning.SearchTrajectoryContext;
import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import de.regelsuche.search.learning.SearchTrajectoryDataset;
import de.regelsuche.search.learning.SearchTrajectoryRun;
import de.regelsuche.search.learning.TransformationDescriptor;
import de.regelsuche.search.policy.DescriptorPolicyModel.Mode;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class DescriptorPolicyTrainerTest {
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();

    @Test
    void trainsAndLoadsByteStableDescriptorModelFromTrajectoryV2() {
        SearchTrajectoryDataset dataset = datasetWithValidation(
            run(
                "train-neutral",
                "neutral-family",
                DatasetSplit.TRAIN,
                "p + 0",
                "p",
                "train-good",
                "train-bad"),
            run(
                "validation-double",
                "double-family",
                DatasetSplit.VALIDATION,
                "q + q",
                "2 * q",
                "validation-good",
                "validation-bad"));
        DescriptorPolicyTrainer trainer = new DescriptorPolicyTrainer();

        DescriptorPolicyModel first = trainer.train(dataset, Mode.LINEAR, 1);
        DescriptorPolicyModel second = trainer.train(dataset, Mode.LINEAR, 1);
        DescriptorPolicyModel loaded = DescriptorPolicyModel.load(first.toPortableText());

        assertEquals(first, second);
        assertEquals(first, loaded);
        assertEquals(first.toJson(), second.toJson());
        assertTrue(first.compatible());
        assertTrue(first.modelVersion().startsWith("descriptor-policy-v1:"));
        assertTrue(first.sourceDatasetHash().startsWith("sha256:"));
        assertTrue(first.predictiveDatasetHash().startsWith("sha256:"));
        assertEquals(TransformationDescriptor.SCHEMA, first.featureSchemaVersion());
        assertEquals(2, first.descriptors().values().stream()
            .mapToInt(DescriptorPolicyModel.DescriptorStatistics::observations)
            .sum());
        assertTrue(first.features().containsKey("root.transition.ADD_TO_VARIABLE"));
        assertTrue(first.features().containsKey("root.transition.ADD_TO_MUL"));
        assertTrue(dataset.toJsonLines().contains("\"schema\":\"regelsuche.search-trajectory/v2\""));
        assertTrue(dataset.toJsonLines().contains("\"transformationDescriptor\""));
        assertTrue(dataset.toJsonLines().contains(TransformationDescriptor.SCHEMA));
    }

    @Test
    void heldOutRowsDoNotAffectDescriptorModelIdentityOrWeights() {
        SearchTrajectoryRun training = run(
            "train-neutral",
            "neutral-family",
            DatasetSplit.TRAIN,
            "p + 0",
            "p",
            "train-good",
            "train-bad");
        SearchTrajectoryDataset firstDataset = datasetWithValidation(
            training,
            run(
                "validation-double",
                "double-family",
                DatasetSplit.VALIDATION,
                "q + q",
                "2 * q",
                "held-out-double",
                "held-out-double-bad"));
        SearchTrajectoryDataset secondDataset = datasetWithValidation(
            training,
            run(
                "validation-power",
                "power-family",
                DatasetSplit.VALIDATION,
                "r * r",
                "r ^ 2",
                "held-out-power",
                "held-out-power-bad"));
        DescriptorPolicyTrainer trainer = new DescriptorPolicyTrainer();

        assertNotEquals(firstDataset.toJsonLines(), secondDataset.toJsonLines());
        assertEquals(
            trainer.train(firstDataset, Mode.LINEAR, 1),
            trainer.train(secondDataset, Mode.LINEAR, 1));
    }

    @Test
    void trainingRuleIdsDoNotEnterPredictiveModelMaterial() {
        SearchTrajectoryDataset firstDataset = new SearchTrajectoryDataset(List.of(run(
            "train-a",
            "neutral-family",
            DatasetSplit.TRAIN,
            "p + 0",
            "p",
            "rule-a-good",
            "rule-a-bad")));
        SearchTrajectoryDataset secondDataset = new SearchTrajectoryDataset(List.of(run(
            "train-a",
            "neutral-family",
            DatasetSplit.TRAIN,
            "p + 0",
            "p",
            "completely-different-good-id",
            "completely-different-bad-id")));
        DescriptorPolicyTrainer trainer = new DescriptorPolicyTrainer();

        DescriptorPolicyModel first = trainer.train(firstDataset, Mode.LINEAR, 1);
        DescriptorPolicyModel second = trainer.train(secondDataset, Mode.LINEAR, 1);

        assertNotEquals(first.sourceDatasetHash(), second.sourceDatasetHash());
        assertEquals(first.predictiveDatasetHash(), second.predictiveDatasetHash());
        assertEquals(first.modelVersion(), second.modelVersion());
        assertEquals(first.descriptors(), second.descriptors());
        assertEquals(first.features(), second.features());
    }

    @Test
    void exactOrAlphaSplitLeakageStillBlocksTraining() {
        SearchTrajectoryRun training = run(
            "train-neutral",
            "neutral-family",
            DatasetSplit.TRAIN,
            "p + 0",
            "p",
            "train-good",
            "train-bad");
        SearchTrajectoryDataset leaking = new SearchTrajectoryDataset(List.of(
            training,
            training.withSplit(DatasetSplit.VALIDATION)));

        assertFalse(leaking.leakageFree());
        assertThrows(
            IllegalArgumentException.class,
            () -> new DescriptorPolicyTrainer().train(leaking, Mode.LINEAR, 1));
    }

    private SearchTrajectoryDataset datasetWithValidation(
        SearchTrajectoryRun training,
        SearchTrajectoryRun validation
    ) {
        SearchTrajectoryDataset dataset = new SearchTrajectoryDataset(List.of(training, validation));
        assertTrue(dataset.leakageFree(), dataset.leakageViolations().toString());
        return dataset;
    }

    private SearchTrajectoryRun run(
        String runId,
        String family,
        DatasetSplit split,
        String root,
        String target,
        String goodRule,
        String badRule
    ) {
        TransformationEngine engine = expression -> expression.equals(root)
            ? List.of(
                step(goodRule, target, -1),
                step(badRule, "(" + root + ") * 1", 1))
            : List.of();
        SearchTrajectoryCollector collector = new SearchTrajectoryCollector();
        SearchProblem problem = new SearchProblem(
            root,
            engine,
            scorer,
            canonicalizer,
            new SearchHeuristic(2, 20, 1, 2, 10, 10))
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
                "descriptor-policy-test/v1",
                List.of(goodRule, badRule),
                split));
    }

    private static Transformation step(String rule, String output, int costDelta) {
        return new Transformation(
            rule,
            output,
            RewriteKind.NORMALIZE,
            costDelta > 0,
            costDelta,
            true,
            rule + ":" + output,
            List.of("x != 0"));
    }
}
