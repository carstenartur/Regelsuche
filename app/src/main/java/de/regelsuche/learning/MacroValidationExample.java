package de.regelsuche.learning;

/** One generated substitution used as promotion evidence for a learned macro. */
public record MacroValidationExample(
    String substitution,
    String instantiatedLeft,
    String instantiatedRight,
    boolean equivalent
) {
    public MacroValidationExample {
        substitution = substitution == null ? "" : substitution;
        instantiatedLeft = instantiatedLeft == null ? "" : instantiatedLeft;
        instantiatedRight = instantiatedRight == null ? "" : instantiatedRight;
    }
}
