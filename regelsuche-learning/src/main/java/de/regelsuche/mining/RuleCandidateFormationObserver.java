package de.regelsuche.mining;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Receives one fully formed rule candidate together with the immutable
 * evidence that led to that formation.
 *
 * <p>The callback is deliberately positioned after candidate formation. It
 * must not mutate, promote or replace the candidate. A configured observer is
 * part of the fail-closed formation lifecycle: if it rejects the evidence, the
 * mining call does not pretend that post-formation processing succeeded.</p>
 */
@FunctionalInterface
public interface RuleCandidateFormationObserver {
    void onCandidateFormed(RuleCandidate candidate, Evidence evidence);

    /** Observer used by the backward-compatible default miner constructors. */
    static RuleCandidateFormationObserver none() {
        return (candidate, evidence) -> { };
    }

    /**
     * Formation evidence retained independently from any later theory
     * classification, cache decision, novelty decision or promotion policy.
     */
    record Evidence(
        List<String> primitiveRuleIds,
        List<String> sourceProvenance,
        List<String> assumptions,
        List<String> validationEvidence
    ) {
        public Evidence {
            primitiveRuleIds = normalized(
                primitiveRuleIds,
                "primitiveRuleIds");
            sourceProvenance = normalized(
                sourceProvenance,
                "sourceProvenance");
            assumptions = normalized(assumptions, "assumptions");
            validationEvidence = normalized(
                validationEvidence,
                "validationEvidence");
        }

        public static Evidence fromPaths(
            List<SuccessfulTransformationPath> paths
        ) {
            Objects.requireNonNull(paths, "paths");
            List<String> primitiveRuleIds = new ArrayList<>();
            List<String> sourceProvenance = new ArrayList<>();
            List<String> assumptions = new ArrayList<>();
            List<String> validationEvidence = new ArrayList<>();
            for (SuccessfulTransformationPath path : paths) {
                SuccessfulTransformationPath checked =
                    Objects.requireNonNull(path, "path");
                primitiveRuleIds.addAll(checked.rules());
                sourceProvenance.add(checked.id());
                assumptions.addAll(checked.assumptions());
                if (checked.equivalenceEvidence() != null
                        && !checked.equivalenceEvidence().isBlank()) {
                    validationEvidence.add(
                        checked.equivalenceEvidence());
                }
            }
            return new Evidence(
                primitiveRuleIds,
                sourceProvenance,
                assumptions,
                validationEvidence);
        }

        public Evidence merge(Evidence other) {
            Objects.requireNonNull(other, "other");
            return new Evidence(
                concatenated(primitiveRuleIds, other.primitiveRuleIds),
                concatenated(sourceProvenance, other.sourceProvenance),
                concatenated(assumptions, other.assumptions),
                concatenated(validationEvidence, other.validationEvidence));
        }

        private static List<String> concatenated(
            List<String> first,
            List<String> second
        ) {
            List<String> result = new ArrayList<>(
                first.size() + second.size());
            result.addAll(first);
            result.addAll(second);
            return result;
        }

        private static List<String> normalized(
            List<String> values,
            String name
        ) {
            Objects.requireNonNull(values, name);
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(
                        name + " must not contain blank entries");
                }
                normalized.add(value);
            }
            return List.copyOf(normalized);
        }
    }
}
