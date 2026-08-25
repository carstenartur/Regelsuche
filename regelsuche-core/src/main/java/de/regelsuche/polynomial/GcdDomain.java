package de.regelsuche.polynomial;

/** Exact integral domain with gcd and checked exact division. */
public interface GcdDomain<C> extends CoefficientDomain<C> {
    C gcd(C left, C right);

    C divideExact(C dividend, C divisor);
}
