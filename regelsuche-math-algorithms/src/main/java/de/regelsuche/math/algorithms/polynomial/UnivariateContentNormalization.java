package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Exact content and primitive-part normalization for univariate
 * {@code Z[x]} and {@code Q[x]} factorization requests.
 *
 * <p>Both entry points return one primitive integer polynomial with positive
 * leading coefficient. The complete scalar, including sign and cleared
 * denominator, remains separate for later rational reassembly.</p>
 */
public final class UnivariateContentNormalization {
    public static final String METHOD_ID =
        "regelsuche.univariate-content-primitive-part/v1";

    private UnivariateContentNormalization() {
    }

    public static UnivariateContentResult normalizeInteger(
        FactorizationRequest<BigInteger> request,
        UnivariateContentPolicy policy
    ) {
        Objects.requireNonNull(request, "request");
        return normalizeInteger(
            request,
            policy,
            new PolynomialWorkBudget(request.maxWorkUnits()));
    }

    public static UnivariateContentResult normalizeRational(
        FactorizationRequest<ExactRational> request,
        UnivariateContentPolicy policy
    ) {
        Objects.requireNonNull(request, "request");
        return normalizeRational(
            request,
            policy,
            new PolynomialWorkBudget(request.maxWorkUnits()));
    }

    static UnivariateContentResult normalizeInteger(
        FactorizationRequest<BigInteger> request,
        UnivariateContentPolicy policy,
        PolynomialWorkBudget work
    ) {
        return normalize(
            request,
            policy,
            IntegerAccess.INSTANCE,
            work);
    }

    static UnivariateContentResult normalizeRational(
        FactorizationRequest<ExactRational> request,
        UnivariateContentPolicy policy,
        PolynomialWorkBudget work
    ) {
        return normalize(
            request,
            policy,
            RationalAccess.INSTANCE,
            work);
    }

    private static <C> UnivariateContentResult normalize(
        FactorizationRequest<C> request,
        UnivariateContentPolicy policy,
        CoefficientAccess<C> access,
        PolynomialWorkBudget work
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(work, "work");

        UnivariateContentResult rejected =
            rejectInput(
                request,
                policy,
                access,
                work.ledger());
        if (rejected != null) {
            return rejected;
        }

        try {
            return completed(
                request,
                policy,
                access,
                work);
        } catch (PolynomialWorkBudget.LimitReached exception) {
            return failure(
                UnivariateContentResult.Status.BUDGET_INCONCLUSIVE,
                "CONTENT_NORMALIZATION_WORK_BUDGET_EXCEEDED",
                request,
                policy,
                work.ledger());
        } catch (IntermediateLimitReached exception) {
            return failure(
                UnivariateContentResult.Status.BUDGET_INCONCLUSIVE,
                exception.detailCode(),
                request,
                policy,
                work.ledger());
        } catch (ArithmeticException exception) {
            return failure(
                UnivariateContentResult.Status.TECHNICAL_FAILURE,
                "CONTENT_NORMALIZATION_EXACT_ARITHMETIC_FAILED",
                request,
                policy,
                work.ledger());
        } catch (RuntimeException exception) {
            return failure(
                UnivariateContentResult.Status.TECHNICAL_FAILURE,
                "CONTENT_NORMALIZATION_"
                    + exception.getClass().getSimpleName()
                        .toUpperCase(java.util.Locale.ROOT),
                request,
                policy,
                work.ledger());
        }
    }

    private static <C> UnivariateContentResult rejectInput(
        FactorizationRequest<C> request,
        UnivariateContentPolicy policy,
        CoefficientAccess<C> access,
        PolynomialWorkLedger work
    ) {
        SparsePolynomial<C> source = request.source();
        String structuralViolation =
            request.structuralViolation().orElse(null);
        if (structuralViolation != null) {
            return failure(
                UnivariateContentResult.Status.BUDGET_INCONCLUSIVE,
                structuralViolation,
                request,
                policy,
                work);
        }
        if (!access.domainId().equals(
                source.ring().coefficientDomain().id())) {
            return failure(
                UnivariateContentResult.Status.UNSUPPORTED_DOMAIN,
                access.domainFailureCode(),
                request,
                policy,
                work);
        }
        if (source.ring().variableCount() != 1) {
            return failure(
                UnivariateContentResult.Status.UNSUPPORTED_SHAPE,
                "REQUIRES_ONE_POLYNOMIAL_VARIABLE",
                request,
                policy,
                work);
        }
        if (source.isZero()) {
            return failure(
                UnivariateContentResult.Status.UNSUPPORTED_SHAPE,
                "ZERO_POLYNOMIAL_HAS_NO_PRIMITIVE_PART",
                request,
                policy,
                work);
        }
        return null;
    }

    private static <C> UnivariateContentResult completed(
        FactorizationRequest<C> request,
        UnivariateContentPolicy policy,
        CoefficientAccess<C> access,
        PolynomialWorkBudget work
    ) {
        SparsePolynomial<C> source = request.source();
        BigInteger denominator = denominatorClearingFactor(
            source,
            policy,
            access,
            work);
        NavigableMap<Monomial, BigInteger> integral =
            integralTerms(
                source,
                denominator,
                policy,
                access,
                work);
        BigInteger content = integerContent(integral, work);
        if (content.signum() <= 0) {
            throw new IllegalStateException(
                "nonzero polynomial produced invalid content");
        }

        work.consume("content.sign-normalization", 1);
        BigInteger signedContent =
            integral.firstEntry().getValue().signum() < 0
                ? content.negate()
                : content;
        NavigableMap<Monomial, BigInteger> primitiveTerms =
            primitiveTerms(
                integral,
                signedContent,
                policy,
                work);
        SparsePolynomial<BigInteger> primitive =
            primitivePolynomial(source, primitiveTerms);
        ExactRational scalar = new ExactRational(
            signedContent,
            denominator);

        verify(
            source,
            integral,
            primitive,
            content,
            signedContent,
            scalar,
            access,
            work);
        return UnivariateContentResult.completed(
            denominator,
            content,
            scalar,
            primitive,
            work.ledger(),
            request,
            policy);
    }

    private static <C> BigInteger denominatorClearingFactor(
        SparsePolynomial<C> source,
        UnivariateContentPolicy policy,
        CoefficientAccess<C> access,
        PolynomialWorkBudget work
    ) {
        BigInteger result = BigInteger.ONE;
        for (C coefficient : source.terms().values()) {
            work.consume(
                "content.denominator-lcm.coefficients",
                1);
            BigInteger denominator =
                access.denominator(coefficient);
            requireWithinLimit(
                denominator,
                policy,
                "DENOMINATOR_LCM_BIT_LENGTH_EXCEEDED");
            work.consume("content.denominator-lcm.gcd", 1);
            BigInteger gcd = result.gcd(denominator);
            work.consume("content.denominator-lcm.division", 1);
            BigInteger reduced = result.divide(gcd);
            work.consume(
                "content.denominator-lcm.multiplication",
                1);
            result = multiplyWithinLimit(
                reduced,
                denominator,
                policy,
                "DENOMINATOR_LCM_BIT_LENGTH_EXCEEDED");
        }
        return result;
    }

    private static <C> NavigableMap<Monomial, BigInteger>
            integralTerms(
        SparsePolynomial<C> source,
        BigInteger denominatorClearingFactor,
        UnivariateContentPolicy policy,
        CoefficientAccess<C> access,
        PolynomialWorkBudget work
    ) {
        NavigableMap<Monomial, BigInteger> result =
            new TreeMap<>(source.ring().monomialComparator());
        for (Map.Entry<Monomial, C> term
                : source.terms().entrySet()) {
            work.consume(
                "content.integralization.coefficients",
                1);
            BigInteger denominator =
                access.denominator(term.getValue());
            work.consume("content.integralization.division", 1);
            BigInteger multiplier =
                denominatorClearingFactor.divide(denominator);
            work.consume(
                "content.integralization.multiplication",
                1);
            BigInteger value = multiplyWithinLimit(
                access.numerator(term.getValue()),
                multiplier,
                policy,
                "INTEGRAL_COEFFICIENT_BIT_LENGTH_EXCEEDED");
            if (value.signum() == 0) {
                throw new IllegalStateException(
                    "sparse source exposed a zero term");
            }
            result.put(term.getKey(), value);
        }
        return result;
    }

    private static BigInteger integerContent(
        NavigableMap<Monomial, BigInteger> integral,
        PolynomialWorkBudget work
    ) {
        BigInteger result = BigInteger.ZERO;
        for (BigInteger coefficient : integral.values()) {
            work.consume("content.integer-content.gcd", 1);
            result = result.gcd(coefficient.abs());
        }
        return result;
    }

    private static NavigableMap<Monomial, BigInteger>
            primitiveTerms(
        NavigableMap<Monomial, BigInteger> integral,
        BigInteger signedContent,
        UnivariateContentPolicy policy,
        PolynomialWorkBudget work
    ) {
        NavigableMap<Monomial, BigInteger> result =
            new TreeMap<>(integral.comparator());
        for (Map.Entry<Monomial, BigInteger> term
                : integral.entrySet()) {
            work.consume("content.primitive-part.division", 1);
            BigInteger coefficient =
                term.getValue().divide(signedContent);
            requireWithinLimit(
                coefficient,
                policy,
                "PRIMITIVE_COEFFICIENT_BIT_LENGTH_EXCEEDED");
            result.put(term.getKey(), coefficient);
        }
        return result;
    }

    private static <C> SparsePolynomial<BigInteger>
            primitivePolynomial(
        SparsePolynomial<C> source,
        NavigableMap<Monomial, BigInteger> terms
    ) {
        PolynomialRing<BigInteger> ring =
            new PolynomialRing<>(
                BigIntegerDomain.INSTANCE,
                source.ring().variables(),
                source.ring().monomialOrder());
        return new SparsePolynomial<>(ring, terms);
    }

    private static <C> void verify(
        SparsePolynomial<C> source,
        NavigableMap<Monomial, BigInteger> integral,
        SparsePolynomial<BigInteger> primitive,
        BigInteger content,
        BigInteger signedContent,
        ExactRational scalar,
        CoefficientAccess<C> access,
        PolynomialWorkBudget work
    ) {
        verifyPrimitive(primitive, content, work);
        verifyIntegral(
            integral,
            primitive,
            signedContent,
            work);
        verifySource(
            source,
            primitive,
            scalar,
            access,
            work);
    }

    private static void verifyPrimitive(
        SparsePolynomial<BigInteger> primitive,
        BigInteger content,
        PolynomialWorkBudget work
    ) {
        BigInteger gcd = BigInteger.ZERO;
        for (BigInteger coefficient : primitive.terms().values()) {
            work.consume("content.verify.primitive-gcd", 1);
            gcd = gcd.gcd(coefficient.abs());
        }
        work.consume("content.verify.primitive-comparison", 1);
        if (!BigInteger.ONE.equals(gcd)
                || content.signum() <= 0
                || primitive.leadingCoefficient().signum() <= 0) {
            throw new IllegalStateException(
                "primitive-part verification failed");
        }
    }

    private static void verifyIntegral(
        NavigableMap<Monomial, BigInteger> integral,
        SparsePolynomial<BigInteger> primitive,
        BigInteger signedContent,
        PolynomialWorkBudget work
    ) {
        work.consume("content.verify.integral-support", 1);
        if (!integral.keySet().equals(
                primitive.terms().keySet())) {
            throw new IllegalStateException(
                "primitive part changed integral support");
        }
        for (Map.Entry<Monomial, BigInteger> term
                : primitive.terms().entrySet()) {
            work.consume(
                "content.verify.integral-multiplication",
                1);
            BigInteger reconstructed =
                term.getValue().multiply(signedContent);
            work.consume(
                "content.verify.integral-comparison",
                1);
            if (!reconstructed.equals(
                    integral.get(term.getKey()))) {
                throw new IllegalStateException(
                    "integer content reconstruction failed");
            }
        }
    }

    private static <C> void verifySource(
        SparsePolynomial<C> source,
        SparsePolynomial<BigInteger> primitive,
        ExactRational scalar,
        CoefficientAccess<C> access,
        PolynomialWorkBudget work
    ) {
        work.consume("content.verify.source-support", 1);
        if (!source.terms().keySet().equals(
                primitive.terms().keySet())) {
            throw new IllegalStateException(
                "primitive part changed source support");
        }
        for (Map.Entry<Monomial, C> term
                : source.terms().entrySet()) {
            work.consume(
                "content.verify.source-multiplication",
                1);
            ExactRational reconstructed =
                ExactRational.integer(
                    primitive.terms().get(term.getKey()))
                    .multiply(scalar);
            work.consume(
                "content.verify.source-comparison",
                1);
            if (!access.matches(
                    term.getValue(),
                    reconstructed)) {
                throw new IllegalStateException(
                    "source content reconstruction failed");
            }
        }
    }

    private static BigInteger multiplyWithinLimit(
        BigInteger left,
        BigInteger right,
        UnivariateContentPolicy policy,
        String detailCode
    ) {
        if (left.signum() != 0 && right.signum() != 0) {
            long minimumBits =
                (long) left.abs().bitLength()
                    + right.abs().bitLength()
                    - 1L;
            if (minimumBits
                    > policy
                        .maxIntermediateCoefficientBitLength()) {
                throw new IntermediateLimitReached(detailCode);
            }
        }
        BigInteger result = left.multiply(right);
        requireWithinLimit(result, policy, detailCode);
        return result;
    }

    private static void requireWithinLimit(
        BigInteger value,
        UnivariateContentPolicy policy,
        String detailCode
    ) {
        if (value.abs().bitLength()
                > policy.maxIntermediateCoefficientBitLength()) {
            throw new IntermediateLimitReached(detailCode);
        }
    }

    private static <C> UnivariateContentResult failure(
        UnivariateContentResult.Status status,
        String detailCode,
        FactorizationRequest<C> request,
        UnivariateContentPolicy policy,
        PolynomialWorkLedger work
    ) {
        return UnivariateContentResult.failure(
            status,
            detailCode,
            work,
            request,
            policy);
    }

    private interface CoefficientAccess<C> {
        String domainId();

        String domainFailureCode();

        BigInteger numerator(C coefficient);

        BigInteger denominator(C coefficient);

        boolean matches(
            C sourceCoefficient,
            ExactRational reconstructed);
    }

    private enum IntegerAccess
            implements CoefficientAccess<BigInteger> {
        INSTANCE;

        @Override
        public String domainId() {
            return BigIntegerDomain.DOMAIN_ID;
        }

        @Override
        public String domainFailureCode() {
            return "REQUIRES_EXACT_INTEGER_COEFFICIENT_DOMAIN";
        }

        @Override
        public BigInteger numerator(BigInteger coefficient) {
            return Objects.requireNonNull(
                coefficient,
                "coefficient");
        }

        @Override
        public BigInteger denominator(BigInteger coefficient) {
            Objects.requireNonNull(coefficient, "coefficient");
            return BigInteger.ONE;
        }

        @Override
        public boolean matches(
            BigInteger sourceCoefficient,
            ExactRational reconstructed
        ) {
            return reconstructed.isInteger()
                && sourceCoefficient.equals(
                    reconstructed.numerator());
        }
    }

    private enum RationalAccess
            implements CoefficientAccess<ExactRational> {
        INSTANCE;

        @Override
        public String domainId() {
            return ExactRationalField.DOMAIN_ID;
        }

        @Override
        public String domainFailureCode() {
            return "REQUIRES_EXACT_RATIONAL_COEFFICIENT_DOMAIN";
        }

        @Override
        public BigInteger numerator(ExactRational coefficient) {
            return Objects.requireNonNull(
                coefficient,
                "coefficient").numerator();
        }

        @Override
        public BigInteger denominator(ExactRational coefficient) {
            return Objects.requireNonNull(
                coefficient,
                "coefficient").denominator();
        }

        @Override
        public boolean matches(
            ExactRational sourceCoefficient,
            ExactRational reconstructed
        ) {
            return sourceCoefficient.equals(reconstructed);
        }
    }

    private static final class IntermediateLimitReached
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String detailCode;

        private IntermediateLimitReached(String detailCode) {
            this.detailCode = detailCode;
        }

        private String detailCode() {
            return detailCode;
        }
    }
}
