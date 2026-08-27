package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.NavigableMap;
import java.util.TreeMap;

enum NativeRationalDomainAdapter
        implements NativeUnivariateDomainAdapter<ExactRational> {
    INSTANCE;

    @Override
    public String domainId() {
        return ExactRationalField.DOMAIN_ID;
    }

    @Override
    public UnivariateContentResult normalize(
        FactorizationRequest<ExactRational> request,
        UnivariateContentPolicy policy,
        PolynomialWorkBudget work
    ) {
        return UnivariateContentNormalization.normalizeRational(
            request,
            policy,
            work);
    }

    @Override
    public ExactRational targetUnit(ExactRational unit) {
        return unit;
    }

    @Override
    public SparsePolynomial<ExactRational> targetFactor(
        SparsePolynomial<BigInteger> factor,
        PolynomialRing<ExactRational> targetRing
    ) {
        NavigableMap<Monomial, ExactRational> terms =
            new TreeMap<>(targetRing.monomialComparator());
        factor.terms().forEach((monomial, coefficient) ->
            terms.put(
                monomial,
                ExactRational.integer(coefficient)));
        return new SparsePolynomial<>(targetRing, terms);
    }
}
