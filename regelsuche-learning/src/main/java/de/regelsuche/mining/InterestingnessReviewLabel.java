package de.regelsuche.mining;

import de.regelsuche.mining.InterestingnessCalibrationCase.RelevanceLabel;
import java.util.Objects;

/**
 * One independent, post-hoc relevance judgment for an already-scored candidate.
 *
 * <p>Only a salted reviewer hash is retained. The judgment is never supplied to
 * candidate formation or {@link EvidenceAwareInterestingnessAssessor}.</p>
 */
public record InterestingnessReviewLabel(
    String reviewId,
    String reviewRoundId,
    String caseId,
    String reviewerIdHash,
    Source source,
    RelevanceLabel relevanceLabel,
    int confidencePermille,
    RationaleCode rationaleCode,
    boolean blindToAssessment
) {
    public InterestingnessReviewLabel {
        requireText(reviewId, "reviewId");
        requireText(reviewRoundId, "reviewRoundId");
        requireText(caseId, "caseId");
        if (reviewerIdHash == null
                || !reviewerIdHash.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("reviewerIdHash must be SHA-256");
        }
        source = Objects.requireNonNull(source, "source");
        relevanceLabel = Objects.requireNonNull(relevanceLabel, "relevanceLabel");
        if (confidencePermille < 0 || confidencePermille > 1000) {
            throw new IllegalArgumentException("confidencePermille must be in [0,1000]");
        }
        rationaleCode = Objects.requireNonNull(rationaleCode, "rationaleCode");
    }

    public enum Source {
        EXPERT_REVIEW,
        CONTROL_ASSIGNMENT,
        TEST_FIXTURE
    }

    public enum RationaleCode {
        STRUCTURAL_DEPTH,
        CROSS_FAMILY_TRANSFER,
        SEARCH_REUSE,
        ASSUMPTION_SIMPLICITY,
        TRIVIAL_CONTROL,
        MIXED_OR_OTHER
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
