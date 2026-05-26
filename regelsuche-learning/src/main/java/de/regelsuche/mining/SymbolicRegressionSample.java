package de.regelsuche.mining;

/** Numeric sample consumed by symbolic-regression backends. */
public record SymbolicRegressionSample(String pathId, double x, double y) {
    public SymbolicRegressionSample {
        if (pathId == null || pathId.isBlank()) {
            throw new IllegalArgumentException("pathId must not be blank");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("sample values must be finite");
        }
    }
}
