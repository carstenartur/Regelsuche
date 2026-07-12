package de.regelsuche.search.learning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Stable metadata supplied by a trajectory producer for one search run. */
public record SearchTrajectoryContext(
    String runId,
    String family,
    String producerVersion,
    List<String> ruleInventoryIds,
    DatasetSplit split
) {
    public SearchTrajectoryContext {
        requireText(runId, "runId");
        requireText(family, "family");
        requireText(producerVersion, "producerVersion");
        ruleInventoryIds = ruleInventoryIds == null
            ? List.of()
            : ruleInventoryIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        split = split == null ? DatasetSplit.UNASSIGNED : split;
    }

    public SearchTrajectoryContext withSplit(DatasetSplit newSplit) {
        return new SearchTrajectoryContext(
            runId, family, producerVersion, ruleInventoryIds,
            Objects.requireNonNull(newSplit, "newSplit"));
    }

    public String ruleInventoryHash() {
        return "rules-v1:" + sha256(String.join("\n", ruleInventoryIds));
    }

    public enum DatasetSplit {
        UNASSIGNED,
        TRAIN,
        VALIDATION,
        TEST
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
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
}
