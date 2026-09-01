package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolynomialTheoryUtilityCandidateFreezeBoundaryTest {
    private static final PolynomialTheoryUtilityCandidateMeasurementBatch
        BASELINE_BATCH = measuredBatch("BASELINE_", null);

    @Test
    void rejectsMalformedUnicodeBeforeCanonicalByteBinding() {
        String malformed = "BROKEN_"
            + Character.toString((char) 0xd800);
        var batch = measuredBatch("MALFORMED_", malformed);

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateFreeze.create(batch)
        );
    }

    @Test
    void rejectsAQualificationSymlinkInTheOutputDirectory(
        @TempDir Path temporary
    ) throws IOException {
        var freeze = PolynomialTheoryUtilityCandidateFreeze.create(
            BASELINE_BATCH
        );
        Path target = temporary.resolve("qualification-target.json");
        Files.writeString(target, "{}", StandardCharsets.UTF_8);
        Path blocked = temporary.resolve("blocked");
        Files.createDirectories(blocked);
        Path qualification = blocked.resolve(
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME
        );
        try {
            Files.createSymbolicLink(qualification, target.toAbsolutePath());
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception);
        }

        assertThrows(
            IllegalArgumentException.class,
            () -> freeze.write(blocked)
        );
        assertFalse(Files.exists(
            blocked.resolve(PolynomialTheoryUtilityCandidateFreeze.FILE_NAME)
        ));
    }

    private static PolynomialTheoryUtilityCandidateMeasurementBatch
            measuredBatch(String detailPrefix, String firstDetail) {
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze();
        var cases = PolynomialTheoryUtilityCaseCorpus.load().cases();
        List<PolynomialTheoryUtilityCandidateResult> results =
            new ArrayList<>(inputs.inputs().size());
        List<PolynomialTheoryUtilityCandidateMeasurements> measurements =
            new ArrayList<>(inputs.inputs().size());

        for (int index = 0; index < inputs.inputs().size(); index++) {
            var input = inputs.inputs().get(index);
            var studyCase = cases.get(index % cases.size());
            String detail = index == 0 && firstDetail != null
                ? firstDetail
                : detailPrefix + index;
            var result = PolynomialTheoryUtilityCandidateResult.noTransition(
                input,
                studyCase,
                detail
            );
            results.add(result);
            measurements.add(
                PolynomialTheoryUtilityCandidateMeasurements.create(
                    result,
                    List.of(),
                    List.of(),
                    List.of()
                )
            );
        }

        var candidateBatch =
            PolynomialTheoryUtilityProfileAdapter.CandidateBatch.create(
                inputs,
                results
            );
        return PolynomialTheoryUtilityCandidateMeasurementBatch.create(
            candidateBatch,
            measurements
        );
    }
}
