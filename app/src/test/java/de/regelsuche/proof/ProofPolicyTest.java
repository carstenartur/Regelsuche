package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProofPolicyTest {

    @Test
    void proofOptionalAcceptsAnyStatus() {
        ProofPolicy policy = ProofPolicy.PROOF_OPTIONAL;
        assertTrue(policy.satisfiedBy("PROVER_CONFIRMED"));
        assertTrue(policy.satisfiedBy("SCRIPT_GENERATED"));
        assertTrue(policy.satisfiedBy("PROVER_NOT_AVAILABLE"));
        assertTrue(policy.satisfiedBy("PROVER_TIMEOUT"));
        assertTrue(policy.satisfiedBy("PROVER_FAILED"));
        assertTrue(policy.satisfiedBy(null));
        assertTrue(policy.satisfiedBy(""));
    }

    @Test
    void proofRequiredForPromotionOnlyAcceptsConfirmed() {
        ProofPolicy policy = ProofPolicy.PROOF_REQUIRED_FOR_PROMOTION;
        assertTrue(policy.satisfiedBy("PROVER_CONFIRMED"));
        assertFalse(policy.satisfiedBy("SCRIPT_GENERATED"));
        assertFalse(policy.satisfiedBy("PROVER_NOT_AVAILABLE"));
        assertFalse(policy.satisfiedBy("PROVER_TIMEOUT"));
        assertFalse(policy.satisfiedBy("PROVER_FAILED"));
        assertFalse(policy.satisfiedBy(null));
        assertFalse(policy.satisfiedBy(""));
    }

    @Test
    void proofRequiredForPublicEvidenceOnlyAcceptsConfirmed() {
        ProofPolicy policy = ProofPolicy.PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE;
        assertTrue(policy.satisfiedBy("PROVER_CONFIRMED"));
        assertFalse(policy.satisfiedBy("SCRIPT_GENERATED"));
        assertFalse(policy.satisfiedBy("PROVER_NOT_AVAILABLE"));
        assertFalse(policy.satisfiedBy("PROVER_TIMEOUT"));
        assertFalse(policy.satisfiedBy("PROVER_FAILED"));
        assertFalse(policy.satisfiedBy(null));
    }

    @Test
    void requiresConfirmedProofForPromotionFlags() {
        assertFalse(ProofPolicy.PROOF_OPTIONAL.requiresConfirmedProofForPromotion());
        assertTrue(ProofPolicy.PROOF_REQUIRED_FOR_PROMOTION.requiresConfirmedProofForPromotion());
        assertTrue(ProofPolicy.PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE.requiresConfirmedProofForPromotion());
    }

    @Test
    void requiresConfirmedProofForPublicEvidenceFlags() {
        assertFalse(ProofPolicy.PROOF_OPTIONAL.requiresConfirmedProofForPublicEvidence());
        assertFalse(ProofPolicy.PROOF_REQUIRED_FOR_PROMOTION.requiresConfirmedProofForPublicEvidence());
        assertTrue(ProofPolicy.PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE.requiresConfirmedProofForPublicEvidence());
    }

    @Test
    void normaliseExecutionStatusReturnsSCRIPT_GENERATEDForBlank() {
        assertEquals("SCRIPT_GENERATED", ProofPolicy.normaliseExecutionStatus(null));
        assertEquals("SCRIPT_GENERATED", ProofPolicy.normaliseExecutionStatus(""));
        assertEquals("SCRIPT_GENERATED", ProofPolicy.normaliseExecutionStatus("  "));
        assertEquals("PROVER_CONFIRMED", ProofPolicy.normaliseExecutionStatus("PROVER_CONFIRMED"));
        assertEquals("PROVER_TIMEOUT", ProofPolicy.normaliseExecutionStatus("  PROVER_TIMEOUT  "));
    }
}
