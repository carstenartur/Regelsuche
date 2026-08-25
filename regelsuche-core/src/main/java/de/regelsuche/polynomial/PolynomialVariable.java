package de.regelsuche.polynomial;

/**
 * Stable mathematical variable or structural-atom identity.
 * Display syntax and source occurrence data deliberately live outside the ring.
 */
public record PolynomialVariable(String id)
        implements Comparable<PolynomialVariable> {
    public PolynomialVariable {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                "polynomial variable id must not be blank");
        }
    }

    @Override
    public int compareTo(PolynomialVariable other) {
        return id.compareTo(other.id);
    }
}
