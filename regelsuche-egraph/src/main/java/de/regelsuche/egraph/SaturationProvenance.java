package de.regelsuche.egraph;

import de.regelsuche.ast.Expr;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import java.util.Objects;

/**
 * Local rewrite witnesses, not an end-to-end congruence explanation.
 * nativeWitnessExtractions counts actual binding extraction attempts, including failed ones.
 */
public record SaturationProvenance(List<Witness> witnesses, long droppedWitnesses,
        long nativeWitnessExtractions) {
    public static final int MAX_WITNESSES = 256;
    public SaturationProvenance { witnesses = List.copyOf(witnesses); }
    /** Source-compatible constructor for callers without extraction work accounting. */
    public SaturationProvenance(List<Witness> witnesses, long droppedWitnesses) {
        this(witnesses, droppedWitnesses, 0);
    }
    public static SaturationProvenance empty() { return new SaturationProvenance(List.of(), 0); }
    public boolean hasExtractionProof() { return false; }
    public String explanationStatus() { return "LOCAL_WITNESSES_ONLY; CONGRUENCE_EXTRACTION_PROOF_UNAVAILABLE"; }

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
