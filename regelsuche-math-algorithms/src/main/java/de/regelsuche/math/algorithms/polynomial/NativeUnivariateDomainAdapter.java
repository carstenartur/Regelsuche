package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;

interface NativeUnivariateDomainAdapter<C> {
    String domainId();

    UnivariateContentResult normalize(
        FactorizationRequest<C> request,
        UnivariateContentPolicy policy,
        PolynomialWorkBudget work);

    C targetUnit(ExactRational unit);

    SparsePolynomial<C> targetFactor(
        SparsePolynomial<BigInteger> factor,
        PolynomialRing<C> targetRing);
}
