package de.regelsuche.benchmark.polynomial;

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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolynomialTheoryUtilityExecutionInputsTest {
    @Test
    void freezesOneTargetBlindInputForEveryPlanRow() {
        var plan = PolynomialTheoryUtilityExecutionPlan.freeze();
        var artifact = PolynomialTheoryUtilityExecutionInputs.freeze();

        assertEquals(
            "regelsuche.polynomial-theory-utility-execution-inputs/v1",
            artifact.schema()
        );
        assertEquals(
            PolynomialTheoryUtilityPreregistration.STUDY_ID,
            artifact.studyId()
        );
        assertEquals("READY_NOT_EXECUTED", artifact.evidenceStatus());
        assertEquals(600, artifact.inputs().size());
        assertEquals(
            PolynomialTheoryUtilityExecutionInputs.EXPECTED_CONTENT_HASH,
            artifact.contentHash()
        );
        assertEquals(
            PolynomialTheoryUtilityExecutionInputs.EXPECTED_BYTE_LENGTH,
            artifact.byteLength()
        );
        assertEquals(
            600L,
            artifact.inputs().stream()
                .map(PolynomialTheoryUtilityExecutionInput::inputId)
                .distinct()
                .count()
        );

        for (int index = 0; index < plan.rows().size(); index++) {
            var row = plan.rows().get(index);
            var input = artifact.inputs().get(index);
            assertEquals(row.rowId(), input.rowId());
            assertEquals(row.runId(), input.runId());
            assertEquals(row.caseId(), input.caseId());
            assertEquals(row.profileId(), input.profileId());
            assertEquals(row.checkpointId(), input.checkpointId());
            assertEquals(
                PolynomialTheoryUtilityExecutionInputs
                    .profile(row.profileId()).adapterId(),
                input.adapterId()
            );
            assertEquals(
                row.admittedPrimitiveWork(),
                input.admittedPrimitiveWork()
            );
            assertEquals(
                row.totalMechanicalWork(),
                input.totalMechanicalWork()
            );
            assertEquals(
                row.factorizationWork(),
                input.factorizationWork()
            );
            assertEquals("READY_NOT_EXECUTED", input.inputStatus());
        }
    }

    @Test
    void retainsRunMajorOrderingAndFrozenAdapterBindings() {
        var formation = PolynomialTheoryUtilityCaseCorpus.load();
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze().inputs();
        List<String> caseIds = formation.cases().stream()
            .map(PolynomialTheoryUtilityCaseCorpus.FormationCase::caseId)
            .toList();
        int offset = 0;

        for (var profile : PolynomialTheoryUtilityExecutionPlan.PROFILES) {
            for (var checkpoint
                    : PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS) {
                var run = inputs.subList(offset, offset + caseIds.size());
                assertTrue(run.stream().allMatch(value ->
                    profile.profileId().equals(value.profileId())));
                assertTrue(run.stream().allMatch(value ->
                    profile.adapterId().equals(value.adapterId())));
                assertTrue(run.stream().allMatch(value ->
                    checkpoint.checkpointId().equals(
                        value.checkpointId())));
                assertEquals(
                    caseIds,
                    run.stream()
                        .map(PolynomialTheoryUtilityExecutionInput::caseId)
                        .toList()
                );
                assertEquals(
                    1L,
                    run.stream()
                        .map(PolynomialTheoryUtilityExecutionInput::runId)
                        .distinct()
                        .count()
                );
                offset += caseIds.size();
            }
        }
        assertEquals(600, offset);
    }

    @Test
    void exposesNoQualificationResultOrDecisionFields() {
        String canonical =
            PolynomialTheoryUtilityExecutionInputs.freeze().canonicalJson();

        for (String forbidden : List.of(
                "\"requiredOutcome\"",
                "\"reducibilityStatus\"",
                "\"multiplicityStatus\"",
                "\"referenceExpression\"",
                "\"expectedClassifierOutcome\"",
                "\"sourceExpression\"",
                "\"resultStatus\"",
                "\"selectedDecision\"")) {
            assertFalse(canonical.contains(forbidden), forbidden);
        }
        for (String binding : List.of(
                "\"qualificationExposure\": "
                    + "\"HASH_ONLY_BEFORE_RESULT_FREEZE\"",
                "\"formationResolution\":\"FROZEN_CASE_ID_LOOKUP\"",
                "\"profilePolicySource\":\"FROZEN_EXECUTION_PLAN\"",
                "\"resultVisibility\":\"NONE\"",
                "\"decisionAuthority\":\"NONE\"",
                "\"adapterOutputAuthority\":"
                    + "\"VERSIONED_CANDIDATE_FREEZE_ONLY\"",
                "\"rowOrder\":\"RUN_MAJOR_CONTIGUOUS\"")) {
            assertTrue(canonical.contains(binding), binding);
        }
        assertFalse(canonical.contains("\r"));
    }

    @Test
    void writesOnlyInputsAndRejectsQualification(
        @TempDir Path directory
    ) throws IOException {
        var artifact = PolynomialTheoryUtilityExecutionInputs.write(directory);
        Path inputs = directory.resolve(
            PolynomialTheoryUtilityExecutionInputs.FILE_NAME
        );
        Path qualification = directory.resolve(
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME
        );

        assertEquals(
            artifact.canonicalJson(),
            Files.readString(inputs, StandardCharsets.UTF_8)
        );
        assertFalse(Files.exists(qualification));

        Files.writeString(qualification, "sealed\n", StandardCharsets.UTF_8);
        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> PolynomialTheoryUtilityExecutionInputs.write(directory)
        );
        assertTrue(failure.getMessage().contains("sealed qualification"));
    }

    @Test
    void rejectsRowsAdaptersStatusesAndCanonicalBytesThatDrift() {
        var artifact = PolynomialTheoryUtilityExecutionInputs.freeze();
        var original = artifact.inputs().get(0);
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityExecutionInput(
                original.inputId(),
                original.rowId(),
                original.runId(),
                original.caseId(),
                original.profileId(),
                original.checkpointId(),
                "different-adapter/v1",
                original.admittedPrimitiveWork(),
                original.totalMechanicalWork(),
                original.factorizationWork(),
                original.inputStatus()
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityExecutionInput(
                original.inputId(),
                original.rowId(),
                original.runId(),
                original.caseId(),
                original.profileId(),
                original.checkpointId(),
                original.adapterId(),
                original.admittedPrimitiveWork(),
                original.totalMechanicalWork(),
                original.factorizationWork(),
                "EXECUTED"
            )
        );

        var changed = new ArrayList<>(artifact.inputs());
        Collections.swap(changed, 0, 1);
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityExecutionInputArtifact(
                changed,
                artifact.canonicalJson()
            )
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> artifact.inputs().add(original)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityExecutionInputs.main(new String[0])
        );
    }

    @Test
    void inputIdentityBindsTheFrozenAdapterAndPlanRow() {
        var row = PolynomialTheoryUtilityExecutionPlan.freeze().rows().get(0);
        var profile =
            PolynomialTheoryUtilityExecutionInputs.profile(row.profileId());
        String original =
            PolynomialTheoryUtilityExecutionInputIdentity.inputId(
                row,
                profile
            );
        var changedProfile = new PolynomialTheoryUtilityExecutionProfile(
            profile.profileId(),
            "other-adapter/v1",
            profile.scope(),
            profile.factorizationMode(),
            profile.engineId(),
            profile.transformationId(),
            profile.cacheMode(),
            profile.fallbackMode(),
            profile.candidateSelection()
        );

        assertNotEquals(
            original,
            PolynomialTheoryUtilityExecutionInputIdentity.inputId(
                row,
                changedProfile
            )
        );
        assertEquals(
            Set.of("READY_NOT_EXECUTED"),
            PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
                .map(PolynomialTheoryUtilityExecutionInput::inputStatus)
                .collect(java.util.stream.Collectors.toSet())
        );
    }
}
