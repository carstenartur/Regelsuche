package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolynomialTheoryUtilityCandidateFreezeTest {
    private static final Fixture FIXTURE = fixture();

    @Test
    void freezesAllMeasuredRowsIntoStableTargetBlindBytes() {
        var first = PolynomialTheoryUtilityCandidateFreeze.create(
            FIXTURE.measuredBatch()
        );
        var second = PolynomialTheoryUtilityCandidateFreeze.create(
            FIXTURE.measuredBatch()
        );

        assertEquals(
            "regelsuche.polynomial-theory-utility-candidate-freeze/v1",
            first.schema()
        );
        assertEquals(
            "CANDIDATES_FROZEN_QUALIFICATION_NOT_OPENED",
            first.evidenceStatus()
        );
        assertEquals("HASH_ONLY_NOT_OPENED", first.qualificationExposure());
        assertEquals(
            PolynomialTheoryUtilityPreregistration.STUDY_ID,
            first.studyId()
        );
        assertEquals(
            PolynomialTheoryUtilityExecutionInputs.EXPECTED_INPUT_COUNT,
            first.rowCount()
        );
        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.byteLength(), second.byteLength());
        assertEquals(first.canonicalJson(), second.canonicalJson());
        assertArrayEquals(first.bytes(), second.bytes());
        assertEquals(
            first.contentHash(),
            PolynomialTheoryUtilityExecutionIdentity.sha256(first.bytes())
        );
        assertEquals(first.byteLength(), first.bytes().length);
        first.validateAgainst(FIXTURE.measuredBatch());

        String json = first.canonicalJson();
        assertTrue(json.endsWith("\n"));
        assertFalse(json.contains("\r"));
        assertEquals(600, occurrences(json, "\"rowIndex\":"));
        assertTrue(json.contains("\"rowIndex\":0"));
        assertTrue(json.contains("\"rowIndex\":599"));
        assertTrue(json.contains(
            FIXTURE.measuredBatch().measurements()
                .getLast()
                .measurementId()
        ));
        assertTrue(json.contains(
            "\"transitionTraces\":[]"
        ));
        assertTrue(json.contains(
            "\"factorizationAttempts\":[]"
        ));
        assertTrue(json.contains("\"cacheEvents\":[]"));
    }

    @Test
    void bindsEveryFrozenInputArtifactAndOnlyHashesQualification() {
        String json = PolynomialTheoryUtilityCandidateFreeze.create(
            FIXTURE.measuredBatch()
        ).canonicalJson();

        assertBinding(
            json,
            "preregistrationBinding",
            PolynomialTheoryUtilityPreregistration.FILE_NAME,
            PolynomialTheoryUtilityPreregistration.BYTE_LENGTH,
            PolynomialTheoryUtilityPreregistration.CONTENT_HASH
        );
        assertBinding(
            json,
            "formationBinding",
            PolynomialTheoryUtilityCaseCorpus.FORMATION_FILE_NAME,
            PolynomialTheoryUtilityCaseCorpus.FORMATION_BYTE_LENGTH,
            PolynomialTheoryUtilityCaseCorpus.FORMATION_CONTENT_HASH
        );
        assertBinding(
            json,
            "qualificationBinding",
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME,
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_BYTE_LENGTH,
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_CONTENT_HASH
        );
        assertBinding(
            json,
            "planBinding",
            PolynomialTheoryUtilityExecutionPlan.FILE_NAME,
            PolynomialTheoryUtilityExecutionPlan.EXPECTED_BYTE_LENGTH,
            PolynomialTheoryUtilityExecutionPlan.EXPECTED_CONTENT_HASH
        );
        assertBinding(
            json,
            "executionInputBinding",
            PolynomialTheoryUtilityExecutionInputs.FILE_NAME,
            PolynomialTheoryUtilityExecutionInputs.EXPECTED_BYTE_LENGTH,
            PolynomialTheoryUtilityExecutionInputs.EXPECTED_CONTENT_HASH
        );

        for (String forbidden : List.of(
                "requiredOutcome",
                "reducibilityStatus",
                "multiplicityStatus",
                "referenceExpression",
                "expectedClassifierOutcome",
                "selectedDecision",
                "qualificationResult")) {
            assertFalse(json.contains("\"" + forbidden + "\":"));
        }
    }

    @Test
    void rejectsCounterfeitIdentityLengthBytesAndTampering() {
        var valid = PolynomialTheoryUtilityCandidateFreeze.create(
            FIXTURE.measuredBatch()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCandidateFreeze(
                hash("counterfeit-freeze"),
                valid.byteLength(),
                valid.measuredBatch(),
                valid.canonicalJson()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCandidateFreeze(
                valid.contentHash(),
                valid.byteLength() + 1L,
                valid.measuredBatch(),
                valid.canonicalJson()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCandidateFreeze(
                valid.contentHash(),
                valid.byteLength(),
                valid.measuredBatch(),
                valid.canonicalJson() + " "
            )
        );

        byte[] tampered = valid.bytes();
        tampered[tampered.length / 2] ^= 1;
        assertFalse(valid.verify(tampered));
        assertFalse(valid.verify(null));
        assertThrows(
            IllegalArgumentException.class,
            () -> valid.requireVerified(tampered)
        );

        byte[] exposed = valid.bytes();
        byte original = exposed[0];
        exposed[0] ^= 1;
        assertNotEquals(exposed[0], valid.bytes()[0]);
        assertEquals(original, valid.bytes()[0]);
    }

    @Test
    void writesAtomicallyAndRejectsAQualificationInTheOutputDirectory(
        @TempDir Path temporary
    ) throws IOException {
        var freeze = PolynomialTheoryUtilityCandidateFreeze.create(
            FIXTURE.measuredBatch()
        );
        Path target = freeze.write(temporary.resolve("candidate"));
        assertEquals(
            PolynomialTheoryUtilityCandidateFreeze.FILE_NAME,
            target.getFileName().toString()
        );
        assertArrayEquals(freeze.bytes(), Files.readAllBytes(target));

        Path blocked = temporary.resolve("blocked");
        Files.createDirectories(blocked);
        Files.writeString(
            blocked.resolve(
                PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME
            ),
            "{}",
            StandardCharsets.UTF_8
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> freeze.write(blocked)
        );
        assertFalse(Files.exists(
            blocked.resolve(
                PolynomialTheoryUtilityCandidateFreeze.FILE_NAME
            )
        ));
    }

    private static void assertBinding(
        String json,
        String field,
        String path,
        long byteLength,
        String contentHash
    ) {
        String binding = "\"" + field + "\":{"
            + "\"path\":\"" + path + "\","
            + "\"byteLength\":" + byteLength + ","
            + "\"contentHash\":\"" + contentHash + "\"}";
        assertTrue(json.contains(binding));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static Fixture fixture() {
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
                "CANDIDATE_FREEZE_TEST_" + index
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
                    )
                )
                .toList();
        var measuredBatch =
            PolynomialTheoryUtilityCandidateMeasurementBatch.create(
                candidateBatch,
                measurements
            );
        return new Fixture(measuredBatch);
    }

    private static String hash(String value) {
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            value.getBytes(StandardCharsets.UTF_8)
        );
    }

    private record Fixture(
        PolynomialTheoryUtilityCandidateMeasurementBatch measuredBatch
    ) {
    }
}
