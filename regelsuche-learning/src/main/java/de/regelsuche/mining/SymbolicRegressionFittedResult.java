package de.regelsuche.mining;

import java.util.List;

/** Evidence-only result fitted by a symbolic-regression backend. */
public record SymbolicRegressionFittedResult(
    String templateName,
    String expression,
    List<SymbolicRegressionSample> supportingSamples
) {
    public SymbolicRegressionFittedResult {
        if (templateName == null || templateName.isBlank()) {
            throw new IllegalArgumentException("templateName must not be blank");
        }
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression must not be blank");
        }
        supportingSamples = supportingSamples == null ? List.of() : List.copyOf(supportingSamples);
    }
}
