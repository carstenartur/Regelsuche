package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.experiments.autopilot.AutonomousCandidateLifecycleV2.LifecycleOutcome;
import de.regelsuche.experiments.autopilot.AutonomousCandidateLifecycleV2.StageDisposition;
import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.mining.HypothesisCandidate.ExpressionPair;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyReport;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyStatus;
import de.regelsuche.mining.OpenTargetConjectureProofGate;
import de.regelsuche.mining.OpenTargetConjectureProofGate.EligibilityStatus;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofReport;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofStatus;
import de.regelsuche.validation.CandidateProofStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousCandidateLifecycleV2Test {
    private static final String CANDIDATE_ID = "open-target-candidate-336";
    private static final String BRANCH_ID = "campaign-336/candidate-open-target";

    @Test
    void mapsExactAndAlphaDuplicatesToTerminalDuplicateOutcomes() {
        for (NoveltyStatus status : List.of(
                NoveltyStatus.EXACT_DUPLICATE,
                NoveltyStatus.ALPHA_EQUIVALENT_DUPLICATE)) {
            var decision = AutonomousCandidateLifecycleV2.decide(
                BRANCH_ID, novelty(status), null, null);

            assertEquals(LifecycleOutcome.DUPLICATE, decision.outcome());
            assertTrue(decision.terminal());
            assertEquals(StageDisposition.NOT_RUN_TERMINAL,
                decision.proofDisposition());
            assertEquals(StageDisposition.NOT_RUN_TERMINAL,
                decision.lifecycleHandoffDisposition());
            assertFalse(decision.promotionAttempted());
            assertFalse(decision.publicationAttempted());
        }
    }

    @Test
    void preservesInconclusiveNoveltyAndProofAsIncomplete() {
        var noveltyIncomplete = AutonomousCandidateLifecycleV2.decide(
            BRANCH_ID,
            novelty(NoveltyStatus.INCONCLUSIVE_UNPARSEABLE),
            null,
            null);
        var proofIncomplete = AutonomousCandidateLifecycleV2.decide(
            BRANCH_ID,
            novelty(NoveltyStatus.NOVEL_WITHIN_PROJECT),
            proof(ProofStatus.INCONCLUSIVE),
            null);

        assertEquals(LifecycleOutcome.INCOMPLETE, noveltyIncomplete.outcome());
        assertFalse(noveltyIncomplete.terminal());
        assertEquals(StageDisposition.NOT_RUN_BLOCKED,
            noveltyIncomplete.proofDisposition());
        assertEquals(LifecycleOutcome.INCOMPLETE, proofIncomplete.outcome());
        assertFalse(proofIncomplete.terminal());
        assertEquals(StageDisposition.COMPLETED_INCONCLUSIVE,
            proofIncomplete.proofDisposition());
        assertEquals(StageDisposition.NOT_RUN_BLOCKED,
            proofIncomplete.lifecycleHandoffDisposition());
    }

    @Test
    void mapsRefutedProofToTerminalDisprovedOutcome() {
        var decision = AutonomousCandidateLifecycleV2.decide(
            BRANCH_ID,
            novelty(NoveltyStatus.NOVEL_WITHIN_PROJECT),
            proof(ProofStatus.REFUTED),
            null);

        assertEquals(LifecycleOutcome.DISPROVED, decision.outcome());
        assertTrue(decision.terminal());
        assertEquals(StageDisposition.COMPLETED_TERMINAL,
            decision.proofDisposition());
        assertEquals(StageDisposition.NOT_RUN_TERMINAL,
            decision.lifecycleHandoffDisposition());
    }

    @Test
    void requiresConservativeLifecycleHandoffAfterSymbolicVerification()
        throws IOException {
        var withoutHandoff = AutonomousCandidateLifecycleV2.decide(
            BRANCH_ID,
            novelty(NoveltyStatus.NOVEL_WITHIN_PROJECT),
            proof(ProofStatus.SYMBOLICALLY_VERIFIED),
            null);
        var completed = AutonomousCandidateLifecycleV2.decide(
            BRANCH_ID,
            novelty(NoveltyStatus.NOVEL_WITHIN_PROJECT),
            proof(ProofStatus.SYMBOLICALLY_VERIFIED),
            lifecycleCandidate());

        assertEquals(LifecycleOutcome.INCOMPLETE, withoutHandoff.outcome());
        assertEquals(StageDisposition.NOT_RUN,
            withoutHandoff.lifecycleHandoffDisposition());
        assertEquals(LifecycleOutcome.COMPLETED, completed.outcome());
        assertFalse(completed.terminal());
        assertEquals(StageDisposition.COMPLETED, completed.proofDisposition());
        assertEquals(StageDisposition.COMPLETED,
            completed.lifecycleHandoffDisposition());
        assertEquals(CANDIDATE_ID, completed.lifecycleCandidateId());
        assertEquals("NOT_EVALUATED", completed.promotionStatus());
        assertEquals("NOT_EVALUATED", completed.publicEvidenceStatus());
        assertTrue(completed.blockers().isEmpty());
        assertTrue(completed.toCanonicalJson().contains(
            "\"promotionAttempted\":false"));
        assertTrue(completed.toCanonicalJson().contains(
            "\"publicationAttempted\":false"));

        Path output = Path.of(
            "build", "reports", "autopilot-v2-dag", "lifecycle-decision.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, completed.toCanonicalJson(), StandardCharsets.UTF_8);
    }

    @Test
    void rejectsDownstreamWorkAfterTerminalOrMismatchedEvidence() {
        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousCandidateLifecycleV2.decide(
                BRANCH_ID,
                novelty(NoveltyStatus.EXACT_DUPLICATE),
                proof(ProofStatus.SYMBOLICALLY_VERIFIED),
                null));
        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousCandidateLifecycleV2.decide(
                BRANCH_ID,
                novelty(NoveltyStatus.NOVEL_WITHIN_PROJECT),
                proof(ProofStatus.REFUTED),
                lifecycleCandidate()));
        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousCandidateLifecycleV2.decide(
                BRANCH_ID,
                novelty(NoveltyStatus.NOVEL_WITHIN_PROJECT),
                proofFor("other-candidate", ProofStatus.SYMBOLICALLY_VERIFIED),
                null));
    }

    private static NoveltyReport novelty(NoveltyStatus status) {
        return new NoveltyReport(
            OpenTargetConjectureNoveltyChecker.SCHEMA,
            CANDIDATE_ID,
            status,
            status == NoveltyStatus.INCONCLUSIVE_UNPARSEABLE
                ? ""
                : AutonomousEvidenceDagV2Fixtures.hash("exact-" + status),
            status == NoveltyStatus.INCONCLUSIVE_UNPARSEABLE
                ? ""
                : AutonomousEvidenceDagV2Fixtures.hash("alpha-" + status),
            10,
            4,
            List.of(),
            "NOT_EVALUATED",
            "characterization");
    }

    private static ProofReport proof(ProofStatus status) {
        return proofFor(CANDIDATE_ID, status);
    }

    private static ProofReport proofFor(String candidateId, ProofStatus status) {
        return new ProofReport(
            OpenTargetConjectureProofGate.REPORT_SCHEMA,
            candidateId,
            status == ProofStatus.NOT_RUN
                ? EligibilityStatus.NOT_ELIGIBLE
                : EligibilityStatus.ELIGIBLE,
            status,
            null,
            "characterization-backend",
            status.name(),
            "NOT_EVALUATED",
            status == ProofStatus.SYMBOLICALLY_VERIFIED
                ? List.of()
                : List.of("characterization blocker"),
            AutonomousEvidenceDagV2Fixtures.hash(
                "proof-" + candidateId + '-' + status));
    }

    private static HypothesisCandidate lifecycleCandidate() {
        return new HypothesisCandidate(
            CANDIDATE_ID,
            "A + B",
            "B + A",
            List.of("path-a", "path-b"),
            List.of(
                new ExpressionPair("a + b", "b + a"),
                new ExpressionPair("x + y", "y + x")),
            List.of(),
            0.0,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            Boolean.FALSE,
            List.of(),
            Map.of(),
            Instant.EPOCH);
    }
}
