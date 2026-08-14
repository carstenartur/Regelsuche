package de.regelsuche.discovery.representation;

import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Candidate input whose mathematical validation remains an independent evidence axis. */
public record RepresentationCandidateProposal(
    String sourceExpression,
    String candidateExpression,
    RepresentationScope scope,
    ExpressionOccurrencePath occurrencePath,
    List<String> assumptions,
    CandidateProofStatus validationStatus
) {
    public RepresentationCandidateProposal {
        sourceExpression = requireText(sourceExpression, "sourceExpression");
        candidateExpression = requireText(candidateExpression, "candidateExpression");
        scope = Objects.requireNonNull(scope, "scope");
        occurrencePath = Objects.requireNonNull(occurrencePath, "occurrencePath");
        assumptions = normalizedAssumptions(assumptions);
        validationStatus = validationStatus == null
            ? CandidateProofStatus.OBSERVED
            : validationStatus;
        if (scope == RepresentationScope.WHOLE_EXPRESSION && !occurrencePath.isRoot()) {
            throw new IllegalArgumentException(
                "whole-expression proposals must use the root occurrence");
        }
        if (scope == RepresentationScope.SUBEXPRESSION && occurrencePath.isRoot()) {
            throw new IllegalArgumentException(
                "subexpression proposals must name a non-root occurrence");
        }
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
            RepresentationScope.WHOLE_EXPRESSION,
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
        return new RepresentationCandidateProposal(
            sourceExpression,
            candidateExpression,
            RepresentationScope.SUBEXPRESSION,
            occurrencePath,
            assumptions,
            validationStatus
        );
    }

    private static List<String> normalizedAssumptions(List<String> assumptions) {
        Objects.requireNonNull(assumptions, "assumptions");
        TreeSet<String> normalized = new TreeSet<>();
        for (String assumption : assumptions) {
            normalized.add(requireText(assumption, "assumption"));
        }
        return List.copyOf(normalized);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
