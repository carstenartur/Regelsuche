package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.proof.ProofPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests that verify the proof-policy gate behaves according to the acceptance
 * criteria from issue #215:
 *
 * <ul>
 *   <li>{@code SCRIPT_GENERATED} never satisfies a required proof policy.</li>
 *   <li>{@code PROVER_CONFIRMED} satisfies the configured gate.</li>
 *   <li>Timeout, rejection and unavailable prover are distinct blockers.</li>
 *   <li>Modified content invalidates prior confirmation (handled by ProofCacheKey).</li>
 *   <li>Public evidence displays the exact proof status.</li>
 * </ul>
 */
class ProofPolicyGateTest {

    private final PromotionDecider decider = new PromotionDecider();
    private final PublicEvidenceGate evidenceGate = new PublicEvidenceGate();

    // -----------------------------------------------------------------------
    // PromotionDecider — PROOF_REQUIRED_FOR_PROMOTION
    // -----------------------------------------------------------------------

    @Test
    void scriptGeneratedNeverSatisfiesRequiredPromotionPolicy() {
        PromotionObservation obs = qualifyingObservation(
            ProofPolicy.PROOF_REQUIRED_FOR_PROMOTION, "SCRIPT_GENERATED");

        PromotionRecord record = decider.decide(obs);

        assertFalse(record.promotionEligible(),
            "SCRIPT_GENERATED must not satisfy PROOF_REQUIRED_FOR_PROMOTION");
        assertTrue(record.promotionBlockers().stream()
                .anyMatch(b -> b.startsWith("proof=")),
            "a proof= blocker must be present: " + record.promotionBlockers());
        assertEquals(PromotionStage.VALIDATED, record.stage(),
            "stage must be VALIDATED when proof is required but not confirmed");
    }

    @Test
    void proverConfirmedSatisfiesRequiredPromotionPolicy() {
        PromotionObservation obs = qualifyingObservation(
            ProofPolicy.PROOF_REQUIRED_FOR_PROMOTION, "PROVER_CONFIRMED");

        PromotionRecord record = decider.decide(obs);

        assertTrue(record.promotionEligible(),
            "PROVER_CONFIRMED must satisfy PROOF_REQUIRED_FOR_PROMOTION");
        assertFalse(record.promotionBlockers().stream()
                .anyMatch(b -> b.startsWith("proof=")),
            "no proof= blocker must be present");
        assertEquals(PromotionStage.PROMOTED, record.stage());
    }

    @Test
    void proverTimeoutIsDistinctBlockerUnderRequiredPolicy() {
        PromotionObservation obs = qualifyingObservation(
            ProofPolicy.PROOF_REQUIRED_FOR_PROMOTION, "PROVER_TIMEOUT");

        PromotionRecord record = decider.decide(obs);

        assertFalse(record.promotionEligible());
        assertTrue(record.promotionBlockers().contains("proof=PROVER_TIMEOUT"),
            record.promotionBlockers().toString());
    }

    @Test
    void proverNotAvailableIsDistinctBlockerUnderRequiredPolicy() {
        PromotionObservation obs = qualifyingObservation(
            ProofPolicy.PROOF_REQUIRED_FOR_PROMOTION, "PROVER_NOT_AVAILABLE");

        PromotionRecord record = decider.decide(obs);

        assertFalse(record.promotionEligible());
        assertTrue(record.promotionBlockers().contains("proof=PROVER_NOT_AVAILABLE"),
            record.promotionBlockers().toString());
    }

    @Test
    void proverFailedIsDistinctBlockerUnderRequiredPolicy() {
        PromotionObservation obs = qualifyingObservation(
            ProofPolicy.PROOF_REQUIRED_FOR_PROMOTION, "PROVER_FAILED");

        PromotionRecord record = decider.decide(obs);

        assertFalse(record.promotionEligible());
        assertTrue(record.promotionBlockers().contains("proof=PROVER_FAILED"),
            record.promotionBlockers().toString());
    }

    @Test
    void optionalPolicyAllowsPromotionWithoutProof() {
        PromotionObservation obs = qualifyingObservation(
            ProofPolicy.PROOF_OPTIONAL, "SCRIPT_GENERATED");

        PromotionRecord record = decider.decide(obs);

        assertTrue(record.promotionEligible(),
            "PROOF_OPTIONAL must allow promotion without confirmed proof");
        assertEquals(PromotionStage.PROMOTED, record.stage());
    }

    // -----------------------------------------------------------------------
    // PublicEvidenceGate — PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE
    // -----------------------------------------------------------------------

    @Test
    void scriptGeneratedNeverSatisfiesRequiredPublicEvidencePolicy() {
        PromotionRecord record = qualifyingRecord(
            ProofPolicy.PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE, "SCRIPT_GENERATED");

        PublicEvidenceGate.GateDecision decision =
            evidenceGate.evaluate(record, NoveltyStatus.NEW);

        assertFalse(decision.accepted(),
            "SCRIPT_GENERATED must not satisfy PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE");
        assertTrue(decision.rejectionReasons().stream()
                .anyMatch(r -> r.startsWith("proof=")),
            "a proof= rejection reason must be present: " + decision.rejectionReasons());
    }

    @Test
    void proverConfirmedSatisfiesPublicEvidencePolicy() {
        PromotionRecord record = qualifyingRecord(
            ProofPolicy.PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE, "PROVER_CONFIRMED");

        PublicEvidenceGate.GateDecision decision =
            evidenceGate.evaluate(record, NoveltyStatus.NEW);

        assertTrue(decision.accepted(),
            "PROVER_CONFIRMED must satisfy PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE: "
                + decision.rejectionReasons());
    }

    @Test
    void optionalPolicyAllowsPublicEvidenceWithoutProof() {
        PromotionRecord record = qualifyingRecord(
            ProofPolicy.PROOF_OPTIONAL, "SCRIPT_GENERATED");

        PublicEvidenceGate.GateDecision decision =
            evidenceGate.evaluate(record, NoveltyStatus.NEW);

        assertTrue(decision.accepted(),
            "PROOF_OPTIONAL should not block public evidence: " + decision.rejectionReasons());
    }

    @Test
    void publicEvidenceGateRecordCarriesProofStatus() {
        PromotionRecord record = qualifyingRecord(
            ProofPolicy.PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE, "PROVER_TIMEOUT");

        PublicEvidenceGate.GateDecision decision =
            evidenceGate.evaluate(record, NoveltyStatus.NEW);

        assertFalse(decision.accepted());
        assertTrue(decision.rejectionReasons().contains("proof=PROVER_TIMEOUT"),
            "exact prover status must appear in rejections: " + decision.rejectionReasons());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Builds an observation that passes all gates EXCEPT possibly the proof gate. */
    private PromotionObservation qualifyingObservation(
            ProofPolicy policy, String proverStatus) {
        return new PromotionObservation(
            "test-candidate",
            "campaign",
            "2026-01-01",
            "polynomial",
            "a + b",
            "b + a",
            true,
            "AGREE",
            "sympy: equivalent",
            "DEGRADED",
            "commutativity_intro",
            "sympy-polynomial-basic",
            List.of(),
            "commutativity of addition",
            List.of("commutativity_intro"),
            true,
            false,
            false,
            false,
            policy,
            proverStatus
        );
    }

    /** Builds a fully-promoted record that passes all gates EXCEPT possibly the proof gate. */
    private PromotionRecord qualifyingRecord(
            ProofPolicy policy, String proverStatus) {
        AblationEvidence ablation = AblationEvidence.compare(
            true, 1, 5, true, 3, 30, "test ablation");
        return new PromotionRecord(
            "test-candidate",
            "campaign",
            "2026-01-01",
            "polynomial",
            PromotionStage.PROMOTED,
            "a + b",
            "b + a",
            "AGREE",
            "sympy: equivalent",
            ablation.ablationStatus(),
            "commutativity_intro",
            "sympy-polynomial-basic",
            List.of(),
            "commutativity of addition",
            List.of("commutativity_intro"),
            true,
            List.of(),
            true,
            false,
            false,
            false,
            "",
            List.of(),
            false,
            "",
            ablation,
            policy,
            proverStatus
        );
    }
}
