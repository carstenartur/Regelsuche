package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.StageBudget;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousResearchBriefV2Test {
    @Test
    void addsNoveltyAndLifecycleWithoutTargetFields() {
        AutonomousResearchBriefV2 brief = AutonomousEvidenceDagV2Fixtures.brief();
        List<String> componentNames = java.util.Arrays.stream(
                AutonomousResearchBriefV2.class.getRecordComponents())
            .map(component -> component.getName().toLowerCase())
            .toList();

        assertTrue(brief.stageBudgets().containsKey(EvidenceStage.PROJECT_NOVELTY));
        assertTrue(brief.stageBudgets().containsKey(EvidenceStage.LIFECYCLE_HANDOFF));
        assertTrue(componentNames.stream().noneMatch(name ->
            name.contains("target") || name.contains("expectedanswer")));
        assertFalse(brief.toCanonicalJson().contains("targetExpression"));
        assertFalse(brief.toCanonicalJson().contains("expectedAnswer"));
    }

    @Test
    void rejectsWrongStageResourcesAndTimeOnlyBudgets() {
        Map<EvidenceStage, StageBudget> wrongStage =
            new java.util.EnumMap<>(AutonomousEvidenceDagV2Fixtures.budgets());
        wrongStage.put(
            EvidenceStage.PROJECT_NOVELTY,
            new StageBudget(Map.of(ResourceKind.PROOF_ATTEMPTS, 1L)));
        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousEvidenceDagV2Fixtures.brief(wrongStage));

        Map<EvidenceStage, StageBudget> timeOnly =
            new java.util.EnumMap<>(AutonomousEvidenceDagV2Fixtures.budgets());
        timeOnly.put(
            EvidenceStage.LIFECYCLE_HANDOFF,
            new StageBudget(Map.of(ResourceKind.WALL_CLOCK_MILLIS, 100L)));
        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousEvidenceDagV2Fixtures.brief(timeOnly));
    }
}
