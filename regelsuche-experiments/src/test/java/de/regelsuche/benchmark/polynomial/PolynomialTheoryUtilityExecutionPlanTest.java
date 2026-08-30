package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityExecutionPlanTest {
    @Test
    void freezesTwentyCasesFiveProfilesAndSixCheckpoints() {
        var artifact = PolynomialTheoryUtilityExecutionPlan.freeze();

        assertEquals(
            "regelsuche.polynomial-theory-utility-execution-plan/v1",
            artifact.schema()
        );
        assertEquals(
            PolynomialTheoryUtilityPreregistration.STUDY_ID,
            artifact.studyId()
        );
        assertEquals("FROZEN_NOT_EXECUTED", artifact.evidenceStatus());
        assertEquals(600, artifact.rows().size());
        assertEquals(
            PolynomialTheoryUtilityExecutionPlan.EXPECTED_CONTENT_HASH,
            artifact.contentHash()
        );
        assertEquals(
            PolynomialTheoryUtilityExecutionPlan.EXPECTED_BYTE_LENGTH,
            artifact.byteLength()
        );
        assertEquals(
            600L,
            artifact.rows().stream()
                .map(PolynomialTheoryUtilityExecutionRow::rowId)
                .distinct()
                .count()
        );
        assertTrue(artifact.rows().stream().allMatch(row ->
            "NOT_EXECUTED".equals(row.resultStatus())));
        assertTrue(artifact.canonicalJson().contains(
            "\"rowOrder\":\"RUN_MAJOR_CONTIGUOUS\""
        ));
        assertFalse(artifact.canonicalJson().contains("\r"));
        for (String sealed : List.of(
                "\"requiredOutcome\"",
                "\"reducibilityStatus\"",
                "\"multiplicityStatus\"",
                "\"referenceExpression\"",
                "\"expectedClassifierOutcome\"")) {
            assertFalse(artifact.canonicalJson().contains(sealed));
        }
    }

    @Test
    void emitsThirtyContiguousRunsInFrozenCaseOrder() {
        var formation = PolynomialTheoryUtilityCaseCorpus.load();
        var rows = PolynomialTheoryUtilityExecutionPlan.freeze().rows();
        List<String> caseIds = formation.cases().stream()
            .map(PolynomialTheoryUtilityCaseCorpus.FormationCase::caseId)
            .toList();
        int runSize = caseIds.size();
        int offset = 0;

        for (var profile : PolynomialTheoryUtilityExecutionPlan.PROFILES) {
            for (var checkpoint
                    : PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS) {
                var run = rows.subList(offset, offset + runSize);
                String expectedRunId =
                    PolynomialTheoryUtilityExecutionIdentity.runId(
                        profile,
                        checkpoint
                    );
                assertTrue(run.stream().allMatch(row ->
                    expectedRunId.equals(row.runId())));
                assertTrue(run.stream().allMatch(row ->
                    profile.profileId().equals(row.profileId())));
                assertTrue(run.stream().allMatch(row ->
                    checkpoint.checkpointId().equals(row.checkpointId())));
                assertEquals(
                    caseIds,
                    run.stream()
                        .map(PolynomialTheoryUtilityExecutionRow::caseId)
                        .toList()
                );
                offset += runSize;
            }
        }
        assertEquals(600, offset);
    }

    @Test
    void contentAddressesEveryRunPolicyAndCheckpointFraction() {
        var profile = PolynomialTheoryUtilityExecutionPlan.PROFILES.get(1);
        var checkpoint = PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS.get(0);
        String original = PolynomialTheoryUtilityExecutionIdentity.runId(
            profile,
            checkpoint
        );
        var changedEngine = new PolynomialTheoryUtilityExecutionProfile(
            profile.profileId(),
            profile.adapterId(),
            profile.scope(),
            profile.factorizationMode(),
            "other-engine/v1",
            profile.transformationId(),
            profile.cacheMode(),
            profile.fallbackMode(),
            profile.candidateSelection()
        );
        var changedFraction =
            new PolynomialTheoryUtilityExecutionCheckpoint(
                checkpoint.checkpointId(),
                checkpoint.ordinal(),
                2,
                12
            );

        assertNotEquals(
            original,
            PolynomialTheoryUtilityExecutionIdentity.runId(
                changedEngine,
                checkpoint
            )
        );
        assertNotEquals(
            original,
            PolynomialTheoryUtilityExecutionIdentity.runId(
                profile,
                changedFraction
            )
        );
    }

    @Test
    void rejectsCanonicalBytesPairedWithDifferentRows() {
        var artifact = PolynomialTheoryUtilityExecutionPlan.freeze();
        var changed = new ArrayList<>(artifact.rows());
        Collections.swap(changed, 0, 1);

        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityExecutionArtifact(
                changed,
                artifact.canonicalJson()
            )
        );
    }

    @Test
    void matchesWorkForAllFiveProfilesAtEveryCheckpoint() {
        var formation = PolynomialTheoryUtilityCaseCorpus.load();
        var artifact = PolynomialTheoryUtilityExecutionPlan.freeze();
        List<String> profiles =
            PolynomialTheoryUtilityPreregistration.PROFILES;

        for (var studyCase : formation.cases()) {
            for (var checkpoint
                    : PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS) {
                var rows = artifact.rows().stream()
                    .filter(row ->
                        studyCase.caseId().equals(row.caseId()))
                    .filter(row ->
                        checkpoint.checkpointId().equals(row.checkpointId()))
                    .toList();
                assertEquals(
                    profiles,
                    rows.stream()
                        .map(PolynomialTheoryUtilityExecutionRow::profileId)
                        .toList()
                );
                assertEquals(
                    Set.of(PolynomialTheoryUtilityExecutionPlan.scale(
                        studyCase.admittedPrimitiveWork(),
                        checkpoint
                    )),
                    rows.stream()
                        .map(
                            PolynomialTheoryUtilityExecutionRow
                                ::admittedPrimitiveWork
                        )
                        .collect(java.util.stream.Collectors.toSet())
                );
                assertEquals(
                    Set.of(PolynomialTheoryUtilityExecutionPlan.scale(
                        studyCase.totalMechanicalWork(),
                        checkpoint
                    )),
                    rows.stream()
                        .map(
                            PolynomialTheoryUtilityExecutionRow
                                ::totalMechanicalWork
                        )
                        .collect(java.util.stream.Collectors.toSet())
                );
                assertEquals(
                    Set.of(PolynomialTheoryUtilityExecutionPlan.scale(
                        studyCase.factorizationWork(),
                        checkpoint
                    )),
                    rows.stream()
                        .map(
                            PolynomialTheoryUtilityExecutionRow
                                ::factorizationWork
                        )
                        .collect(java.util.stream.Collectors.toSet())
                );
            }
        }
    }

    @Test
    void roundsTinyBudgetsUpAtCumulativeCheckpoints() {
        var rows = PolynomialTheoryUtilityExecutionPlan.freeze().rows().stream()
            .filter(row -> "z08-tiny-budget".equals(row.caseId()))
            .filter(row -> "NO_FACTORIZATION".equals(row.profileId()))
            .toList();

        assertEquals(
            List.of(1, 1, 2, 2, 3, 4),
            rows.stream()
                .map(
                    PolynomialTheoryUtilityExecutionRow
                        ::admittedPrimitiveWork
                )
                .toList()
        );
        assertEquals(
            List.of(1, 1, 1, 1, 2, 2),
            rows.stream()
                .map(PolynomialTheoryUtilityExecutionRow::factorizationWork)
                .toList()
        );
    }
}
