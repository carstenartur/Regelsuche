package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.ObservationBranch;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousAggregateDecisionV2Test {
    @Test
    void isStableUnderObservationAndResourcePermutation() {
        AutonomousResearchBriefV2 brief = AutonomousEvidenceDagV2Fixtures.brief();
        List<ObservationBranch> observations = AutonomousEvidenceDagV2Fixtures.observations();
        List<ObservationBranch> reversed = new ArrayList<>(observations);
        java.util.Collections.reverse(reversed);

        var first = AutonomousEvidenceDagV2.planCandidateFormation(
            brief,
            "mine-batch-1",
            observations,
            AutonomousEvidenceDagV2Fixtures.plannedCandidateFormationResources(),
            "mine structurally independent untargeted observations");
        var second = AutonomousEvidenceDagV2.planCandidateFormation(
            brief,
            "mine-batch-1",
            reversed,
            Map.of(
                ResourceKind.CANDIDATES, 3L,
                ResourceKind.WALL_CLOCK_MILLIS, 500L,
                ResourceKind.MINING_BATCHES, 1L),
            "mine structurally independent untargeted observations");

        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(
            List.of("obs-branch-a", "obs-branch-b", "obs-branch-c"),
            first.inputs().stream().map(ObservationBranch::branchId).toList());
    }

    @Test
    void rejectsTargetedDuplicateOrInsufficientObservationEvidence() {
        AutonomousResearchBriefV2 brief = AutonomousEvidenceDagV2Fixtures.brief();
        assertThrows(
            IllegalArgumentException.class,
            () -> new ObservationBranch(
                "targeted-branch",
                AutonomousEvidenceDagV2.BranchType.OBSERVATION,
                "family-a",
                "targeted-observation",
                AutonomousEvidenceDagV2Fixtures.hash("targeted-snapshot"),
                AutonomousEvidenceDagV2Fixtures.hash("targeted-evidence"),
                "TARGETED",
                AutonomousEvidenceDagV2Fixtures.hash("targeted-content")));
        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousEvidenceDagV2.planCandidateFormation(
                brief,
                "duplicate-evidence-batch",
                List.of(
                    AutonomousEvidenceDagV2Fixtures.observation(
                        "duplicate-a", "family-a", "duplicate-observation-a",
                        "same-evidence"),
                    AutonomousEvidenceDagV2Fixtures.observation(
                        "duplicate-b", "family-b", "duplicate-observation-b",
                        "same-evidence")),
                AutonomousEvidenceDagV2Fixtures.plannedCandidateFormationResources(),
                "duplicate evidence must not count twice"));
        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousEvidenceDagV2.planCandidateFormation(
                brief,
                "one-observation-batch",
                List.of(AutonomousEvidenceDagV2Fixtures.observations().getFirst()),
                AutonomousEvidenceDagV2Fixtures.plannedCandidateFormationResources(),
                "insufficient support must not be mined"));
    }

    @Test
    void rejectsUnplannedOrOverspentAggregateResources() {
        AutonomousResearchBriefV2 brief = AutonomousEvidenceDagV2Fixtures.brief();
        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousEvidenceDagV2.planCandidateFormation(
                brief,
                "wrong-resource-batch",
                AutonomousEvidenceDagV2Fixtures.observations(),
                Map.of(
                    ResourceKind.MINING_BATCHES, 1L,
                    ResourceKind.PROOF_ATTEMPTS, 1L),
                "proof work does not belong to mining"));
        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousEvidenceDagV2.planCandidateFormation(
                brief,
                "overspent-batch",
                AutonomousEvidenceDagV2Fixtures.observations(),
                Map.of(
                    ResourceKind.MINING_BATCHES, 3L,
                    ResourceKind.CANDIDATES, 1L),
                "planned work must stay inside the brief"));
    }
}
