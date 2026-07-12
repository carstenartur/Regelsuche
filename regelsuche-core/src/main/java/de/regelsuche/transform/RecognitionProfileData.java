package de.regelsuche.transform;

import de.regelsuche.ast.BinaryOperator;
import java.util.List;

/** Stable, serializer-friendly representation stored with learned rules. */
public record RecognitionProfileData(
    String schema,
    List<String> associativeOperators,
    List<String> commutativeOperators,
    boolean inferAlgebraicBindings,
    List<String> recognitionRuleIds,
    int maxEquivalenceDepth
) {
    public static final String SCHEMA = "regelsuche.recognition-profile/v1";

    public RecognitionProfileData {
        schema = schema == null || schema.isBlank() ? SCHEMA : schema;
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported recognition profile schema: " + schema);
        }
        associativeOperators = associativeOperators == null ? List.of() : List.copyOf(associativeOperators);
        commutativeOperators = commutativeOperators == null ? List.of() : List.copyOf(commutativeOperators);
        recognitionRuleIds = recognitionRuleIds == null ? List.of() : List.copyOf(recognitionRuleIds);
        if (maxEquivalenceDepth < 0) {
            throw new IllegalArgumentException("maxEquivalenceDepth must not be negative");
        }
    }

    public static RecognitionProfileData from(RecognitionProfile profile) {
        return new RecognitionProfileData(
            SCHEMA,
            profile.associativeOperators().stream().map(Enum::name).sorted().toList(),
            profile.commutativeOperators().stream().map(Enum::name).sorted().toList(),
            profile.inferAlgebraicBindings(),
            profile.recognitionRuleIds().stream().sorted().toList(),
            profile.maxEquivalenceDepth()
        );
    }

    public RecognitionProfile toProfile() {
        return new RecognitionProfile(
            associativeOperators.stream().map(BinaryOperator::valueOf).collect(java.util.stream.Collectors.toSet()),
            commutativeOperators.stream().map(BinaryOperator::valueOf).collect(java.util.stream.Collectors.toSet()),
            inferAlgebraicBindings,
            java.util.Set.copyOf(recognitionRuleIds),
            maxEquivalenceDepth
        );
    }
}
