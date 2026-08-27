package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;

enum NativeIntegerDomainAdapter
        implements NativeUnivariateDomainAdapter<BigInteger> {
    INSTANCE;

    @Override
    public String domainId() {
        return BigIntegerDomain.DOMAIN_ID;
    }

    @Override
    public UnivariateContentResult normalize(
        FactorizationRequest<BigInteger> request,
        UnivariateContentPolicy policy,
        PolynomialWorkBudget work
    ) {
        return UnivariateContentNormalization.normalizeInteger(
            request,
            policy,
            work);
    }

    @Override
    public BigInteger targetUnit(ExactRational unit) {
        if (!unit.isInteger()) {
            throw new ArithmeticException(
                "integer factorization produced rational unit");
        }
        return unit.numerator();
    }

    @Override
    public SparsePolynomial<BigInteger> targetFactor(
        SparsePolynomial<BigInteger> factor,
        PolynomialRing<BigInteger> targetRing
    ) {
        return factor.ring().equals(targetRing)
            ? factor
            : new SparsePolynomial<>(targetRing, factor.terms());
    }
}
