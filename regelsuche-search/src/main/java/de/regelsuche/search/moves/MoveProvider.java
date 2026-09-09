package de.regelsuche.search.moves;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;
import java.util.Objects;

/** Candidate formation contains no global search ordering. */
public interface MoveProvider {
    Descriptor descriptor();
    Batch candidates(MoveState state, MoveContext context);

    record Descriptor(String id, String ruleFamily, SearchMove.SourceKind sourceKind,
            SearchMove.ProofStrength proofStrength, List<String> requiredAssumptions,
            SearchMove.ValueEvidence valueEvidence, String provenanceId) {
        public Descriptor {
            if (id == null || id.isBlank() || ruleFamily == null || ruleFamily.isBlank() || provenanceId == null) {
                throw new IllegalArgumentException("provider identity is required");
            }
            Objects.requireNonNull(sourceKind, "sourceKind");
            Objects.requireNonNull(proofStrength, "proofStrength");
            Objects.requireNonNull(valueEvidence, "valueEvidence");
            requiredAssumptions = AssumptionSignature.ofExpressions(requiredAssumptions).normalizedAssumptions();
        }
    }

    /** complete is a successor-relation claim, not a mathematical proof or a claim that the goal is unreachable. */
    record Batch(List<SearchMove> moves, TransformationWorkMetrics work, boolean complete) {
        public Batch { moves = List.copyOf(moves); Objects.requireNonNull(work, "work"); }
    }
}
