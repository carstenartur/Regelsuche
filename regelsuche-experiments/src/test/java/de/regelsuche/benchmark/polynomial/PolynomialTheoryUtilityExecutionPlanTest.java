package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void matchesWorkForAllFiveProfilesAtEveryCheckpoint() {
        var formation = PolynomialTheoryUtilityCaseCorpus.load();
        var artifact = PolynomialTheoryUtilityExecutionPlan.freeze();
        List<String> profiles =
            PolynomialTheoryUtilityPreregistration.PROFILES;
        int offset = 0;

        for (var studyCase : formation.cases()) {
            for (var checkpoint
                    : PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS) {
                var rows = artifact.rows().subList(offset, offset + 5);
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
                offset += 5;
            }
        }
        assertEquals(600, offset);
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
