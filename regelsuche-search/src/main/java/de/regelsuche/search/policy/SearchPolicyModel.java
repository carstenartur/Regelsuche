package de.regelsuche.search.policy;

import de.regelsuche.json.JsonWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable transparent model trained from a leakage-free trajectory dataset. */
public record SearchPolicyModel(
    String modelVersion,
    String datasetHash,
    String featureSchemaVersion,
    String ruleInventoryHash,
    Mode mode,
    int minimumObservations,
    Map<String, RuleStatistics> rules
) {
    public static final String FEATURE_SCHEMA = "regelsuche.search-policy-features/v1";
    private static final String MODEL_SCHEMA = "regelsuche.search-policy-model/v1";

    public SearchPolicyModel {
        if (modelVersion == null || modelVersion.isBlank()
                || datasetHash == null || datasetHash.isBlank()
                || featureSchemaVersion == null || featureSchemaVersion.isBlank()
                || ruleInventoryHash == null || ruleInventoryHash.isBlank()
                || mode == null) {
            throw new IllegalArgumentException("model metadata must not be blank");
        }
        if (minimumObservations < 1) {
            throw new IllegalArgumentException("minimumObservations must be positive");
        }
        Map<String, RuleStatistics> sorted = new LinkedHashMap<>();
        if (rules != null) {
            rules.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        }
        rules = Collections.unmodifiableMap(sorted);
    }

    public boolean compatible() {
        return FEATURE_SCHEMA.equals(featureSchemaVersion);
    }

    public String toJson() {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", MODEL_SCHEMA)
            .property("modelVersion", modelVersion)
            .property("datasetHash", datasetHash)
            .property("featureSchemaVersion", featureSchemaVersion)
            .property("ruleInventoryHash", ruleInventoryHash)
            .property("mode", mode.name())
            .property("minimumObservations", minimumObservations)
            .array("rules", array -> rules.forEach((ruleId, statistics) ->
                array.objectValue(object -> object
                    .property("ruleId", ruleId)
                    .property("observations", statistics.observations())
                    .property("successfulChoices", statistics.successfulChoices())
                    .property("failedAlternatives", statistics.failedAlternatives())
                    .property("successPermille", statistics.successPermille())
                    .property("meanScoreDelta", statistics.meanScoreDelta()))))
            .endObject();
        return json.toString();
    }

    /** Deterministic line format intended for loading without a permissive JSON mapper. */
    public String toPortableText() {
        StringBuilder text = new StringBuilder(MODEL_SCHEMA).append('\n')
            .append(encoded(modelVersion)).append('\n')
            .append(encoded(datasetHash)).append('\n')
            .append(encoded(featureSchemaVersion)).append('\n')
            .append(encoded(ruleInventoryHash)).append('\n')
            .append(mode.name()).append('\n')
            .append(minimumObservations).append('\n')
            .append(rules.size());
        rules.forEach((ruleId, statistics) -> text.append('\n')
            .append(encoded(ruleId)).append('\t')
            .append(statistics.observations()).append('\t')
            .append(statistics.successfulChoices()).append('\t')
            .append(statistics.failedAlternatives()).append('\t')
            .append(statistics.successPermille()).append('\t')
            .append(statistics.meanScoreDelta()));
        return text.toString();
    }

    public static SearchPolicyModel load(String text) {
        if (text == null) {
            throw new IllegalArgumentException("model text must not be null");
        }
        String[] lines = text.split("\\R", -1);
        if (lines.length < 8 || !MODEL_SCHEMA.equals(lines[0])) {
            throw new IllegalArgumentException("unsupported search policy model schema");
        }
        int ruleCount = integer(lines[7], "rule count");
        if (ruleCount < 0 || lines.length != 8 + ruleCount) {
            throw new IllegalArgumentException("search policy rule count does not match payload");
        }
        Map<String, RuleStatistics> rules = new LinkedHashMap<>();
        for (int index = 0; index < ruleCount; index++) {
            String[] fields = lines[8 + index].split("\\t", -1);
            if (fields.length != 6) {
                throw new IllegalArgumentException("invalid search policy rule row " + index);
            }
            String ruleId = decoded(fields[0]);
            if (rules.put(ruleId, new RuleStatistics(
                    integer(fields[1], "observations"),
                    integer(fields[2], "successfulChoices"),
                    integer(fields[3], "failedAlternatives"),
                    integer(fields[4], "successPermille"),
                    integer(fields[5], "meanScoreDelta"))) != null) {
                throw new IllegalArgumentException("duplicate search policy rule " + ruleId);
            }
        }
        return new SearchPolicyModel(
            decoded(lines[1]),
            decoded(lines[2]),
            decoded(lines[3]),
            decoded(lines[4]),
            Mode.valueOf(lines[5]),
            integer(lines[6], "minimumObservations"),
            rules);
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decoded(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid search policy model text", exception);
        }
    }

    private static int integer(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid " + name, exception);
        }
    }

    public enum Mode {
        FREQUENCY,
        LINEAR,
        LINEAR_WITH_EXPERIENCE
    }

    public record RuleStatistics(
        int observations,
        int successfulChoices,
        int failedAlternatives,
        int successPermille,
        int meanScoreDelta
    ) {
        public RuleStatistics {
            if (observations < 0 || successfulChoices < 0 || failedAlternatives < 0
                    || successfulChoices + failedAlternatives != observations
                    || successPermille < 0 || successPermille > 1000) {
                throw new IllegalArgumentException("invalid rule statistics");
            }
        }
    }
}
