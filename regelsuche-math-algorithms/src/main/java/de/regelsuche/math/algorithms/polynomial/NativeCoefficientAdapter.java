package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Domain-specific boundary for the shared native univariate pipeline. */
interface NativeCoefficientAdapter<C> {
    String domainId();

    String engineId();

    UnivariateContentResult normalize(
        FactorizationRequest<C> request,
        UnivariateContentPolicy policy,
        PolynomialWorkBudget work);

    C targetUnit(ExactRational unit);

    SparsePolynomial<C> targetFactor(
        SparsePolynomial<BigInteger> factor,
        PolynomialRing<C> targetRing);

    enum IntegerAdapter
            implements NativeCoefficientAdapter<BigInteger> {
        INSTANCE;

        @Override
        public String domainId() {
            return BigIntegerDomain.DOMAIN_ID;
        }

        @Override
        public String engineId() {
            return "regelsuche.factorization.native-univariate-integer/v1";
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
                : new SparsePolynomial<>(
                    targetRing,
                    factor.terms());
        }
    }

    enum RationalAdapter
            implements NativeCoefficientAdapter<ExactRational> {
        INSTANCE;

        @Override
        public String domainId() {
            return ExactRationalField.DOMAIN_ID;
        }

        @Override
        public String engineId() {
            return "regelsuche.factorization.native-univariate-rational/v1";
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
}
