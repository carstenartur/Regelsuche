package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.experiments.autopilot.AutonomousProductionCampaignRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutonomousCandidateQualificationRunnerTest {
    private final AutonomousCandidateQualificationRunner runner =
        new AutonomousCandidateQualificationRunner();
    private AutonomousCandidateQualificationRunner.QualificationRun run;

    @BeforeAll
    void qualifyRetainedProductionCandidate() {
        run = runner.run(new AutonomousProductionCampaignRunner().runPinned(2));
    }

    @Test
    void qualifiesExactCandidateOnBalancedHeldOutCompositeFactorSuite()
            throws Exception {
        var evidence = run.evidence();
        assertTrue(run.split().passed());
        assertEquals(1, run.split().heldOutFamilyOrClusterCount());
        assertTrue(run.split().upstreamCollisions().isEmpty());
        assertTrue(run.split().internalCollisions().isEmpty());
        assertEquals(12, evidence.configuredPositiveHoldouts());
        assertEquals(12, evidence.executedPositiveHoldouts());
        assertEquals(12, evidence.configuredNegativeHoldouts());
        assertEquals(12, evidence.executedNegativeHoldouts());
        assertEquals(0, evidence.mandatorySkippedWorkCount());
        assertEquals(0, evidence.refutingHoldouts());
        assertEquals(0, evidence.counterexamplesFound());
        assertTrue(run.utility().pairedUtilityEvaluated());
        assertTrue(run.utility().materialGainCount() > 0);
        assertEquals(0, run.utility().correctnessRegressionCount());
        assertTrue(run.utility().beneficial());
        assertTrue(evidence.qualified());
        assertEquals(
            run.campaign().lifecycle().conjecture().conjectureId(),
            evidence.conjectureId());
        assertEquals(
            run.campaign().lifecycle().mining().fullBatch().evidence().contentHash(),
            evidence.miningEvidenceHash());
        assertFalse(evidence.supportingObservationIds().isEmpty());
        assertEquals(
            evidence.supportingObservationIds().size(),
            evidence.sourceObservationBranchHashes().size());

        Path output = Path.of("build", "reports", "candidate-qualification");
        runner.write(output, run);
        for (String file : List.of(
                "qualification-suite.json",
                "qualification-split-audit.json",
                "qualification-evaluation.json",
                "qualification-utility.json",
                "candidate-qualification-evidence.json",
                "candidate-qualification-run.json")) {
            assertTrue(Files.isRegularFile(output.resolve(file)), file);
            assertTrue(Files.size(output.resolve(file)) > 0L, file);
        }
    }
}
