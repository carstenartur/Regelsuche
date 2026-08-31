package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityCandidateFreeze.Artifact;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityCandidateFreeze.Row;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.CandidateBatch;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.CandidateResult;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.NoFactorizationAdapter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityCandidateFreezeTest {
    @Test
    void serializesTheCompleteTargetBlindRunMatrixCanonically() {
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze();
        Artifact artifact = PolynomialTheoryUtilityCandidateFreeze.create(
            completeBatch()
        );

        assertEquals(
            "regelsuche.polynomial-theory-utility-candidate-freeze/v1",
            artifact.schema()
        );
        assertEquals(
            "polynomial-theory-utility-candidate-freeze-v1.json",
            artifact.fileName()
        );
        assertEquals(
            PolynomialTheoryUtilityPreregistration.STUDY_ID,
            artifact.studyId()
        );
        assertEquals(
            "TARGET_BLIND_CANDIDATE_FREEZE",
            artifact.evidenceStatus()
        );
        assertEquals("HASH_ONLY_NOT_OPENED", artifact.qualificationExposure());
        assertEquals(inputs.contentHash(), artifact.inputContentHash());
        assertEquals(inputs.byteLength(), artifact.inputByteLength());
        assertEquals(600, artifact.rows().size());
        assertEquals(
            30L,
            artifact.rows().stream().map(Row::runId).distinct().count()
        );
        assertEquals(
            5L,
            artifact.rows().stream().map(Row::profileId).distinct().count()
        );
        assertEquals(
            120L,
            artifact.rows().stream()
                .filter(row -> row.terminalStatus()
                    == CandidateResult.TerminalStatus.NO_TRANSITION)
                .count()
        );
        assertEquals(
            480L,
            artifact.rows().stream()
                .filter(row -> row.terminalStatus()
                    == CandidateResult.TerminalStatus.UNSUPPORTED)
                .count()
        );
        assertEquals(
            inputs.inputs().getFirst().inputId(),
            artifact.rows().getFirst().inputId()
        );
        assertEquals(
            inputs.inputs().getLast().inputId(),
            artifact.rows().getLast().inputId()
        );
        assertTrue(artifact.contentHash().matches("sha256:[0-9a-f]{64}"));
        assertEquals(
            artifact.canonicalBytes().length,
            artifact.byteLength()
        );
        assertArrayEquals(
            artifact.canonicalJson().getBytes(StandardCharsets.UTF_8),
            artifact.canonicalBytes()
        );
        assertTrue(artifact.canonicalJson().startsWith("{\n"));
        assertTrue(artifact.canonicalJson().endsWith("}\n"));
        assertFalse(artifact.canonicalJson().contains("\r"));
    }

    @Test
    void bindsOnlyTheSealedQualificationCommitment() {
        Artifact artifact = PolynomialTheoryUtilityCandidateFreeze.create(
            completeBatch()
        );
        String canonical = artifact.canonicalJson();

        assertTrue(canonical.contains(
            "\"qualificationExposure\":\"HASH_ONLY_NOT_OPENED\""
        ));
        assertTrue(canonical.contains(
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME
        ));
        assertTrue(canonical.contains(
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_CONTENT_HASH
        ));
        assertTrue(canonical.contains(
            "\"byteLength\":"
                + PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_BYTE_LENGTH
        ));
        for (String forbidden : List.of(
                "requiredOutcome",
                "reducibilityStatus",
                "multiplicityStatus",
                "referenceExpression",
                "expectedClassifierOutcome",
                "selectedDecision",
                "qualificationResult",
                "expectedOutcome")) {
            assertFalse(canonical.contains("\"" + forbidden + "\":"));
        }
    }

    @Test
    void isByteStableAndSensitiveToOneTerminalPayload() {
        CandidateBatch batch = completeBatch();
        Artifact first = PolynomialTheoryUtilityCandidateFreeze.create(batch);
        Artifact second = PolynomialTheoryUtilityCandidateFreeze.create(
            completeBatch()
        );

        assertEquals(first.canonicalJson(), second.canonicalJson());
        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.byteLength(), second.byteLength());

        List<CandidateResult> changed = new ArrayList<>(batch.results());
        var input = changed.getFirst().input();
        changed.set(
            0,
            CandidateResult.noTransition(
                input,
                "ALTERNATE_TARGET_BLIND_BASELINE_DETAIL"
            )
        );
        CandidateBatch changedBatch = CandidateBatch.create(
            PolynomialTheoryUtilityExecutionInputs.freeze(),
            changed
        );
        Artifact changedArtifact =
            PolynomialTheoryUtilityCandidateFreeze.create(changedBatch);

        assertEquals(first.inputContentHash(), changedArtifact.inputContentHash());
        assertNotEquals(first.contentHash(), changedArtifact.contentHash());
        assertNotEquals(first.canonicalJson(), changedArtifact.canonicalJson());
    }

    @Test
    void rejectsRowReorderingAndResultIdentitySubstitution() {
        Artifact artifact = PolynomialTheoryUtilityCandidateFreeze.create(
            completeBatch()
        );

        List<Row> reordered = new ArrayList<>(artifact.rows());
        Collections.swap(reordered, 0, 1);
        assertThrows(
            IllegalArgumentException.class,
            () -> new Artifact(
                artifact.inputContentHash(),
                artifact.inputByteLength(),
                reordered,
                artifact.canonicalJson(),
                artifact.contentHash(),
                artifact.byteLength()
            )
        );

        Row original = artifact.rows().getFirst();
        Row substituted = new Row(
            original.candidateResultId(),
            original.inputId(),
            original.executionRowId(),
            original.runId(),
            original.caseId(),
            original.profileId(),
            original.checkpointId(),
            original.adapterId(),
            original.terminalStatus(),
            "SUBSTITUTED_DETAIL_WITH_REUSED_RESULT_ID",
            original.primitiveWorkConsumed(),
            original.mechanicalWorkConsumed(),
            original.factorizationWorkConsumed(),
            original.generatedTransitions(),
            original.verifierOutcome(),
            original.transitionEvidenceHash()
        );
        List<Row> substitutedRows = new ArrayList<>(artifact.rows());
        substitutedRows.set(0, substituted);
        assertThrows(
            IllegalArgumentException.class,
            () -> new Artifact(
                artifact.inputContentHash(),
                artifact.inputByteLength(),
                substitutedRows,
                artifact.canonicalJson(),
                artifact.contentHash(),
                artifact.byteLength()
            )
        );
    }

    @Test
    void rejectsCounterfeitCanonicalAndContentMetadata() {
        Artifact artifact = PolynomialTheoryUtilityCandidateFreeze.create(
            completeBatch()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new Artifact(
                artifact.inputContentHash(),
                artifact.inputByteLength(),
                artifact.rows(),
                artifact.canonicalJson() + " ",
                artifact.contentHash(),
                artifact.byteLength() + 1
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new Artifact(
                artifact.inputContentHash(),
                artifact.inputByteLength(),
                artifact.rows(),
                artifact.canonicalJson(),
                "sha256:" + "0".repeat(64),
                artifact.byteLength()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new Artifact(
                "sha256:" + "f".repeat(64),
                artifact.inputByteLength(),
                artifact.rows(),
                artifact.canonicalJson(),
                artifact.contentHash(),
                artifact.byteLength()
            )
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> artifact.rows().clear()
        );

        byte[] firstBytes = artifact.canonicalBytes();
        firstBytes[0] = 0;
        assertEquals('{', artifact.canonicalBytes()[0]);
    }

    @Test
    void rejectsNegativeFrozenWorkValuesBeforeSerialization() {
        Artifact artifact = PolynomialTheoryUtilityCandidateFreeze.create(
            completeBatch()
        );
        Row row = artifact.rows().getFirst();

        assertThrows(
            IllegalArgumentException.class,
            () -> new Row(
                row.candidateResultId(),
                row.inputId(),
                row.executionRowId(),
                row.runId(),
                row.caseId(),
                row.profileId(),
                row.checkpointId(),
                row.adapterId(),
                row.terminalStatus(),
                row.detailCode(),
                -1L,
                row.mechanicalWorkConsumed(),
                row.factorizationWorkConsumed(),
                row.generatedTransitions(),
                row.verifierOutcome(),
                row.transitionEvidenceHash()
            )
        );
    }

    private static CandidateBatch completeBatch() {
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze();
        List<CandidateResult> results = new ArrayList<>(
            inputs.inputs().size()
        );
        for (var input : inputs.inputs()) {
            if (NoFactorizationAdapter.PROFILE_ID.equals(input.profileId())) {
                results.add(CandidateResult.noTransition(
                    input,
                    NoFactorizationAdapter.DETAIL_CODE
                ));
            } else {
                results.add(CandidateResult.create(
                    input,
                    CandidateResult.TerminalStatus.UNSUPPORTED,
                    "TEST_STUB_UNSUPPORTED",
                    0L,
                    0L,
                    0L,
                    0,
                    "NOT_REQUESTED",
                    CandidateResult.NO_TRANSITION_EVIDENCE
                ));
            }
        }
        return CandidateBatch.create(inputs, results);
    }
}
