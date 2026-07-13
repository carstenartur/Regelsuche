package de.regelsuche.search.policy;

import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import de.regelsuche.search.learning.SearchTrajectoryDataset;
import de.regelsuche.search.learning.SearchTrajectoryRecord;
import de.regelsuche.search.learning.SearchTrajectoryRun;
import de.regelsuche.search.policy.SearchPolicyModel.Mode;
import de.regelsuche.search.policy.SearchPolicyModel.RuleStatistics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Deterministically trains a transparent empirical rule-ranking model. */
public final class SearchPolicyTrainer {
    public SearchPolicyModel train(
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

        List<SearchTrajectoryRun> trainingRuns = dataset.runs().stream()
            .filter(run -> run.context().split() == DatasetSplit.TRAIN)
            .toList();
        Map<String, MutableRuleStatistics> mutable = new LinkedHashMap<>();
        TreeSet<String> inventoryHashes = new TreeSet<>();
        trainingRuns.forEach(run -> run.records().stream()
            .filter(SearchTrajectoryRecord::decision)
            .filter(record -> !record.ruleId().isBlank())
            .forEach(record -> {
                inventoryHashes.add(record.ruleInventoryHash());
                mutable.computeIfAbsent(
                    record.ruleId(), ignored -> new MutableRuleStatistics())
                    .record(record.selectedPath() && record.eventualSuccess(),
                        record.score() - record.parentScore());
            }));
        if (mutable.isEmpty()) {
            throw new IllegalArgumentException("training split contains no search decisions");
        }

        Map<String, RuleStatistics> rules = new LinkedHashMap<>();
        mutable.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> rules.put(entry.getKey(), entry.getValue().freeze()));

        SearchTrajectoryDataset trainingDataset = new SearchTrajectoryDataset(trainingRuns);
        String datasetHash = "sha256:" + sha256(trainingDataset.toJsonLines());
        String inventoryHash = "sha256:" + sha256(String.join("\n", inventoryHashes));
        String modelMaterial = datasetHash + "\n" + SearchPolicyModel.FEATURE_SCHEMA
            + "\n" + inventoryHash + "\n" + mode + "\n" + minimumObservations
            + "\n" + rules;
        String modelVersion = "policy-v1:" + sha256(modelMaterial).substring(0, 24);
        return new SearchPolicyModel(
            modelVersion,
            datasetHash,
            SearchPolicyModel.FEATURE_SCHEMA,
            inventoryHash,
            mode,
            minimumObservations,
            rules);
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

    private static final class MutableRuleStatistics {
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

        private RuleStatistics freeze() {
            int failed = observations - successful;
            int successPermille = observations == 0 ? 0 : successful * 1000 / observations;
            int meanScoreDelta = observations == 0
                ? 0
                : (int) Math.round((double) scoreDeltaSum / observations);
            return new RuleStatistics(
                observations, successful, failed, successPermille, meanScoreDelta);
        }
    }
}
