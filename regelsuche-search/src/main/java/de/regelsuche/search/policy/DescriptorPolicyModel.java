package de.regelsuche.search.policy;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.search.learning.TransformationDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable transparent model trained from rule-ID-independent transformation descriptors.
 * This contract is behavior-neutral until a descriptor policy explicitly consumes it.
 */
public record DescriptorPolicyModel(
    String modelVersion,
    String sourceDatasetHash,
    String predictiveDatasetHash,
    String featureSchemaVersion,
    Mode mode,
    int minimumObservations,
    Map<String, DescriptorStatistics> descriptors,
    Map<String, FeatureStatistics> features
) {
    public static final String FEATURE_SCHEMA = TransformationDescriptor.SCHEMA;
    private static final String MODEL_SCHEMA = "regelsuche.transformation-descriptor-model/v1";

    public DescriptorPolicyModel {
        requireText(modelVersion, "modelVersion");
        requireText(sourceDatasetHash, "sourceDatasetHash");
        requireText(predictiveDatasetHash, "predictiveDatasetHash");
        requireText(featureSchemaVersion, "featureSchemaVersion");
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        if (minimumObservations < 1) {
            throw new IllegalArgumentException("minimumObservations must be positive");
        }
        descriptors = immutableSorted(descriptors);
        features = immutableSorted(features);
    }

    public boolean compatible() {
        return FEATURE_SCHEMA.equals(featureSchemaVersion);
    }

    public String toJson() {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", MODEL_SCHEMA)
            .property("modelVersion", modelVersion)
            .property("sourceDatasetHash", sourceDatasetHash)
            .property("predictiveDatasetHash", predictiveDatasetHash)
            .property("featureSchemaVersion", featureSchemaVersion)
            .property("mode", mode.name())
            .property("minimumObservations", minimumObservations)
            .array("descriptors", array -> descriptors.forEach((fingerprint, statistics) ->
                array.objectValue(object -> object
                    .property("fingerprint", fingerprint)
                    .property("observations", statistics.observations())
                    .property("successfulChoices", statistics.successfulChoices())
                    .property("failedAlternatives", statistics.failedAlternatives())
                    .property("successPermille", statistics.successPermille())
                    .property("meanScoreDelta", statistics.meanScoreDelta()))))
            .array("features", array -> features.forEach((name, statistics) ->
                array.objectValue(object -> object
                    .property("name", name)
                    .property("observations", statistics.observations())
                    .property("successfulChoices", statistics.successfulChoices())
                    .property("failedAlternatives", statistics.failedAlternatives())
                    .property("meanSuccessfulValue", statistics.meanSuccessfulValue())
                    .property("meanFailedValue", statistics.meanFailedValue())
                    .property("minimumValue", statistics.minimumValue())
                    .property("maximumValue", statistics.maximumValue())
                    .property("coefficientPermille", statistics.coefficientPermille()))))
            .endObject();
        return json.toString();
    }

    /** Deterministic strict format for portable model loading. */
    public String toPortableText() {
        StringBuilder text = new StringBuilder(MODEL_SCHEMA).append('\n')
            .append(encoded(modelVersion)).append('\n')
            .append(encoded(sourceDatasetHash)).append('\n')
            .append(encoded(predictiveDatasetHash)).append('\n')
            .append(encoded(featureSchemaVersion)).append('\n')
            .append(mode.name()).append('\n')
            .append(minimumObservations).append('\n')
            .append(descriptors.size()).append('\n')
            .append(features.size());
        descriptors.forEach((fingerprint, statistics) -> text.append('\n')
            .append('D').append('\t')
            .append(encoded(fingerprint)).append('\t')
            .append(statistics.observations()).append('\t')
            .append(statistics.successfulChoices()).append('\t')
            .append(statistics.failedAlternatives()).append('\t')
            .append(statistics.successPermille()).append('\t')
            .append(statistics.meanScoreDelta()));
        features.forEach((name, statistics) -> text.append('\n')
            .append('F').append('\t')
            .append(encoded(name)).append('\t')
            .append(statistics.observations()).append('\t')
            .append(statistics.successfulChoices()).append('\t')
            .append(statistics.failedAlternatives()).append('\t')
            .append(statistics.meanSuccessfulValue()).append('\t')
            .append(statistics.meanFailedValue()).append('\t')
            .append(statistics.minimumValue()).append('\t')
            .append(statistics.maximumValue()).append('\t')
            .append(statistics.coefficientPermille()));
        return text.toString();
    }

    public static DescriptorPolicyModel load(String text) {
        if (text == null) {
            throw new IllegalArgumentException("model text must not be null");
        }
        String[] lines = text.split("\\R", -1);
        if (lines.length < 9 || !MODEL_SCHEMA.equals(lines[0])) {
            throw new IllegalArgumentException("unsupported descriptor policy model schema");
        }
        int descriptorCount = integer(lines[7], "descriptor count");
        int featureCount = integer(lines[8], "feature count");
        if (descriptorCount < 0 || featureCount < 0
                || lines.length != 9 + descriptorCount + featureCount) {
            throw new IllegalArgumentException("descriptor model row count does not match payload");
        }
        Map<String, DescriptorStatistics> descriptors = new LinkedHashMap<>();
        for (int index = 0; index < descriptorCount; index++) {
            String[] fields = lines[9 + index].split("\\t", -1);
            if (fields.length != 7 || !"D".equals(fields[0])) {
                throw new IllegalArgumentException("invalid descriptor row " + index);
            }
            String fingerprint = decoded(fields[1]);
            DescriptorStatistics statistics = new DescriptorStatistics(
                integer(fields[2], "observations"),
                integer(fields[3], "successful choices"),
                integer(fields[4], "failed alternatives"),
                integer(fields[5], "success permille"),
                integer(fields[6], "mean score delta"));
            if (descriptors.put(fingerprint, statistics) != null) {
                throw new IllegalArgumentException("duplicate descriptor " + fingerprint);
            }
        }
        Map<String, FeatureStatistics> features = new LinkedHashMap<>();
        int featureOffset = 9 + descriptorCount;
        for (int index = 0; index < featureCount; index++) {
            String[] fields = lines[featureOffset + index].split("\\t", -1);
            if (fields.length != 10 || !"F".equals(fields[0])) {
                throw new IllegalArgumentException("invalid feature row " + index);
            }
            String name = decoded(fields[1]);
            FeatureStatistics statistics = new FeatureStatistics(
                integer(fields[2], "observations"),
                integer(fields[3], "successful choices"),
                integer(fields[4], "failed alternatives"),
                integer(fields[5], "mean successful value"),
                integer(fields[6], "mean failed value"),
                integer(fields[7], "minimum value"),
                integer(fields[8], "maximum value"),
                integer(fields[9], "coefficient permille"));
            if (features.put(name, statistics) != null) {
                throw new IllegalArgumentException("duplicate feature " + name);
            }
        }
        return new DescriptorPolicyModel(
            decoded(lines[1]),
            decoded(lines[2]),
            decoded(lines[3]),
            decoded(lines[4]),
            Mode.valueOf(lines[5]),
            integer(lines[6], "minimum observations"),
            descriptors,
            features);
    }

    public enum Mode {
        FREQUENCY,
        LINEAR
    }

    public record DescriptorStatistics(
        int observations,
        int successfulChoices,
        int failedAlternatives,
        int successPermille,
        int meanScoreDelta
    ) {
        public DescriptorStatistics {
            validateCounts(observations, successfulChoices, failedAlternatives);
            if (successPermille < 0 || successPermille > 1000) {
                throw new IllegalArgumentException("successPermille must be in 0..1000");
            }
        }
    }

    public record FeatureStatistics(
        int observations,
        int successfulChoices,
        int failedAlternatives,
        int meanSuccessfulValue,
        int meanFailedValue,
        int minimumValue,
        int maximumValue,
        int coefficientPermille
    ) {
        public FeatureStatistics {
            validateCounts(observations, successfulChoices, failedAlternatives);
            if (minimumValue > maximumValue) {
                throw new IllegalArgumentException("feature minimum must not exceed maximum");
            }
            if (coefficientPermille < -1000 || coefficientPermille > 1000) {
                throw new IllegalArgumentException("coefficientPermille must be in -1000..1000");
            }
        }
    }

    private static void validateCounts(int observations, int successful, int failed) {
        if (observations < 0 || successful < 0 || failed < 0
                || successful + failed != observations) {
            throw new IllegalArgumentException("invalid observation counts");
        }
    }

    private static <T> Map<String, T> immutableSorted(Map<String, T> values) {
        Map<String, T> sorted = new LinkedHashMap<>();
        if (values != null) {
            values.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        }
        return Collections.unmodifiableMap(sorted);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decoded(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid descriptor policy model text", exception);
        }
    }

    private static int integer(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid " + name, exception);
        }
    }
}
