package de.regelsuche.sdk.optimization;

import java.util.Map;

public sealed interface VerificationResult {
    record Verified(VerificationEvidence evidence, RuntimeObligations obligations) implements VerificationResult {}
    record Refuted(String diagnostic, Map<String, Object> counterexample) implements VerificationResult {
        public Refuted { counterexample = Map.copyOf(counterexample); }
    }
    record Inconclusive(String diagnostic) implements VerificationResult {}
    record Unsupported(String diagnostic) implements VerificationResult {}
    record Cancelled(String diagnostic) implements VerificationResult {}
    record BudgetExceeded(String diagnostic, long work) implements VerificationResult {}
}
