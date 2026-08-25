package de.regelsuche.polynomial;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded exact quadratic-by-quadratic decomposition engine for binary
 * homogeneous quartics.
 *
 * <p>This is one factorization engine, not the polynomial API. It proves exact
 * product reconstruction, but it does not prove that the emitted quadratic
 * factors are irreducible.</p>
 */
public final class BinaryQuarticFactorizationEngine
        implements FactorizationEngine<BigInteger> {
    public static final String ENGINE_ID =
        "regelsuche.factorization.binary-quartic-2x2/v1";

    private static final int DEFAULT_MAX_COEFFICIENT_ABS = 32;
    private static final long DEFAULT_MAX_FACTOR_CONFIGURATIONS = 4_096;

    private final int maxCoefficientAbs;
    private final long maxFactorConfigurations;

    public BinaryQuarticFactorizationEngine() {
        this(
            DEFAULT_MAX_COEFFICIENT_ABS,
            DEFAULT_MAX_FACTOR_CONFIGURATIONS);
    }

    public BinaryQuarticFactorizationEngine(
        int maxCoefficientAbs,
        long maxFactorConfigurations
    ) {
        if (maxCoefficientAbs < 1 || maxFactorConfigurations < 1) {
            throw new IllegalArgumentException(
                "binary quartic factorization budget is invalid");
        }
        this.maxCoefficientAbs = maxCoefficientAbs;
        this.maxFactorConfigurations = maxFactorConfigurations;
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public String coefficientDomainId() {
        return BigIntegerDomain.DOMAIN_ID;
    }

    @Override
    public FactorizationReport<BigInteger> factor(
        FactorizationRequest<BigInteger> request
    ) {
        Objects.requireNonNull(request, "request");
        SparsePolynomial<BigInteger> source = request.source();
        FactorizationReport<BigInteger> unsupported =
            validateRequest(request, source);
        if (unsupported != null) {
            return unsupported;
        }

        Coefficients target = new Coefficients(
            source.coefficient(4, 0),
            source.coefficient(3, 1),
            source.coefficient(2, 2),
            source.coefficient(1, 3),
            source.coefficient(0, 4));
        Work work = new Work(Math.min(
            maxFactorConfigurations,
            request.maxArithmeticSteps()));
        Map<String, FactorizationCandidate<BigInteger>> candidates =
            new LinkedHashMap<>();
        try {
            enumerateCandidates(
                source,
                target,
                work,
                candidates);
        } catch (BudgetExceeded exception) {
            return FactorizationReport.failure(
                engineId(),
                FactorizationStatus.BUDGET_INCONCLUSIVE,
                exception.getMessage(),
                work.considered());
        } catch (InvalidCandidate exception) {
            return FactorizationReport.failure(
                engineId(),
                FactorizationStatus.TECHNICAL_FAILURE,
                exception.getMessage(),
                work.considered());
        }

        List<FactorizationCandidate<BigInteger>> ordered =
            candidates.values().stream()
                .sorted(Comparator.comparing(
                    FactorizationCandidate::canonicalMaterial))
                .limit(request.maxCandidates())
                .toList();
        if (ordered.isEmpty()) {
            return FactorizationReport.failure(
                engineId(),
                FactorizationStatus.NO_FACTORIZATION_FOUND,
                "NO_BOUNDED_INTEGER_QUADRATIC_DECOMPOSITION",
                work.considered());
        }
        return new FactorizationReport<>(
            engineId(),
            FactorizationStatus.PARTIAL_FACTORIZATION,
            "EXACT_PRODUCT_DECOMPOSITION_WITHOUT_IRREDUCIBILITY_CLAIM",
            work.considered(),
            ordered);
    }

    private FactorizationReport<BigInteger> validateRequest(
        FactorizationRequest<BigInteger> request,
        SparsePolynomial<BigInteger> source
    ) {
        if (!coefficientDomainId().equals(
                source.ring().coefficientDomain().id())) {
            return FactorizationReport.failure(
                engineId(),
                FactorizationStatus.UNSUPPORTED_DOMAIN,
                "REQUIRES_EXACT_INTEGER_COEFFICIENT_DOMAIN",
                0);
        }
        if (!FactorizationCompleteness.DECOMPOSITION_ONLY.meets(
                request.minimumCompleteness())) {
            return FactorizationReport.failure(
                engineId(),
                FactorizationStatus.UNSUPPORTED_REQUEST,
                "ENGINE_DOES_NOT_CERTIFY_FACTOR_IRREDUCIBILITY",
                0);
        }
        if (source.ring().variableCount() != 2
                || !source.isHomogeneousOfDegree(4)
                || source.coefficient(4, 0).signum() == 0
                || source.coefficient(0, 4).signum() == 0) {
            return FactorizationReport.failure(
                engineId(),
                FactorizationStatus.UNSUPPORTED_REQUEST,
                "REQUIRES_BINARY_HOMOGENEOUS_QUARTIC_WITH_NONZERO_EXTREME_TERMS",
                0);
        }
        if (request.maxCandidates() == 0) {
            return FactorizationReport.failure(
                engineId(),
                FactorizationStatus.BUDGET_INCONCLUSIVE,
                "MAX_CANDIDATES_IS_ZERO",
                0);
        }
        return null;
    }

    private void enumerateCandidates(
        SparsePolynomial<BigInteger> source,
        Coefficients target,
        Work work,
        Map<String, FactorizationCandidate<BigInteger>> candidates
    ) {
        for (BigInteger a : divisors(target.c40())) {
            BigInteger d = target.c40().divide(a);
            if (!withinBound(d)) {
                continue;
            }
            for (BigInteger c : divisors(target.c04())) {
                work.consider();
                BigInteger f = target.c04().divide(c);
                if (!withinBound(f)) {
                    continue;
                }
                for (MiddlePair middle : solveMiddle(
                        a,
                        d,
                        c,
                        f,
                        target)) {
                    retainCandidate(
                        source,
                        target,
                        a,
                        d,
                        c,
                        f,
                        middle,
                        work,
                        candidates);
                }
            }
        }
    }

    private void retainCandidate(
        SparsePolynomial<BigInteger> source,
        Coefficients target,
        BigInteger a,
        BigInteger d,
        BigInteger c,
        BigInteger f,
        MiddlePair middle,
        Work work,
        Map<String, FactorizationCandidate<BigInteger>> candidates
    ) {
        if (!withinBound(middle.b())
                || !withinBound(middle.e())) {
            return;
        }
        BigInteger reconstructedMiddle = a.multiply(f)
            .add(middle.b().multiply(middle.e()))
            .add(c.multiply(d));
        if (!reconstructedMiddle.equals(target.c22())) {
            return;
        }
        NormalizedFactorPair pair = normalize(
            quadratic(source.ring(), a, middle.b(), c),
            quadratic(source.ring(), d, middle.e(), f));
        FactorizationCandidate<BigInteger> candidate = candidate(
            source,
            pair,
            work.considered());
        FactorizationVerifier.Verification<BigInteger> verification =
            FactorizationVerifier.verify(source, candidate);
        if (!verification.verified()) {
            throw new InvalidCandidate(verification.detailCode());
        }
        candidates.putIfAbsent(
            candidate.canonicalMaterial(),
            candidate);
    }

    private SparsePolynomial<BigInteger> quadratic(
        PolynomialRing<BigInteger> ring,
        BigInteger a,
        BigInteger b,
        BigInteger c
    ) {
        return new SparsePolynomial<>(
            ring,
            Map.of(
                Monomial.of(2, 0), a,
                Monomial.of(1, 1), b,
                Monomial.of(0, 2), c));
    }

    private FactorizationCandidate<BigInteger> candidate(
        SparsePolynomial<BigInteger> source,
        NormalizedFactorPair pair,
        long considered
    ) {
        List<PolynomialFactor<BigInteger>> factors = List.of(
            new PolynomialFactor<>(pair.left(), 1),
            new PolynomialFactor<>(pair.right(), 1));
        String material = certificateMaterial(
            source,
            pair,
            considered);
        return new FactorizationCandidate<>(
            pair.unit(),
            factors,
            SparsePolynomial.one(source.ring()),
            FactorizationCompleteness.DECOMPOSITION_ONLY,
            sha256(material));
    }

    private List<MiddlePair> solveMiddle(
        BigInteger a,
        BigInteger d,
        BigInteger c,
        BigInteger f,
        Coefficients target
    ) {
        BigInteger determinant = d.multiply(c)
            .subtract(a.multiply(f));
        if (determinant.signum() != 0) {
            BigInteger bNumerator = target.c31().multiply(c)
                .subtract(a.multiply(target.c13()));
            BigInteger eNumerator = d.multiply(target.c13())
                .subtract(f.multiply(target.c31()));
            if (!isDivisible(bNumerator, determinant)
                    || !isDivisible(eNumerator, determinant)) {
                return List.of();
            }
            return List.of(new MiddlePair(
                bNumerator.divide(determinant),
                eNumerator.divide(determinant)));
        }

        List<MiddlePair> result = new ArrayList<>();
        for (int value = -maxCoefficientAbs;
                value <= maxCoefficientAbs;
                value++) {
            BigInteger b = BigInteger.valueOf(value);
            BigInteger eNumerator = target.c31()
                .subtract(d.multiply(b));
            if (!isDivisible(eNumerator, a)) {
                continue;
            }
            BigInteger e = eNumerator.divide(a);
            if (f.multiply(b)
                    .add(c.multiply(e))
                    .equals(target.c13())) {
                result.add(new MiddlePair(b, e));
            }
        }
        return List.copyOf(result);
    }

    private List<BigInteger> divisors(BigInteger value) {
        BigInteger absolute = value.abs();
        List<BigInteger> result = new ArrayList<>();
        for (int divisor = 1;
                divisor <= maxCoefficientAbs
                    && BigInteger.valueOf(divisor)
                        .compareTo(absolute) <= 0;
                divisor++) {
            BigInteger candidate = BigInteger.valueOf(divisor);
            if (absolute.mod(candidate).signum() == 0) {
                result.add(candidate);
                result.add(candidate.negate());
            }
        }
        return result.stream()
            .distinct()
            .sorted()
            .toList();
    }

    private boolean withinBound(BigInteger value) {
        return value.abs().compareTo(
            BigInteger.valueOf(maxCoefficientAbs)) <= 0;
    }

    private static boolean isDivisible(
        BigInteger numerator,
        BigInteger denominator
    ) {
        return denominator.signum() != 0
            && numerator.remainder(denominator).signum() == 0;
    }

    private NormalizedFactorPair normalize(
        SparsePolynomial<BigInteger> first,
        SparsePolynomial<BigInteger> second
    ) {
        BigInteger unit = BigInteger.ONE;
        if (first.leadingCoefficient().signum() < 0) {
            first = first.negate();
            unit = unit.negate();
        }
        if (second.leadingCoefficient().signum() < 0) {
            second = second.negate();
            unit = unit.negate();
        }
        return first.canonicalMaterial().compareTo(
                second.canonicalMaterial()) <= 0
            ? new NormalizedFactorPair(unit, first, second)
            : new NormalizedFactorPair(unit, second, first);
    }

    private static String certificateMaterial(
        SparsePolynomial<BigInteger> source,
        NormalizedFactorPair pair,
        long considered
    ) {
        return ENGINE_ID
            + "|source=" + source.canonicalMaterial()
            + "|unit=" + pair.unit()
            + "|left=" + pair.left().canonicalMaterial()
            + "|right=" + pair.right().canonicalMaterial()
            + "|completeness="
            + FactorizationCompleteness.DECOMPOSITION_ONLY
            + "|work=" + considered;
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "SHA-256 unavailable",
                exception);
        }
    }

    private record Coefficients(
        BigInteger c40,
        BigInteger c31,
        BigInteger c22,
        BigInteger c13,
        BigInteger c04
    ) {
    }

    private record MiddlePair(
        BigInteger b,
        BigInteger e
    ) {
    }

    private record NormalizedFactorPair(
        BigInteger unit,
        SparsePolynomial<BigInteger> left,
        SparsePolynomial<BigInteger> right
    ) {
    }

    private static final class Work {
        private final long limit;
        private long considered;

        private Work(long limit) {
            this.limit = limit;
        }

        private void consider() {
            considered++;
            if (considered > limit) {
                throw new BudgetExceeded(
                    "MAX_ARITHMETIC_STEPS_EXCEEDED");
            }
        }

        private long considered() {
            return considered;
        }
    }

    private static final class BudgetExceeded
            extends RuntimeException {
        private BudgetExceeded(String detailCode) {
            super(detailCode);
        }
    }

    private static final class InvalidCandidate
            extends RuntimeException {
        private InvalidCandidate(String detailCode) {
            super(detailCode);
        }
    }
}
