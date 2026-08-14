package de.regelsuche.discovery.representation;

import java.util.List;
import java.util.Objects;

/** Canonical first-stage evidence for one representation candidate. */
public record RepresentationCandidateAssessment(
    String schema,
    RepresentationCandidateProposal proposal,
    String knownStructureCatalogHash,
    SemanticDescriptionMetrics wholeSourceMetrics,
    SemanticDescriptionMetrics wholeCandidateMetrics,
    SemanticDescriptionMetrics scopedSourceMetrics,
    SemanticDescriptionMetrics scopedCandidateMetrics,
    SemanticCompressionDelta wholeCompressionDelta,
    SemanticCompressionDelta scopedCompressionDelta,
    SemanticCompressionStatus compressionStatus,
    List<KnownStructureMatch> sourceStructureMatches,
    List<KnownStructureMatch> candidateStructureMatches,
    List<KnownStructureMatch> newlyExposedStructureMatches,
    List<String> newlyUnlockedConsequences,
    List<RepresentationCandidateType> candidateTypes,
    List<String> introducedVariableSymbols,
    List<String> introducedFunctionSymbols,
    List<RepresentationAssessmentWarning> warnings,
    boolean materialRepresentationGain,
    boolean claimEligible
) {
    public static final String SCHEMA_VERSION =
        "regelsuche.representation-candidate-assessment/v1";

    public RepresentationCandidateAssessment {
        if (!SCHEMA_VERSION.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported representation-candidate schema: " + schema);
        }
        proposal = Objects.requireNonNull(proposal, "proposal");
        knownStructureCatalogHash =
            requireText(knownStructureCatalogHash, "knownStructureCatalogHash");
        wholeSourceMetrics = Objects.requireNonNull(
            wholeSourceMetrics, "wholeSourceMetrics");
        wholeCandidateMetrics = Objects.requireNonNull(
            wholeCandidateMetrics, "wholeCandidateMetrics");
        scopedSourceMetrics = Objects.requireNonNull(
            scopedSourceMetrics, "scopedSourceMetrics");
        scopedCandidateMetrics = Objects.requireNonNull(
            scopedCandidateMetrics, "scopedCandidateMetrics");
        wholeCompressionDelta = Objects.requireNonNull(
            wholeCompressionDelta, "wholeCompressionDelta");
        scopedCompressionDelta = Objects.requireNonNull(
            scopedCompressionDelta, "scopedCompressionDelta");
        compressionStatus = Objects.requireNonNull(
            compressionStatus, "compressionStatus");
        sourceStructureMatches = List.copyOf(Objects.requireNonNull(
            sourceStructureMatches, "sourceStructureMatches"));
        candidateStructureMatches = List.copyOf(Objects.requireNonNull(
            candidateStructureMatches, "candidateStructureMatches"));
        newlyExposedStructureMatches = List.copyOf(Objects.requireNonNull(
            newlyExposedStructureMatches, "newlyExposedStructureMatches"));
        newlyUnlockedConsequences = List.copyOf(Objects.requireNonNull(
            newlyUnlockedConsequences, "newlyUnlockedConsequences"));
        candidateTypes = List.copyOf(Objects.requireNonNull(
            candidateTypes, "candidateTypes"));
        if (candidateTypes.isEmpty()) {
            throw new IllegalArgumentException("candidateTypes must not be empty");
        }
        introducedVariableSymbols = List.copyOf(Objects.requireNonNull(
            introducedVariableSymbols, "introducedVariableSymbols"));
        introducedFunctionSymbols = List.copyOf(Objects.requireNonNull(
            introducedFunctionSymbols, "introducedFunctionSymbols"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        if (claimEligible && !materialRepresentationGain) {
            throw new IllegalArgumentException(
                "claim eligibility requires material representation gain");
        }
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
