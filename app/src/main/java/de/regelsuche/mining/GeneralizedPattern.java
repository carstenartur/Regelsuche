package de.regelsuche.mining;

import java.util.List;
import java.util.Map;

public record GeneralizedPattern(
    String leftPattern,
    String rightPattern,
    Map<String, List<Integer>> placeholderValues,
    List<String> parameterRelations
) {
    public GeneralizedPattern {
        placeholderValues = Map.copyOf(placeholderValues);
        parameterRelations = List.copyOf(parameterRelations);
    }
}
