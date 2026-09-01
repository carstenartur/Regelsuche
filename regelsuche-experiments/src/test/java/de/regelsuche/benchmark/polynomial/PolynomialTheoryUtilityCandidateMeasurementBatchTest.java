package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityCandidateMeasurementBatchTest {
    private static final Fixture BASELINE_FIXTURE =
        fixture("BASELINE_RESULT");
    private static final Fixture CHANGED_FIXTURE =
        fixture("CHANGED_RESULT");

    @Test
    void bindsExactlyOneMeasurementToEveryFrozenResult() {
        var candidateBatch = BASELINE_FIXTURE.candidateBatch();
        var measurements = BASELINE_FIXTURE.measurements();

        var batch = PolynomialTheoryUtilityCandidateMeasurementBatch.create(
            candidateBatch,
            measurements
        );

        assertEquals(
            "regelsuche.polynomial-theory-utility-"
                + "candidate-measurement-batch/v1",
            batch.schema()
        );
        assertEquals(
            "TARGET_BLIND_MEASUREMENTS_BOUND_NOT_FROZEN",
            batch.evidenceStatus()
        );
        assertEquals(
            PolynomialTheoryUtilityPreregistration.STUDY_ID,
            batch.studyId()
        );
        assertEquals(
            PolynomialTheoryUtilityExecutionInputs.EXPECTED_INPUT_COUNT,
            batch.rowCount()
        );
        assertEquals(candidateBatch.inputContentHash(), batch.inputContentHash());
        assertEquals(candidateBatch.inputByteLength(), batch.inputByteLength());
        assertEquals(candidateBatch.results(), batch.results());
        assertEquals(measurements, batch.measurements());
        assertTrue(batch.batchId().startsWith("sha256:"));
        batch.validateAgainst(candidateBatch);

        assertThrows(
            UnsupportedOperationException.class,
            () -> batch.measurements().clear()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> batch.results().clear()
        );
    }

    @Test
    void rejectsMissingAdditionalReorderedOrRepeatedMeasurements() {
        var candidateBatch = BASELINE_FIXTURE.candidateBatch();
        var measurements = BASELINE_FIXTURE.measurements();

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateMeasurementBatch.create(
                candidateBatch,
                measurements.subList(0, measurements.size() - 1)
            )
        );

        var additional = new ArrayList<>(measurements);
        additional.add(measurements.getFirst());
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateMeasurementBatch.create(
                candidateBatch,
                additional
            )
        );

        var reordered = new ArrayList<>(measurements);
        var first = reordered.get(0);
        reordered.set(0, reordered.get(1));
        reordered.set(1, first);
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateMeasurementBatch.create(
                candidateBatch,
                reordered
            )
        );

        var repeated = new ArrayList<>(measurements);
        repeated.set(1, repeated.get(0));
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateMeasurementBatch.create(
                candidateBatch,
                repeated
            )
        );
    }

    @Test
    void rejectsCounterfeitIdentityAndForeignBatchBinding() {
        var candidateBatch = BASELINE_FIXTURE.candidateBatch();
        var measurements = BASELINE_FIXTURE.measurements();
        var valid = PolynomialTheoryUtilityCandidateMeasurementBatch.create(
            candidateBatch,
            measurements
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCandidateMeasurementBatch(
                hash("counterfeit-batch"),
                candidateBatch,
                measurements
            )
        );

        var foreign = CHANGED_FIXTURE.candidateBatch();
        assertThrows(
            IllegalArgumentException.class,
            () -> valid.validateAgainst(foreign)
        );
    }

    @Test
    void changesIdentityWhenOnePositionEqualResultAndMeasurementChange() {
        var originalResults = BASELINE_FIXTURE.candidateBatch();
        var original = PolynomialTheoryUtilityCandidateMeasurementBatch.create(
            originalResults,
            BASELINE_FIXTURE.measurements()
        );

        var changedResults = CHANGED_FIXTURE.candidateBatch();
        var changed = PolynomialTheoryUtilityCandidateMeasurementBatch.create(
            changedResults,
            CHANGED_FIXTURE.measurements()
        );

        assertNotEquals(original.batchId(), changed.batchId());
        assertNotEquals(
            original.measurements().getFirst().measurementId(),
            changed.measurements().getFirst().measurementId()
        );
    }

    private static Fixture fixture(String detailPrefix) {
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze();
        var cases = PolynomialTheoryUtilityCaseCorpus.load().cases();
        List<PolynomialTheoryUtilityCandidateResult> results =
            new ArrayList<>(inputs.inputs().size());
        for (int index = 0; index < inputs.inputs().size(); index++) {
            var input = inputs.inputs().get(index);
            var studyCase = cases.get(index % cases.size());
            results.add(PolynomialTheoryUtilityCandidateResult.noTransition(
                input,
                studyCase,
                detailPrefix + "_" + index
            ));
        }
        var candidateBatch =
            PolynomialTheoryUtilityProfileAdapter.CandidateBatch.create(
                inputs,
                results
            );
        List<PolynomialTheoryUtilityCandidateMeasurements> measurements =
            candidateBatch.results().stream()
                .map(result ->
                    PolynomialTheoryUtilityCandidateMeasurements.create(
                        result,
                        List.of(),
                        List.of(),
                        List.of()
                    ))
                .toList();
        return new Fixture(candidateBatch, measurements);
    }

    private record Fixture(
        PolynomialTheoryUtilityProfileAdapter.CandidateBatch candidateBatch,
        List<PolynomialTheoryUtilityCandidateMeasurements> measurements
    ) {
        private Fixture {
            measurements = List.copyOf(measurements);
        }
    }

    private static String hash(String value) {
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            value.getBytes(StandardCharsets.UTF_8)
        );
    }
}
