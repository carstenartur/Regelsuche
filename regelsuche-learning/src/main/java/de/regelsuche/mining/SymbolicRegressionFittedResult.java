package de.regelsuche.mining;

import java.util.List;

/** Evidence-only result fitted by a symbolic-regression backend. */
public record SymbolicRegressionFittedResult(
    String templateName,
    String expression,
    List<SymbolicRegressionSample> supportingSamples,
    double maxResidual,
    double confidence
) {
    public SymbolicRegressionFittedResult(
        String templateName,
        String expression,
        List<SymbolicRegressionSample> supportingSamples
    ) {
        this(templateName, expression, supportingSamples, 0.0, 1.0);
    }

    public SymbolicRegressionFittedResult {
        if (templateName == null || templateName.isBlank()) {
            throw new IllegalArgumentException("templateName must not be blank");
        }
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression must not be blank");
        }
        supportingSamples = supportingSamples == null ? List.of() : List.copyOf(supportingSamples);
        if (!Double.isFinite(maxResidual) || maxResidual < 0.0) {
            maxResidual = Double.POSITIVE_INFINITY;
        }
        if (!Double.isFinite(confidence)) {
            confidence = 0.0;
        }
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }
}
