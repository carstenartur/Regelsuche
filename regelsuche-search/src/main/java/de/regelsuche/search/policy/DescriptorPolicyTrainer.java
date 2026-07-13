package de.regelsuche.search.policy;

import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import de.regelsuche.search.learning.SearchTrajectoryDataset;
import de.regelsuche.search.learning.SearchTrajectoryRecord;
import de.regelsuche.search.learning.SearchTrajectoryRun;
import de.regelsuche.search.learning.TransformationDescriptor;
import de.regelsuche.search.policy.DescriptorPolicyModel.DescriptorStatistics;
import de.regelsuche.search.policy.DescriptorPolicyModel.FeatureStatistics;
import de.regelsuche.search.policy.DescriptorPolicyModel.Mode;
import de.regelsuche.search.telemetry.SearchEventType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
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
            ? featureStatistics(trainingRuns, examples)
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

    private static Map<String, FeatureStatistics> featureStatistics(
        List<SearchTrajectoryRun> trainingRuns,
        List<Example> examples
    ) {
        TreeSet<String> featureNames = new TreeSet<>();
        examples.forEach(example -> featureNames.addAll(example.features().keySet()));
        Map<String, FeatureStatistics> result = new LinkedHashMap<>();
        for (String featureName : featureNames) {
            MutableFeatureStatistics mutable = new MutableFeatureStatistics();
            for (Example example : examples) {
                mutable.record(
                    example.features().getOrDefault(featureName, 0),
                    example.successful());
            }
            result.put(featureName, mutable.freeze());
        }

        result.keySet().removeIf(DescriptorFeatureVector::pairwiseContextFeature);
        pairwiseContextStatistics(trainingRuns).forEach(result::put);
        return result;
    }

    /**
     * Context and context/role interactions are ranking evidence, so they are
     * learned only from candidates that actually competed in the same expansion.
     */
    private static Map<String, FeatureStatistics> pairwiseContextStatistics(
        List<SearchTrajectoryRun> trainingRuns
    ) {
        Map<String, MutableFeatureStatistics> mutable = new LinkedHashMap<>();
        for (SearchTrajectoryRun run : trainingRuns) {
            List<SearchTrajectoryRecord> group = new ArrayList<>();
            boolean collectingExpansion = false;
            for (SearchTrajectoryRecord record : run.records().stream()
                    .sorted(Comparator.comparingLong(SearchTrajectoryRecord::sequence))
                    .toList()) {
                if (record.eventType() == SearchEventType.STATE_EXPANDED) {
                    recordContextCompetition(group, mutable);
                    group.clear();
                    collectingExpansion = true;
                } else if (record.eventType() == SearchEventType.SEARCH_FINISHED) {
                    recordContextCompetition(group, mutable);
                    group.clear();
                    collectingExpansion = false;
                } else if (collectingExpansion && record.decision()
                        && record.transformationDescriptor().available()) {
                    group.add(record);
                }
            }
            recordContextCompetition(group, mutable);
        }

        Map<String, FeatureStatistics> result = new LinkedHashMap<>();
        mutable.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> result.put(entry.getKey(), entry.getValue().freeze()));
        return result;
    }

    private static void recordContextCompetition(
        List<SearchTrajectoryRecord> group,
        Map<String, MutableFeatureStatistics> mutable
    ) {
        List<SearchTrajectoryRecord> selected = group.stream()
            .filter(record -> record.selectedPath() && record.eventualSuccess())
            .toList();
        List<SearchTrajectoryRecord> alternatives = group.stream()
            .filter(record -> !record.selectedPath())
            .toList();
        if (selected.isEmpty() || alternatives.isEmpty()) {
            return;
        }

        for (SearchTrajectoryRecord winner : selected) {
            Map<String, Integer> winnerFeatures =
                DescriptorFeatureVector.of(winner.transformationDescriptor());
            for (SearchTrajectoryRecord alternative : alternatives) {
                Map<String, Integer> alternativeFeatures =
                    DescriptorFeatureVector.of(alternative.transformationDescriptor());
                TreeSet<String> featureNames = new TreeSet<>();
                featureNames.addAll(winnerFeatures.keySet());
                featureNames.addAll(alternativeFeatures.keySet());
                for (String featureName : featureNames) {
                    if (!DescriptorFeatureVector.pairwiseContextFeature(featureName)) {
                        continue;
                    }
                    int winnerValue = winnerFeatures.getOrDefault(featureName, 0);
                    int alternativeValue = alternativeFeatures.getOrDefault(featureName, 0);
                    if (winnerValue == alternativeValue) {
                        continue;
                    }
                    MutableFeatureStatistics statistics = mutable.computeIfAbsent(
                        featureName, ignored -> new MutableFeatureStatistics());
                    statistics.record(winnerValue, true);
                    statistics.record(alternativeValue, false);
                }
            }
        }
    }

    private static String predictiveMaterial(List<Example> examples) {
        List<String> rows = new ArrayList<>();
        for (Example example : examples) {
            StringBuilder row = new StringBuilder(TransformationDescriptor.SCHEMA);
            example.features().forEach((name, value) -> row
                .append('\n').append(name).append('=').append(value));
            row.append("\nlabel=").append(example.successful())
                .append("\nscoreDelta=").append(example.scoreDelta());
            rows.add(row.toString());
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
        Map<String, Integer> features,
        boolean successful,
        int scoreDelta
    ) {
        private static Example of(SearchTrajectoryRecord record) {
            TransformationDescriptor descriptor = record.transformationDescriptor();
            return new Example(
                descriptor,
                DescriptorFeatureVector.of(descriptor),
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
