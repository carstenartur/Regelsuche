package de.regelsuche.discovery.representation;

import java.util.List;
import java.util.Objects;

/** First-stage evidence for one representation candidate. */
public record RepresentationCandidateAssessment(
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
    List<KnownStructureConsequenceUnlock> newlyUnlockedConsequences,
    List<RepresentationCandidateType> candidateTypes,
    List<String> introducedVariableSymbols,
    List<String> introducedFunctionSymbols,
    List<RepresentationAssessmentWarning> warnings,
    boolean materialRepresentationGain,
    boolean claimEligible
) {
    public RepresentationCandidateAssessment {
        proposal = Objects.requireNonNull(proposal, "proposal");
        knownStructureCatalogHash = RepresentationContracts.text(
            knownStructureCatalogHash, "knownStructureCatalogHash");
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
        sourceStructureMatches = immutable(
            sourceStructureMatches, "sourceStructureMatches");
        candidateStructureMatches = immutable(
            candidateStructureMatches, "candidateStructureMatches");
        newlyExposedStructureMatches = immutable(
            newlyExposedStructureMatches, "newlyExposedStructureMatches");
        newlyUnlockedConsequences = immutable(
            newlyUnlockedConsequences, "newlyUnlockedConsequences");
        candidateTypes = immutable(candidateTypes, "candidateTypes");
        if (candidateTypes.isEmpty()) {
            throw new IllegalArgumentException("candidateTypes must not be empty");
        }
        introducedVariableSymbols = immutable(
            introducedVariableSymbols, "introducedVariableSymbols");
        introducedFunctionSymbols = immutable(
            introducedFunctionSymbols, "introducedFunctionSymbols");
        warnings = immutable(warnings, "warnings");
        if (claimEligible && !materialRepresentationGain) {
            throw new IllegalArgumentException(
                "claim eligibility requires material representation gain");
        }
    }

    private static <T> List<T> immutable(List<T> values, String field) {
        return List.copyOf(Objects.requireNonNull(values, field));
    }
}
