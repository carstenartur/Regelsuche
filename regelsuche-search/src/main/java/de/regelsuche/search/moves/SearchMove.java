package de.regelsuche.search.moves;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationProvenance;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Mathematical evidence and scheduling evidence travel together; a score grants no authority. */
public record SearchMove(Transformation transformation, SourceKind sourceKind, String ruleId, String ruleFamily,
        long generationCost, long applicationCost, long verificationCost, List<Transformation> primitiveExpansion,
        List<String> assumptions, ProofStrength proofStrength, TransformationProvenance provenance,
        Set<String> capabilityDelta, ValueEvidence valueEvidence) {
    public enum SourceKind { PRIMITIVE, HYPOTHESIS, LEARNED, PREPARATION, BRIDGE, SOLVER, EXPERT }
    public enum ProofStrength { UNVALIDATED, EMPIRICAL, REPLAYABLE, VERIFIED }

    /** Estimates are features, not certificates. Unknown path length is -1, never the observed detour length. */
    public record ValueEvidence(double confidence, double legacyAverageImprovement, long supportingObservations,
            int bestKnownPrimitiveSteps, int macroSearchDepth, boolean boundedMinimumProved, String evidenceId) {
        public static final ValueEvidence UNKNOWN = new ValueEvidence(0, 0, 0, -1, 1, false, "");
        public ValueEvidence {
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1
                    || !Double.isFinite(legacyAverageImprovement) || supportingObservations < 0
                    || bestKnownPrimitiveSteps < -1 || macroSearchDepth < 1
                    || (boundedMinimumProved && (bestKnownPrimitiveSteps < 0 || evidenceId == null || evidenceId.isBlank()))) {
                throw new IllegalArgumentException("invalid move value evidence");
            }
            evidenceId = Objects.requireNonNull(evidenceId, "evidenceId");
        }
        public int knownDepthCompression() { return Math.max(0, bestKnownPrimitiveSteps - macroSearchDepth); }
    }

    public SearchMove {
        Objects.requireNonNull(transformation, "transformation");
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(proofStrength, "proofStrength");
        Objects.requireNonNull(valueEvidence, "valueEvidence");
        if (ruleId == null || ruleId.isBlank() || ruleFamily == null || ruleFamily.isBlank()
                || generationCost < 0 || applicationCost < 0 || verificationCost < -1) {
            throw new IllegalArgumentException("invalid move identity or cost");
        }
        primitiveExpansion = List.copyOf(primitiveExpansion);
        assumptions = AssumptionSignature.ofExpressions(assumptions).normalizedAssumptions();
        if (!assumptions.equals(AssumptionSignature.ofExpressions(transformation.assumptions()).normalizedAssumptions())
                || !transformation.provenance().equals(provenance)) {
            throw new IllegalArgumentException("move evidence differs from its transformation");
        }
        capabilityDelta = Set.copyOf(capabilityDelta);
    }

    /** Generation cost describes the shared provider batch and must be charged once by its receipt. */
    public static SearchMove from(Transformation transformation, MoveProvider.Descriptor descriptor, long batchCost) {
        var expansion = descriptor.proofStrength() == ProofStrength.UNVALIDATED
                || descriptor.proofStrength() == ProofStrength.EMPIRICAL ? List.<Transformation>of() : primitiveLeaves(transformation);
        return new SearchMove(transformation, descriptor.sourceKind(),
            descriptor.sourceKind() == SourceKind.PRIMITIVE ? transformation.rule() : descriptor.id(),
            descriptor.ruleFamily().equals("*") ? transformation.rule() : descriptor.ruleFamily(),
            batchCost, transformation.executionWork().canonicalWorkUnits(), -1, expansion, transformation.assumptions(),
            descriptor.proofStrength(), transformation.provenance(), Set.of(), descriptor.valueEvidence());
    }

    public static List<Transformation> primitiveLeaves(Transformation step) {
        if (step.provenance() instanceof TransformationProvenance.Sequence sequence) {
            return sequence.steps().stream().flatMap(child -> primitiveLeaves(child).stream()).toList();
        }
        return step.exactTheoryStepCount() == 0 ? List.of(step) : List.of();
    }

    public SearchMove withCapabilityDelta(Set<String> delta) {
        return new SearchMove(transformation, sourceKind, ruleId, ruleFamily, generationCost, applicationCost, verificationCost,
            primitiveExpansion, assumptions, proofStrength, provenance, delta, valueEvidence);
    }
}
