package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TargetFreeHeldOutMatrixRunnerTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void planFreezesExactly144OpaqueRows() {
        var plan = Evidence.PLAN;
        assertEquals(144, plan.content().rows().size());
        assertEquals(6, plan.content().cases().size());
        assertEquals(4, plan.content().policies().size());
        assertEquals(
            java.util.List.of(8, 16, 32, 64, 128, 256),
            plan.content().workMatching().checkpoints());
        assertEquals(
            TargetFreeHeldOutMatrixRunner.QUALIFICATION_NOT_DISCLOSED,
            plan.content().qualificationDisclosure());
        String json = plan.toCanonicalJson();
        assertFalse(json.contains("x + y + w"));
        assertFalse(json.contains("rule:sympy.trig.pythagorean"));
        assertEquals(
            plan,
            PlanArtifact.fromCanonicalJson(json));
        assertThrows(
            IllegalArgumentException.class,
            () -> PlanArtifact.fromCanonicalJson(json + "\n"));
    }

    @Test
    void candidateFreezeBalancesEveryCheckpointBeforeDisclosure() {
        var freeze = Evidence.FREEZE;
        assertEquals(144, freeze.content().rows().size());
        assertEquals(36, freeze.content().matchedWorkGroups().size());
        assertEquals(
            TargetFreeHeldOutMatrixRunner.QUALIFICATION_NOT_DISCLOSED,
            freeze.content().qualificationDisclosure());
        assertTrue(freeze.content().rows().stream().allMatch(row ->
            row.work().admittedPrimitiveSteps() <= row.checkpoint()));
        assertTrue(freeze.content().rows().stream().allMatch(row ->
            row.work().exactCheckpointReached()
                == TargetFreeHeldOutMatrixRunner.EXACT_CHECKPOINT
                    .equals(row.status())));
        assertTrue(freeze.content().rows().stream().allMatch(row ->
            row.candidateSetCount() <= row.candidateLineageCount()));
        assertEquals(
            freeze,
            FreezeArtifact.fromCanonicalJson(freeze.toCanonicalJson()));
    }

    @Test
    void postFreezeQualificationBalancesTheExactFrozenRows() {
        var qualification = Evidence.QUALIFICATION;
        assertEquals(144, qualification.content().rows().size());
        assertEquals(36, qualification.content().comparisons().size());
        assertEquals(
            TargetFreeHeldOutMatrixRunner.QUALIFICATION_DISCLOSED,
            qualification.content().qualificationDisclosure());
        assertEquals(
            Evidence.FREEZE.contentHash(),
            qualification.content().candidateFreezeHash());
        assertEquals(
            qualification,
            QualificationArtifact.fromCanonicalJson(
                qualification.toCanonicalJson()));
    }

    private static final class Evidence {
        private static final RunArtifacts RUN = execute();
        private static final PlanArtifact PLAN = RUN.plan();
        private static final FreezeArtifact FREEZE = RUN.freeze();
        private static final QualificationArtifact QUALIFICATION =
            RUN.qualification();

        private static RunArtifacts execute() {
            String revision = System.getenv(
                "REGELSUCHE_AUTHORITY_GITHUB_SHA");
            if (revision == null || !revision.matches("[0-9a-f]{40}")) {
                revision = System.getenv("GITHUB_SHA");
            }
            if (revision == null || !revision.matches("[0-9a-f]{40}")) {
                revision = REVISION;
            }
            try {
                return TargetFreeHeldOutMatrixRunner.write(
                    Path.of("build/reports/representation-discovery/"
                        + "target-free-held-out-matrix"),
                    revision);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private Evidence() {
        }
    }
}
