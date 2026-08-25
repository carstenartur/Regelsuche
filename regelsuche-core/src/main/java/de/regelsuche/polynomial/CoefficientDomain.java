package de.regelsuche.polynomial;

/**
 * Exact coefficient arithmetic identified by a stable mathematical domain ID.
 *
 * <p>Implementations must return canonical immutable values. Two domain
 * implementations that expose the same ID are required to implement the same
 * mathematical equality and arithmetic contract.</p>
 */
public interface CoefficientDomain<C> {
    String id();

    C zero();

    C one();

    C canonical(C value);

    C add(C left, C right);

    C negate(C value);

    C multiply(C left, C right);

    boolean isZero(C value);

    String canonicalText(C value);

    int bitLength(C value);

    default C subtract(C left, C right) {
        return add(left, negate(right));
    }

    default boolean isOne(C value) {
        return canonical(value).equals(one());
    }
}
