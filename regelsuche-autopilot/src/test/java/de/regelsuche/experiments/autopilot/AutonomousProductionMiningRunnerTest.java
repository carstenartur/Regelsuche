package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutonomousProductionMiningRunnerTest {
    private final AutonomousProductionMiningRunner runner =
        new AutonomousProductionMiningRunner();
    private AutonomousProductionMiningRunner.MiningRun sequential;
    private AutonomousProductionMiningRunner.MiningRun parallel;

    @BeforeAll
    void runPinnedCampaigns() {
        sequential = runner.runPinned(1);
        parallel = runner.runPinned(4);
    }

    @Test
    void minesCrossFamilyCandidateAndRetainsPredeclaredRejection() throws Exception {
        var run = parallel;

        assertEquals(12, run.generation().observations().size());
        assertEquals(2, run.generation().observationBranches().stream()
            .map(AutonomousEvidenceDagV2.ObservationBranch::familyId)
            .distinct()
            .count());
        assertFalse(run.fullBatch().evidence().report().conjectures().isEmpty());
        assertFalse(run.fullBatch().binding().receipt().outputs().isEmpty());
        assertTrue(run.fullBatch().binding().receipt().outputs().stream()
            .anyMatch(output -> output.sources().stream()
                .map(AutonomousEvidenceDagV2.SourceLink::familyId)
                .distinct().count() == 2L));
        assertTrue(run.fullBatch().binding().receipt().outputs().stream()
            .flatMap(output -> output.sources().stream())
            .allMatch(source -> source.snapshotHash() != null
                && source.evidenceHash() != null
                && source.observationBranchHash() != null));
        Map<String, AutonomousProductionGenerationRunner.GeneratedObservation>
            observationsById = run.generation().observations().stream()
                .collect(Collectors.toMap(
                    item -> item.snapshot().observationId(), Function.identity()));
        run.fullBatch().binding().receipt().outputs().forEach(output -> {
            var conjecture = run.fullBatch().evidence().report().conjectures().stream()
                .filter(item -> item.conjectureId().equals(output.conjectureId()))
                .findFirst()
                .orElseThrow();
            assertEquals(
                new TreeSet<>(conjecture.supportingObservationIds()),
                output.sources().stream()
                    .map(AutonomousEvidenceDagV2.SourceLink::observationId)
                    .collect(Collectors.toCollection(TreeSet::new)));
            output.sources().forEach(source -> {
                var observation = observationsById.get(source.observationId());
                assertEquals(observation.branch().branchId(), source.sourceBranchId());
                assertEquals(observation.snapshot().snapshotHash(), source.snapshotHash());
                assertEquals(observation.snapshot().evidenceHash(), source.evidenceHash());
                assertEquals(observation.snapshot().branchHash(),
                    source.observationBranchHash());
            });
        });

        assertTrue(run.rejectionBatch().evidence().report().conjectures().isEmpty());
        assertTrue(run.rejectionBatch().binding().receipt().outputs().isEmpty());
        assertTrue(run.rejectionBatch().evidence().report().rejectedClusters().stream()
            .anyMatch(cluster -> "alpha-distinct-support<2".equals(cluster.reason())));
        assertFalse(run.rejectionBatch().binding().receipt().rejectedClusters().isEmpty());

        assertEquals(2L,
            run.formationReceipt().executed(ResourceKind.MINING_BATCHES));
        assertTrue(run.formationReceipt().executed(ResourceKind.CANDIDATES) >= 1L);
        assertEquals(2, run.dag().decisions().size());
        assertEquals(2, run.dag().receipts().size());
        assertFalse(run.targetProvided());
        assertFalse(run.miningRunIsMathematicalEvidence());
        assertEquals("NOT_EVALUATED", run.promotionStatus());
        assertEquals("NOT_EVALUATED", run.publicEvidenceStatus());

        String combined = run.fullBatch().evidence().toJson()
            + run.rejectionBatch().evidence().toJson()
            + run.formationReceipt().toCanonicalJson()
            + run.toCanonicalJson();
        assertTrue(combined.contains("\"targetProvided\":false"));
        assertFalse(combined.contains("targetExpression"));
        assertFalse(combined.contains("expectedAnswer"));

        Path output = Path.of(
            "build", "reports", "autopilot-production-mining");
        runner.write(output, run);
        for (String file : List.of(
                "brief-v2.json",
                "seeds.json",
                "observations.json",
                "generation-receipt.json",
                "discovery-report.json",
                "generation-run.json",
                "plan-v2.json",
                "full-decision.json",
                "full-mining-evidence.json",
                "full-binding.json",
                "full-receipt.json",
                "full-execution-v2.json",
                "full-lineage-v2.json",
                "rejection-decision.json",
                "rejection-mining-evidence.json",
                "rejection-binding.json",
                "rejection-receipt.json",
                "rejection-execution-v2.json",
                "rejection-lineage-v2.json",
                "candidate-formation-receipt.json",
                "evidence-dag.json",
                "production-mining-run.json")) {
            Path artifact = output.resolve(file);
            assertTrue(Files.isRegularFile(artifact), file);
            assertTrue(Files.size(artifact) > 0L, file);
        }
    }

    @Test
    void miningEvidenceIsStableAcrossGenerationParallelism() {
        assertEquals(
            sequential.generation().observationBundle().contentHash(),
            parallel.generation().observationBundle().contentHash());
        assertEquals(
            sequential.fullBatch().evidence().contentHash(),
            parallel.fullBatch().evidence().contentHash());
        assertEquals(
            sequential.fullBatch().binding().receipt().contentHash(),
            parallel.fullBatch().binding().receipt().contentHash());
        assertEquals(
            sequential.rejectionBatch().evidence().contentHash(),
            parallel.rejectionBatch().evidence().contentHash());
        assertEquals(
            sequential.formationReceipt().contentHash(),
            parallel.formationReceipt().contentHash());
        assertEquals(sequential.dag().contentHash(), parallel.dag().contentHash());
        assertEquals(sequential.contentHash(), parallel.contentHash());
    }
}
