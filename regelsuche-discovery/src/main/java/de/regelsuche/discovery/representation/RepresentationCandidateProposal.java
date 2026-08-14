package de.regelsuche.discovery.representation;

import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import java.util.Objects;

/** Candidate input whose mathematical validation remains an independent axis. */
public record RepresentationCandidateProposal(
    String sourceExpression,
    String candidateExpression,
    ExpressionOccurrencePath occurrencePath,
    List<String> assumptions,
    CandidateProofStatus validationStatus
) {
    public RepresentationCandidateProposal {
        sourceExpression = RepresentationCandidateAssessment.requireText(
            sourceExpression, "sourceExpression");
        candidateExpression = RepresentationCandidateAssessment.requireText(
            candidateExpression, "candidateExpression");
        occurrencePath = Objects.requireNonNull(occurrencePath, "occurrencePath");
        assumptions = RepresentationCandidateAssessment.sortedUnique(
            assumptions, "assumptions");
        validationStatus = validationStatus == null
            ? CandidateProofStatus.OBSERVED
            : validationStatus;
    }

    public static RepresentationCandidateProposal whole(
        String sourceExpression,
        String candidateExpression,
        List<String> assumptions,
        CandidateProofStatus validationStatus
    ) {
        return new RepresentationCandidateProposal(
            sourceExpression,
            candidateExpression,
            ExpressionOccurrencePath.root(),
            assumptions,
            validationStatus
        );
    }

    public static RepresentationCandidateProposal subexpression(
        String sourceExpression,
        String candidateExpression,
        ExpressionOccurrencePath occurrencePath,
        List<String> assumptions,
        CandidateProofStatus validationStatus
    ) {
        if (Objects.requireNonNull(occurrencePath, "occurrencePath").isRoot()) {
            throw new IllegalArgumentException(
                "subexpression proposals must name a non-root occurrence");
        }
        return new RepresentationCandidateProposal(
            sourceExpression,
            candidateExpression,
            occurrencePath,
            assumptions,
            validationStatus
        );
    }

    public boolean wholeExpression() {
        return occurrencePath.isRoot();
    }
}
