package de.regelsuche.egraph;

import de.regelsuche.ast.Expr;
import de.regelsuche.egraph.proof.ProofOutcome;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import java.util.Objects;

/**
 * Diagnostic local witnesses and optional independently checked extraction evidence.
 * nativeWitnessExtractions counts actual binding extraction attempts, including failed ones.
 */
public record SaturationProvenance(List<Witness> witnesses, long droppedWitnesses,
        long nativeWitnessExtractions, ProofOutcome proofOutcome) {
    public static final int MAX_WITNESSES = 256;
    public SaturationProvenance {
        witnesses = List.copyOf(witnesses);
        Objects.requireNonNull(proofOutcome, "proofOutcome");
    }
    /** Source-compatible constructor: local witnesses alone never prove extraction. */
    public SaturationProvenance(List<Witness> witnesses, long droppedWitnesses, long nativeWitnessExtractions) {
        this(witnesses, droppedWitnesses, nativeWitnessExtractions, ProofOutcome.notRequested());
    }
    /** Source-compatible constructor for callers without extraction work accounting. */
    public SaturationProvenance(List<Witness> witnesses, long droppedWitnesses) {
        this(witnesses, droppedWitnesses, 0);
    }
    public static SaturationProvenance empty() { return new SaturationProvenance(List.of(), 0); }
    public boolean hasExtractionProof() { return proofOutcome.complete(); }
    public String explanationStatus() {
        return hasExtractionProof() ? "CHECKED_INPUT_OUTPUT_EQUALITY"
            : "LOCAL_WITNESSES_ONLY; CONGRUENCE_EXTRACTION_PROOF_UNAVAILABLE";
    }

    public record Witness(String ruleId, Expr before, Expr after) {
        public Witness {
            Objects.requireNonNull(ruleId);
            Objects.requireNonNull(before);
            Objects.requireNonNull(after);
        }
        /** Replay against a caller-supplied trusted rule, including its guards. */
        public boolean replay(RewriteRule rule) {
            try {
                return ruleId.equals(rule.id()) && rule.isEquivalencePreservingByConstruction()
                    && rule.matches(before) && rule.assumptions(before).isEmpty()
                    && after.equals(rule.apply(before));
            } catch (RuntimeException failure) {
                return false;
            }
        }
    }
}
