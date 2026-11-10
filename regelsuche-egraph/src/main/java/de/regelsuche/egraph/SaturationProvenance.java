package de.regelsuche.egraph;

import de.regelsuche.ast.Expr;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import java.util.Objects;

/** Local rewrite witnesses. These do not explain unions or congruence closure end to end. */
public record SaturationProvenance(List<Witness> witnesses, long droppedWitnesses) {
    public static final int MAX_WITNESSES = 256;
    public SaturationProvenance { witnesses = List.copyOf(witnesses); }
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
