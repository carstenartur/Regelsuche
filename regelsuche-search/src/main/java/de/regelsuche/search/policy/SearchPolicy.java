package de.regelsuche.search.policy;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.search.learning.TransformationDescriptor;
import de.regelsuche.transform.Transformation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Scores already-applicable transformations without changing rule applicability.
 * Lower priorities are explored first; deterministic search tie-breakers remain
 * authoritative after the policy score.
 */
public interface SearchPolicy {
    String id();

    PolicyDecision score(PolicyContext context, Transformation transformation);

    /** Static reference policy matching the pre-learning target-distance ordering. */
    static SearchPolicy staticPolicy() {
        return StaticPolicy.INSTANCE;
    }

    enum StaticPolicy implements SearchPolicy {
        INSTANCE;

        static final String ID = "static-target-distance/v1";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public PolicyDecision score(PolicyContext context, Transformation transformation) {
            return PolicyDecision.staticDecision(context.targetDistance(), context.targeted());
        }
    }

    record PolicyContext(
        String parentExpression,
        int targetDistance,
        boolean targeted,
        ExpressionCanonicalizer canonicalizer,
        TransformationDescriptor transformationDescriptor
    ) {
        public PolicyContext(
            String parentExpression,
            int targetDistance,
            boolean targeted,
            ExpressionCanonicalizer canonicalizer
        ) {
            this(parentExpression, targetDistance, targeted, canonicalizer, null);
        }

        public PolicyContext {
            parentExpression = parentExpression == null ? "" : parentExpression;
            if (targetDistance < 0 && targeted) {
                throw new IllegalArgumentException("targetDistance must be non-negative for targeted search");
            }
            Objects.requireNonNull(canonicalizer, "canonicalizer");
        }
    }

    record PolicyDecision(
        String policyId,
        int priority,
        int confidencePermille,
        boolean fallback,
        Map<String, Integer> contributions,
        String explanation
    ) {
        public PolicyDecision {
            if (policyId == null || policyId.isBlank()) {
                throw new IllegalArgumentException("policyId must not be blank");
            }
            if (confidencePermille < 0 || confidencePermille > 1000) {
                throw new IllegalArgumentException("confidencePermille must be in 0..1000");
            }
            Map<String, Integer> sorted = new LinkedHashMap<>();
            if (contributions != null) {
                contributions.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
            }
            contributions = Collections.unmodifiableMap(sorted);
            explanation = explanation == null ? "" : explanation;
        }

        public static PolicyDecision staticDecision(int targetDistance, boolean targeted) {
            int priority = targeted ? targetDistance : 0;
            return new PolicyDecision(
                StaticPolicy.ID,
                priority,
                1000,
                false,
                Map.of("targetDistance", priority),
                targeted ? "static target-distance ordering" : "static deterministic ordering");
        }
    }
}
