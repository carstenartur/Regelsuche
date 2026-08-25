package de.regelsuche.polynomial;

import java.math.BigInteger;

/**
 * Exact coefficient arithmetic identified by a stable mathematical domain ID.
 *
 * <p>Implementations return canonical immutable values. The characteristic and
 * integer embedding are part of the domain contract because derivative,
 * finite-field and extension algorithms must not infer them from a Java value
 * type.</p>
 */
public interface CoefficientDomain<C> {
    String id();

    BigInteger characteristic();

    C fromInteger(BigInteger value);

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
