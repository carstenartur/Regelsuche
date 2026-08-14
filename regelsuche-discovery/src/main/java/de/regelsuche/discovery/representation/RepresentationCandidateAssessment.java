package de.regelsuche.discovery.representation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

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
    String compressionStatus,
    List<KnownStructureMatch> sourceStructureMatches,
    List<KnownStructureMatch> candidateStructureMatches,
    List<KnownStructureMatch> newlyExposedStructureMatches,
    List<KnownStructureConsequenceUnlock> newlyUnlockedConsequences,
    List<String> candidateTypes,
    List<String> introducedVariableSymbols,
    List<String> introducedFunctionSymbols,
    List<String> warnings,
    boolean materialRepresentationGain,
    boolean claimEligible
) {
    public static final String COMPRESSION_MATERIAL_MULTI_DIMENSIONAL =
        "MATERIAL_MULTI_DIMENSIONAL";
    public static final String COMPRESSION_NON_MATERIAL = "NON_MATERIAL";
    public static final String COMPRESSION_BLOCKED_BY_INTRODUCED_SYMBOLS =
        "BLOCKED_BY_INTRODUCED_SYMBOLS";
    public static final String COMPRESSION_BLOCKED_BY_STRUCTURAL_REGRESSION =
        "BLOCKED_BY_STRUCTURAL_REGRESSION";

    public static final String TYPE_WHOLE_EXPRESSION_COMPRESSION =
        "WHOLE_EXPRESSION_COMPRESSION";
    public static final String TYPE_SUBEXPRESSION_COMPRESSION =
        "SUBEXPRESSION_COMPRESSION";
    public static final String TYPE_KNOWN_WHOLE_FORM_BRIDGE =
        "KNOWN_WHOLE_FORM_BRIDGE";
    public static final String TYPE_KNOWN_SUBFORM_BRIDGE =
        "KNOWN_SUBFORM_BRIDGE";
    public static final String TYPE_DOWNSTREAM_CAPABILITY_BRIDGE =
        "DOWNSTREAM_CAPABILITY_BRIDGE";
    public static final String TYPE_REPEATED_STRUCTURE_EXTRACTION =
        "REPEATED_STRUCTURE_EXTRACTION";
    public static final String TYPE_REUSABLE_PARAMETRIC_BRIDGE =
        "REUSABLE_PARAMETRIC_BRIDGE";
    public static final String TYPE_NO_MATERIAL_REPRESENTATION_GAIN =
        "NO_MATERIAL_REPRESENTATION_GAIN";

    public static final String WARNING_INTRODUCED_VARIABLE_SYMBOLS =
        "INTRODUCED_VARIABLE_SYMBOLS";
    public static final String WARNING_INTRODUCED_FUNCTION_SYMBOLS =
        "INTRODUCED_FUNCTION_SYMBOLS";
    public static final String WARNING_STRUCTURAL_COMPRESSION_REGRESSION =
        "STRUCTURAL_COMPRESSION_REGRESSION";
    public static final String WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY =
        "KNOWN_FORM_WITHOUT_NEW_CAPABILITY";
    public static final String WARNING_UNSATISFIED_KNOWN_STRUCTURE_ASSUMPTIONS =
        "UNSATISFIED_KNOWN_STRUCTURE_ASSUMPTIONS";
    public static final String WARNING_VALIDATION_BELOW_SYMBOLIC_CONFIRMATION =
        "VALIDATION_BELOW_SYMBOLIC_CONFIRMATION";

    private static final Set<String> COMPRESSION_STATUSES = Set.of(
        COMPRESSION_MATERIAL_MULTI_DIMENSIONAL,
        COMPRESSION_NON_MATERIAL,
        COMPRESSION_BLOCKED_BY_INTRODUCED_SYMBOLS,
        COMPRESSION_BLOCKED_BY_STRUCTURAL_REGRESSION
    );
    private static final Set<String> CANDIDATE_TYPES = Set.of(
        TYPE_WHOLE_EXPRESSION_COMPRESSION,
        TYPE_SUBEXPRESSION_COMPRESSION,
        TYPE_KNOWN_WHOLE_FORM_BRIDGE,
        TYPE_KNOWN_SUBFORM_BRIDGE,
        TYPE_DOWNSTREAM_CAPABILITY_BRIDGE,
        TYPE_REPEATED_STRUCTURE_EXTRACTION,
        TYPE_REUSABLE_PARAMETRIC_BRIDGE,
        TYPE_NO_MATERIAL_REPRESENTATION_GAIN
    );
    private static final Set<String> WARNINGS = Set.of(
        WARNING_INTRODUCED_VARIABLE_SYMBOLS,
        WARNING_INTRODUCED_FUNCTION_SYMBOLS,
        WARNING_STRUCTURAL_COMPRESSION_REGRESSION,
        WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY,
        WARNING_UNSATISFIED_KNOWN_STRUCTURE_ASSUMPTIONS,
        WARNING_VALIDATION_BELOW_SYMBOLIC_CONFIRMATION
    );

    public RepresentationCandidateAssessment {
        proposal = Objects.requireNonNull(proposal, "proposal");
        knownStructureCatalogHash = requireText(
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
        compressionStatus = vocabulary(
            compressionStatus, COMPRESSION_STATUSES, "compressionStatus");
        sourceStructureMatches = immutable(
            sourceStructureMatches, "sourceStructureMatches");
        candidateStructureMatches = immutable(
            candidateStructureMatches, "candidateStructureMatches");
        newlyExposedStructureMatches = immutable(
            newlyExposedStructureMatches, "newlyExposedStructureMatches");
        newlyUnlockedConsequences = immutable(
            newlyUnlockedConsequences, "newlyUnlockedConsequences");
        candidateTypes = vocabulary(
            candidateTypes, CANDIDATE_TYPES, "candidateTypes", false);
        introducedVariableSymbols = immutable(
            introducedVariableSymbols, "introducedVariableSymbols");
        introducedFunctionSymbols = immutable(
            introducedFunctionSymbols, "introducedFunctionSymbols");
        warnings = vocabulary(warnings, WARNINGS, "warnings", true);
        if (claimEligible && !materialRepresentationGain) {
            throw new IllegalArgumentException(
                "claim eligibility requires material representation gain");
        }
    }

    private static <T> List<T> immutable(List<T> values, String field) {
        return List.copyOf(Objects.requireNonNull(values, field));
    }

    private static String vocabulary(
        String value,
        Set<String> allowed,
        String field
    ) {
        String normalized = requireText(value, field);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException("unsupported " + field + ": " + value);
        }
        return normalized;
    }

    private static List<String> vocabulary(
        List<String> values,
        Set<String> allowed,
        String field,
        boolean emptyAllowed
    ) {
        List<String> copy = immutable(values, field);
        if ((!emptyAllowed && copy.isEmpty())
                || !allowed.containsAll(copy)
                || new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException("invalid " + field + ": " + copy);
        }
        return copy;
    }

    static List<String> sortedUnique(List<String> values, String field) {
        Objects.requireNonNull(values, field);
        TreeSet<String> normalized = new TreeSet<>();
        for (String value : values) {
            normalized.add(requireText(value, field + " entry"));
        }
        return List.copyOf(normalized);
    }

    static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                field + " must not contain control characters");
        }
        return normalized;
    }
}
