package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.experiments.autopilot.AutonomousCandidateLifecycleV2.LifecycleOutcome;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyStatus;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofStatus;
import de.regelsuche.validation.CandidateProofStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutonomousProductionLifecycleRunnerTest {
    private final AutonomousProductionLifecycleRunner runner =
        new AutonomousProductionLifecycleRunner();
    private AutonomousProductionLifecycleRunner.LifecycleRun sequential;
    private AutonomousProductionLifecycleRunner.LifecycleRun parallel;

    @BeforeAll
    void runPinnedCampaigns() {
        sequential = runner.runPinned(1);
        parallel = runner.runPinned(4);
    }

    @Test
    void completesValidationNoveltyProofAndConservativeHandoff() throws Exception {
        var run = parallel;

        assertEquals(EvaluationStatus.ACCEPTED_FOR_PROOF,
            run.evaluation().status());
        assertTrue(run.evaluation().holdoutsComplete());
        assertTrue(run.evaluation().allHoldoutsPassed());
        assertEquals(3, run.evaluation().executedPositiveHoldouts());
        assertEquals(3, run.evaluation().executedNegativeHoldouts());
        assertEquals("NO_COUNTEREXAMPLE_FOUND",
            run.evaluation().counterexample().status());
        assertEquals(List.of(
            "numeric-boundary-values",
            "rational-samples",
            "numeric-random",
            "complex-samples"),
            run.evaluation().counterexample().attemptedSources());
        assertTrue(run.evaluation().counterexample().inferredAssumptions().isEmpty());
        assertTrue(run.evaluation().counterexample().assignments().isEmpty());

        assertEquals(NoveltyStatus.NOVEL_WITHIN_PROJECT,
            run.novelty().status());
        assertEquals(7, run.novelty().checkedActiveRules());
        assertEquals(0, run.novelty().checkedPriorCandidates());
        assertTrue(run.novelty().matches().isEmpty());
        assertEquals("NOT_EVALUATED", run.novelty().externalNoveltyStatus());

        assertEquals(ProofStatus.SYMBOLICALLY_VERIFIED,
            run.proof().proofStatus());
        assertTrue(run.proof().proofObligationEmitted());
        assertFalse(run.proof().obligation().targetProvided());
        assertTrue(run.proof().blockers().isEmpty());
        assertEquals("NOT_EVALUATED", run.proof().formalProofStatus());

        assertEquals(CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            run.lifecycleCandidate().proofStatus());
        assertEquals(LifecycleOutcome.COMPLETED,
            run.lifecycleDecision().outcome());
        assertFalse(run.lifecycleDecision().terminal());
        assertFalse(run.lifecycleDecision().promotionAttempted());
        assertFalse(run.lifecycleDecision().publicationAttempted());
        assertTrue(run.lifecycleDecision().blockers().isEmpty());

        assertEquals(6L, run.stageLedger()
            .receipt(EvidenceStage.VALIDATION).executed());
        assertEquals(77L, run.stageLedger()
            .receipt(EvidenceStage.COUNTEREXAMPLE_SEARCH).executed());
        assertEquals(7L, run.stageLedger()
            .receipt(EvidenceStage.PROJECT_NOVELTY).executed());
        assertEquals(1L, run.stageLedger()
            .receipt(EvidenceStage.PROOF).executed());
        assertEquals(1L, run.stageLedger()
            .receipt(EvidenceStage.LIFECYCLE_HANDOFF).executed());
        run.stageLedger().receipts().forEach(receipt -> assertEquals(
            receipt.configured(),
            receipt.executed() + receipt.skipped() + receipt.remaining()));

        assertFalse(run.targetProvided());
        assertFalse(run.lifecycleRunIsMathematicalEvidence());
        assertEquals("NOT_EVALUATED", run.promotionStatus());
        assertEquals("NOT_EVALUATED", run.publicEvidenceStatus());
        String combined = run.validationJson()
            + run.counterexampleJson()
            + run.noveltyJson()
            + run.proof().toCanonicalJson()
            + run.lifecycleCandidateJson()
            + run.lifecycleDecision().toCanonicalJson()
            + run.stageLedger().toCanonicalJson()
            + run.toCanonicalJson();
        assertTrue(combined.contains("\"targetProvided\":false"));
        assertFalse(combined.contains("targetExpression"));
        assertFalse(combined.contains("expectedAnswer"));

        Path output = Path.of(
            "build", "reports", "autopilot-production-lifecycle");
        runner.write(output, run);
        for (String file : List.of(
                "production-mining-run.json",
                "validation-report.json",
                "counterexample-report.json",
                "project-novelty-report.json",
                "proof-report.json",
                "proof-obligation.json",
                "lifecycle-candidate.json",
                "lifecycle-decision.json",
                "stage-resource-ledger.json",
                "production-lifecycle-run.json")) {
            Path artifact = output.resolve(file);
            assertTrue(Files.isRegularFile(artifact), file);
            assertTrue(Files.size(artifact) > 0L, file);
        }
    }

    @Test
    void downstreamSemanticEvidenceIsStableAcrossGenerationParallelism() {
        assertEquals(sequential.mining().contentHash(), parallel.mining().contentHash());
        assertEquals(sequential.validationHash(), parallel.validationHash());
        assertEquals(sequential.counterexampleHash(), parallel.counterexampleHash());
        assertEquals(sequential.noveltyHash(), parallel.noveltyHash());
        assertEquals(sequential.proof().evidenceHash(), parallel.proof().evidenceHash());
        assertEquals(
            sequential.proof().obligation().obligationHash(),
            parallel.proof().obligation().obligationHash());
        assertEquals(
            sequential.lifecycleCandidateHash(),
            parallel.lifecycleCandidateHash());
        assertEquals(
            sequential.lifecycleDecision().contentHash(),
            parallel.lifecycleDecision().contentHash());
        assertEquals(
            sequential.stageLedger().contentHash(),
            parallel.stageLedger().contentHash());
        assertEquals(sequential.contentHash(), parallel.contentHash());
    }
}
