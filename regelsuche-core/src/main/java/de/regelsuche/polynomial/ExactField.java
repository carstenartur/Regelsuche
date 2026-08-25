package de.regelsuche.polynomial;

/** Coefficient domain in which every non-zero value has an exact reciprocal. */
public interface ExactField<C> extends CoefficientDomain<C> {
    C divide(C dividend, C divisor);
}
