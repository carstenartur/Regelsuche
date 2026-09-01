package de.regelsuche.benchmark.polynomial;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Complete ordered result/measurement batch before canonical freezing. */
public record PolynomialTheoryUtilityCandidateMeasurementBatch(
    String batchId,
    PolynomialTheoryUtilityProfileAdapter.CandidateBatch candidateBatch,
    List<PolynomialTheoryUtilityCandidateMeasurements> measurements
) {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-candidate-measurement-batch/v1";
    public static final String EVIDENCE_STATUS =
        "TARGET_BLIND_MEASUREMENTS_BOUND_NOT_FROZEN";
    private static final Pattern SHA_256 =
        Pattern.compile("sha256:[0-9a-f]{64}");

    public PolynomialTheoryUtilityCandidateMeasurementBatch {
        batchId = requireHash(batchId, "batchId");
        candidateBatch = Objects.requireNonNull(
            candidateBatch,
            "candidateBatch"
        );
        measurements = List.copyOf(
            Objects.requireNonNull(measurements, "measurements")
        );
        requireBatch(candidateBatch, measurements);
        if (!batchId.equals(identity(candidateBatch, measurements))) {
            throw new IllegalArgumentException(
                "candidate measurement batch identity differs from its fields"
            );
        }
    }

    public static PolynomialTheoryUtilityCandidateMeasurementBatch create(
        PolynomialTheoryUtilityProfileAdapter.CandidateBatch candidateBatch,
        List<PolynomialTheoryUtilityCandidateMeasurements> measurements
    ) {
        var retainedBatch = Objects.requireNonNull(
            candidateBatch,
            "candidateBatch"
        );
        List<PolynomialTheoryUtilityCandidateMeasurements> retained =
            List.copyOf(Objects.requireNonNull(measurements, "measurements"));
        requireBatch(retainedBatch, retained);
        return new PolynomialTheoryUtilityCandidateMeasurementBatch(
            identity(retainedBatch, retained),
            retainedBatch,
            retained
        );
    }

    public String schema() {
        return SCHEMA;
    }

    public String studyId() {
        return PolynomialTheoryUtilityPreregistration.STUDY_ID;
    }

    public String evidenceStatus() {
        return EVIDENCE_STATUS;
    }

    public String inputContentHash() {
        return candidateBatch.inputContentHash();
    }

    public long inputByteLength() {
        return candidateBatch.inputByteLength();
    }

    public List<PolynomialTheoryUtilityCandidateResult> results() {
        return candidateBatch.results();
    }

    public int rowCount() {
        return measurements.size();
    }

    public void validateAgainst(
        PolynomialTheoryUtilityProfileAdapter.CandidateBatch expected
    ) {
        var value = Objects.requireNonNull(expected, "expected");
        if (!candidateBatch.inputContentHash().equals(
                value.inputContentHash())
                || candidateBatch.inputByteLength()
                    != value.inputByteLength()
                || !candidateBatch.results().equals(value.results())) {
            throw new IllegalArgumentException(
                "candidate measurements refer to another result batch"
            );
        }
    }

    private static void requireBatch(
        PolynomialTheoryUtilityProfileAdapter.CandidateBatch candidateBatch,
        List<PolynomialTheoryUtilityCandidateMeasurements> measurements
    ) {
        if (!PolynomialTheoryUtilityProfileAdapter.CandidateBatch.SCHEMA.equals(
                candidateBatch.schema())
                || measurements.size() != candidateBatch.results().size()
                || measurements.size()
                    != PolynomialTheoryUtilityExecutionInputs
                        .EXPECTED_INPUT_COUNT) {
            throw new IllegalArgumentException(
                "candidate measurement batch must cover every frozen result"
            );
        }
        Set<String> identities = new HashSet<>();
        for (int index = 0; index < measurements.size(); index++) {
            var measurement = Objects.requireNonNull(
                measurements.get(index),
                "measurement"
            );
            measurement.validateAgainst(
                candidateBatch.results().get(index)
            );
            if (!identities.add(measurement.measurementId())) {
                throw new IllegalArgumentException(
                    "candidate measurement identities are not unique"
                );
            }
        }
    }

    private static String identity(
        PolynomialTheoryUtilityProfileAdapter.CandidateBatch candidateBatch,
        List<PolynomialTheoryUtilityCandidateMeasurements> measurements
    ) {
        StringBuilder material = new StringBuilder();
        append(material, SCHEMA);
        append(material, PolynomialTheoryUtilityPreregistration.STUDY_ID);
        append(material, EVIDENCE_STATUS);
        append(
            material,
            Objects.requireNonNull(candidateBatch, "candidateBatch").schema()
        );
        append(material, candidateBatch.inputContentHash());
        append(material, Long.toString(candidateBatch.inputByteLength()));
        append(material, Integer.toString(measurements.size()));
        for (int index = 0; index < measurements.size(); index++) {
            append(
                material,
                candidateBatch.results().get(index).resultId()
            );
            append(
                material,
                Objects.requireNonNull(
                    measurements.get(index),
                    "measurement"
                ).measurementId()
            );
        }
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            material.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String requireHash(String value, String name) {
        String text = requireText(value, name);
        if (!SHA_256.matcher(text).matches()) {
            throw new IllegalArgumentException(name + " is not SHA-256");
        }
        return text;
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }
}
