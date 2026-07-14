package de.regelsuche.mining;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * One post-hoc relevance-labelled case for profile calibration or held-out evaluation.
 *
 * <p>The relevance label is carried only by the evaluator. It is never passed to
 * {@link EvidenceAwareInterestingnessAssessor} and therefore cannot affect the
 * candidate, its evidence, or its component scores.</p>
 */
public record InterestingnessCalibrationCase(
    String caseId,
    String structuralFamily,
    String structuralSignatureHash,
    Split split,
    HypothesisCandidate candidate,
    InterestingnessEvidence evidence,
    double knownRuleSimilarity,
    Set<String> domainTags,
    RelevanceLabel relevanceLabel
) {
    public InterestingnessCalibrationCase {
        requireText(caseId, "caseId");
        requireText(structuralFamily, "structuralFamily");
        if (structuralSignatureHash == null
                || !structuralSignatureHash.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("structuralSignatureHash must be SHA-256");
        }
        split = Objects.requireNonNull(split, "split");
        candidate = Objects.requireNonNull(candidate, "candidate");
        evidence = Objects.requireNonNull(evidence, "evidence");
        if (knownRuleSimilarity < 0.0
                || knownRuleSimilarity > 1.0
                || !Double.isFinite(knownRuleSimilarity)) {
            throw new IllegalArgumentException("knownRuleSimilarity must be in [0,1]");
        }
        TreeSet<String> orderedDomains = new TreeSet<>();
        if (domainTags != null) {
            domainTags.stream()
                .filter(value -> value != null && !value.isBlank())
                .forEach(orderedDomains::add);
        }
        domainTags = Collections.unmodifiableSet(orderedDomains);
        relevanceLabel = Objects.requireNonNull(relevanceLabel, "relevanceLabel");
    }

    public enum Split {
        CALIBRATION,
        TEST
    }

    public enum RelevanceLabel {
        CONTROL(0),
        LOW(1),
        MEDIUM(2),
        HIGH(3);

        private final int priority;

        RelevanceLabel(int priority) {
            this.priority = priority;
        }

        int priority() {
            return priority;
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
