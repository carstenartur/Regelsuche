package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.example.SeedExpression;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutonomousProductionGenerationRunnerTest {
    private final AutonomousProductionGenerationRunner runner =
        new AutonomousProductionGenerationRunner();

    @Test
    void generatesTwoFamiliesAndTwelveImmutableTargetFreeObservations()
            throws Exception {
        var run = runner.runPinned(4);

        assertEquals(12, run.seedCatalog().seeds().size());
        assertEquals(12, run.observations().size());
        assertEquals(2, run.observations().stream()
            .map(item -> item.branch().familyId())
            .distinct()
            .count());
        assertTrue(run.observations().stream().allMatch(item ->
            item.searchResult().status() == GoalStatus.UNTARGETED));
        assertTrue(run.observations().stream().allMatch(item ->
            item.snapshot().states().size() > 1));
        assertTrue(run.observations().stream().allMatch(item ->
            !item.snapshot().selectedRuleIds().isEmpty()));
        assertTrue(run.observations().stream().allMatch(item ->
            item.branch().snapshotHash().equals(item.snapshot().snapshotHash())
                && item.branch().evidenceHash().equals(item.snapshot().evidenceHash())
                && item.branch().contentHash().equals(item.snapshot().branchHash())));

        assertEquals(12L, run.receipt().executed(ResourceKind.OBSERVATIONS));
        assertTrue(run.receipt().executed(ResourceKind.EXPLORED_STATES) >= 12L);
        assertTrue(run.receipt().executed(ResourceKind.GENERATED_STATES) >= 12L);
        assertFalse(run.targetProvided());
        assertFalse(run.generationIsMathematicalEvidence());
        assertEquals("NOT_EVALUATED", run.promotionStatus());
        assertEquals("NOT_EVALUATED", run.publicEvidenceStatus());

        String combined = run.brief().toCanonicalJson()
            + run.seedCatalog().toCanonicalJson()
            + run.observationBundle().toCanonicalJson()
            + run.receipt().toCanonicalJson()
            + run.toCanonicalJson();
        assertTrue(combined.contains("\"targetProvided\":false"));
        assertFalse(combined.contains("targetExpression"));
        assertFalse(combined.contains("expectedAnswer"));

        Path output = Path.of(
            "build", "reports", "autopilot-production-generation");
        runner.write(output, run);
        for (String file : List.of(
                "brief-v2.json",
                "seeds.json",
                "observations.json",
                "generation-receipt.json",
                "discovery-report.json",
                "generation-run.json")) {
            Path artifact = output.resolve(file);
            assertTrue(Files.isRegularFile(artifact));
            assertTrue(Files.size(artifact) > 0L);
        }
    }

    @Test
    void canonicalEvidenceIsStableAcrossInputOrderAndParallelism() {
        var brief = PinnedAutonomousProductionCampaign.brief();
        var sequential = runner.run(
            brief,
            PinnedAutonomousProductionCampaign.seeds(),
            1);
        List<SeedExpression> reversed = new ArrayList<>(
            PinnedAutonomousProductionCampaign.seeds());
        Collections.reverse(reversed);
        var parallel = runner.run(brief, reversed, 4);

        assertEquals(
            sequential.seedCatalog().contentHash(),
            parallel.seedCatalog().contentHash());
        assertEquals(
            sequential.observationBundle().contentHash(),
            parallel.observationBundle().contentHash());
        assertEquals(
            sequential.receipt().contentHash(),
            parallel.receipt().contentHash());
        assertEquals(
            sequential.discoveryReportHash(),
            parallel.discoveryReportHash());
        assertEquals(sequential.contentHash(), parallel.contentHash());
        assertEquals(
            sequential.observationBranches(),
            parallel.observationBranches());
    }

    @Test
    void rejectsAnUndersizedSingleFamilyOrBlankIdCatalog() {
        var brief = PinnedAutonomousProductionCampaign.brief();
        List<SeedExpression> seeds = PinnedAutonomousProductionCampaign.seeds();

        assertThrows(
            IllegalArgumentException.class,
            () -> runner.run(brief, seeds.subList(0, 11), 1));
        List<SeedExpression> oneFamily = seeds.stream()
            .map(seed -> new SeedExpression(
                seed.id(),
                seed.expression(),
                PinnedAutonomousProductionCampaign.LEFT_FACTOR_GENERATOR,
                seed.category(),
                seed.tags(),
                seed.assumptions()))
            .toList();
        assertThrows(
            IllegalArgumentException.class,
            () -> runner.run(brief, oneFamily, 1));

        List<SeedExpression> blankId = new ArrayList<>(seeds);
        SeedExpression first = blankId.getFirst();
        blankId.set(0, new SeedExpression(
            "",
            first.expression(),
            first.source(),
            first.category(),
            first.tags(),
            first.assumptions()));
        assertThrows(
            IllegalArgumentException.class,
            () -> runner.run(brief, blankId, 1));
    }
}
