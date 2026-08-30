package de.regelsuche.benchmark.polynomial;

/** One frozen cumulative canonical-work checkpoint. */
public record PolynomialTheoryUtilityExecutionCheckpoint(
    String checkpointId,
    int ordinal,
    int numerator,
    int denominator
) {
    public PolynomialTheoryUtilityExecutionCheckpoint {
        if (checkpointId == null
                || checkpointId.isBlank()
                || ordinal < 1
                || numerator < 1
                || denominator < 1
                || numerator > denominator) {
            throw new IllegalArgumentException("invalid checkpoint");
        }
    }
}
