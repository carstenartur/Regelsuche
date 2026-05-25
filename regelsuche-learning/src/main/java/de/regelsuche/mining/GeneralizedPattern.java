package de.regelsuche.mining;

import java.util.List;
import java.util.Map;

public record GeneralizedPattern(
    String leftPattern,
    String rightPattern,
    Map<String, List<Integer>> placeholderValues,
    List<String> parameterRelations,
    Map<String, List<String>> expressionPlaceholderValues
) {
    public GeneralizedPattern {
        placeholderValues = Map.copyOf(placeholderValues);
        parameterRelations = List.copyOf(parameterRelations);
        expressionPlaceholderValues = expressionPlaceholderValues == null
            ? Map.of()
            : Map.copyOf(expressionPlaceholderValues);
    }

    /** Backwards-compatible constructor for callers that don't need expression placeholders. */
    public GeneralizedPattern(
        String leftPattern,
        String rightPattern,
        Map<String, List<Integer>> placeholderValues,
        List<String> parameterRelations
    ) {
        this(leftPattern, rightPattern, placeholderValues, parameterRelations, Map.of());
    }
}
