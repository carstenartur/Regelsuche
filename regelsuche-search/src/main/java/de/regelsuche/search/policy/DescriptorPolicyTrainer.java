package de.regelsuche.search.policy;

import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import de.regelsuche.search.learning.SearchTrajectoryDataset;
import de.regelsuche.search.learning.SearchTrajectoryRecord;
import de.regelsuche.search.learning.SearchTrajectoryRun;
import de.regelsuche.search.learning.TransformationDescriptor;
import de.regelsuche.search.policy.DescriptorPolicyModel.DescriptorStatistics;
import de.regelsuche.search.policy.DescriptorPolicyModel.FeatureStatistics;
import de.regelsuche.search.policy.DescriptorPolicyModel.Mode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Deterministically trains rule-ID-independent descriptor statistics from TRAIN only. */
public final class DescriptorPolicyTrainer {
    public DescriptorPolicyModel train(
        SearchTrajectoryDataset dataset,
        Mode mode,
        int minimumObservations
    ) {
        validate(dataset, mode, minimumObservations);
        List<SearchTrajectoryRun> trainingRuns = dataset.runs().stream()
            .filter(run -> run.context().split() == DatasetSplit.TRAIN)
            .toList();
        List<Example> examples = trainingRuns.stream()
            .flatMap(run -> run.records().stream())
            .filter(SearchTrajectoryRecord::decision)
            .filter(record -> record.transformationDescriptor().available())
            .map(Example::of)
            .toList();
        if (examples.isEmpty()) {
            throw new IllegalArgumentException(
                "training split contains no available transformation descriptors");
        }

        Map<String, DescriptorStatistics> descriptors = descriptorStatistics(examples);
        Map<String, FeatureStatistics> features = mode == Mode.LINEAR
            ? featureStatistics(examples)
            : Map.of();
        SearchTrajectoryDataset trainingDataset = new SearchTrajectoryDataset(trainingRuns);
        String sourceHash = "sha256:" + sha256(trainingDataset.toJsonLines());
        String predictiveHash = "sha256:" + sha256(predictiveMaterial(examples));
        String modelMaterial = predictiveHash + '\n' + DescriptorPolicyModel.FEATURE_SCHEMA
            + '\n' + mode + '\n' + minimumObservations
            + '\n' + descriptors + '\n' + features;
        String modelVersion = "descriptor-policy-v1:"
            + sha256(modelMaterial).substring(0, 24);
        return new DescriptorPolicyModel(
            modelVersion,
            sourceHash,
            predictiveHash,
            DescriptorPolicyModel.FEATURE_SCHEMA,
            mode,
            minimumObservations,
            descriptors,
            features);
    }

    private static void validate(
        SearchTrajectoryDataset dataset,
        Mode mode,
        int minimumObservations
    ) {
        if (dataset == null || mode == null) {
            throw new IllegalArgumentException("dataset and mode must not be null");
        }
        if (!dataset.leakageFree()) {
            throw new IllegalArgumentException("cannot train on a dataset with split leakage");
        }
        if (minimumObservations < 1) {
            throw new IllegalArgumentException("minimumObservations must be positive");
        }
    }

    private static Map<String, DescriptorStatistics> descriptorStatistics(
        List<Example> examples
    ) {
        Map<String, MutableDescriptorStatistics> mutable = new LinkedHashMap<>();
        for (Example example : examples) {
            mutable.computeIfAbsent(
                example.descriptor().predictiveFingerprint(),
                ignored -> new MutableDescriptorStatistics())
                .record(example.successful(), example.scoreDelta());
        }
        Map<String, DescriptorStatistics> result = new LinkedHashMap<>();
        mutable.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> result.put(entry.getKey(), entry.getValue().freeze()));
        return result;
    }

    private static Map<String, FeatureStatistics> featureStatistics(List<Example> examples) {
        TreeSet<String> featureNames = new TreeSet<>();
        examples.forEach(example -> featureNames.addAll(example.descriptor().featureVector().keySet()));
        Map<String, FeatureStatistics> result = new LinkedHashMap<>();
        for (String featureName : featureNames) {
            MutableFeatureStatistics mutable = new MutableFeatureStatistics();
            for (Example example : examples) {
                mutable.record(
                    example.descriptor().featureVector().getOrDefault(featureName, 0),
                    example.successful());
            }
            result.put(featureName, mutable.freeze());
        }
        return result;
    }

    private static String predictiveMaterial(List<Example> examples) {
        List<String> rows = new ArrayList<>();
        for (Example example : examples) {
            rows.add(example.descriptor().predictiveMaterial()
                + "\nlabel=" + example.successful()
                + "\nscoreDelta=" + example.scoreDelta());
        }
        rows.sort(String::compareTo);
        return String.join("\n---\n", rows);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Example(
        TransformationDescriptor descriptor,
        boolean successful,
        int scoreDelta
    ) {
        private static Example of(SearchTrajectoryRecord record) {
            return new Example(
                record.transformationDescriptor(),
                record.selectedPath() && record.eventualSuccess(),
                record.score() - record.parentScore());
        }
    }

    private static final class MutableDescriptorStatistics {
        private int observations;
        private int successful;
        private long scoreDeltaSum;

        private void record(boolean successfulChoice, int scoreDelta) {
            observations++;
            if (successfulChoice) {
                successful++;
            }
            scoreDeltaSum += scoreDelta;
        }

        private DescriptorStatistics freeze() {
            int failed = observations - successful;
            int successPermille = successful * 1000 / observations;
            int meanScoreDelta = roundedMean(scoreDeltaSum, observations);
            return new DescriptorStatistics(
                observations, successful, failed, successPermille, meanScoreDelta);
        }
    }

    private static final class MutableFeatureStatistics {
        private int observations;
        private int successful;
        private long successfulValueSum;
        private long failedValueSum;
        private int minimumValue = Integer.MAX_VALUE;
        private int maximumValue = Integer.MIN_VALUE;

        private void record(int value, boolean successfulChoice) {
            observations++;
            minimumValue = Math.min(minimumValue, value);
            maximumValue = Math.max(maximumValue, value);
            if (successfulChoice) {
                successful++;
                successfulValueSum += value;
            } else {
                failedValueSum += value;
            }
        }

        private FeatureStatistics freeze() {
            int failed = observations - successful;
            int meanSuccessful = successful == 0
                ? 0
                : roundedMean(successfulValueSum, successful);
            int meanFailed = failed == 0
                ? 0
                : roundedMean(failedValueSum, failed);
            int coefficient = successful == 0 || failed == 0
                ? 0
                : normalizedCoefficient(
                    meanSuccessful, meanFailed, minimumValue, maximumValue);
            return new FeatureStatistics(
                observations,
                successful,
                failed,
                meanSuccessful,
                meanFailed,
                minimumValue,
                maximumValue,
                coefficient);
        }
    }

    private static int normalizedCoefficient(
        int meanSuccessful,
        int meanFailed,
        int minimum,
        int maximum
    ) {
        long span = Math.max(1L, (long) maximum - minimum);
        long value = ((long) meanFailed - meanSuccessful) * 1000L / span;
        return (int) Math.max(-1000L, Math.min(1000L, value));
    }

    private static int roundedMean(long sum, int count) {
        return (int) Math.round((double) sum / count);
    }
}
