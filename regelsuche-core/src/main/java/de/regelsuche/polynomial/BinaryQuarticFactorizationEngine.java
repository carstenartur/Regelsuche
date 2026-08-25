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
 * Bounded exact quadratic-by-quadratic proposal engine for binary homogeneous
 * quartics.
 *
 * <p>The engine solves exact coefficient constraints. It does not issue trusted
 * product, completeness or irreducibility evidence; that authority belongs to
 * {@link FactorizationVerifier}.</p>
 */
public final class BinaryQuarticFactorizationEngine
        implements FactorizationEngine<BigInteger> {
    public static final String ENGINE_ID =
        "regelsuche.factorization.binary-quartic-2x2/v1";

    private static final int DEFAULT_MAX_COEFFICIENT_ABS = 32;
    private static final long DEFAULT_MAX_ENGINE_WORK_UNITS = 4_096;

    private final int maxCoefficientAbs;
    private final long maxEngineWorkUnits;

    public BinaryQuarticFactorizationEngine() {
        this(
            DEFAULT_MAX_COEFFICIENT_ABS,
            DEFAULT_MAX_ENGINE_WORK_UNITS);
    }

    public BinaryQuarticFactorizationEngine(
        int maxCoefficientAbs,
        long maxEngineWorkUnits
    ) {
        if (maxCoefficientAbs < 1 || maxEngineWorkUnits < 1) {
            throw new IllegalArgumentException(
                "binary quartic factorization budget is invalid");
        }
        this.maxCoefficientAbs = maxCoefficientAbs;
        this.maxEngineWorkUnits = maxEngineWorkUnits;
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
    public EngineResult<BigInteger> propose(
        FactorizationRequest<BigInteger> request
    ) {
        Objects.requireNonNull(request, "request");
        SparsePolynomial<BigInteger> source = request.source();
        EngineResult<BigInteger> unsupported = validateRequest(
            request,
            source);
        if (unsupported != null) {
            return unsupported;
        }

        Work work = new Work(Math.min(
            maxEngineWorkUnits,
            request.maxWorkUnits()));
        Map<String, Proposal<BigInteger>> proposals =
            new LinkedHashMap<>();
        try {
            Coefficients target = coefficients(source);
            List<BigInteger> leadingDivisors = divisors(
                target.c40(),
                work);
            List<BigInteger> trailingDivisors = divisors(
                target.c04(),
                work);
            enumerateProposals(
                source,
                target,
                leadingDivisors,
                trailingDivisors,
                work,
                proposals);
        } catch (BudgetExceeded exception) {
            return result(
                request,
                Outcome.BUDGET_INCONCLUSIVE,
                "ENGINE_WORK_BUDGET_EXCEEDED",
                work.ledger(),
                List.of(),
                BackendClaim.NONE);
        }

        List<Proposal<BigInteger>> ordered = proposals.values().stream()
            .sorted(Comparator.comparing(Proposal::canonicalMaterial))
            .limit(request.maxCandidates())
            .toList();
        if (ordered.isEmpty()) {
            return result(
                request,
                Outcome.NO_CANDIDATE,
                "NO_BOUNDED_INTEGER_QUADRATIC_DECOMPOSITION",
                work.ledger(),
                List.of(),
                BackendClaim.NONE);
        }
        return result(
            request,
            Outcome.CANDIDATES,
            "EXACT_QUADRATIC_DECOMPOSITION_PROPOSALS",
            work.ledger(),
            ordered,
            BackendClaim.NONE);
    }

    private EngineResult<BigInteger> validateRequest(
        FactorizationRequest<BigInteger> request,
        SparsePolynomial<BigInteger> source
    ) {
        if (!coefficientDomainId().equals(
                source.ring().coefficientDomain().id())) {
            return result(
                request,
                Outcome.UNSUPPORTED_DOMAIN,
                "REQUIRES_EXACT_INTEGER_COEFFICIENT_DOMAIN",
                WorkLedger.empty(),
                List.of(),
                BackendClaim.NONE);
        }
        if (request.evidenceRequirement()
                == FactorizationRequest.EvidenceRequirement
                    .INDEPENDENT_COMPLETE) {
            return result(
                request,
                Outcome.UNSUPPORTED_REQUEST,
                "ENGINE_DOES_NOT_CERTIFY_FACTOR_IRREDUCIBILITY",
                WorkLedger.empty(),
                List.of(),
                BackendClaim.NONE);
        }
        if (source.ring().variableCount() != 2
                || !source.isHomogeneousOfDegree(4)
                || source.coefficient(4, 0).signum() == 0
                || source.coefficient(0, 4).signum() == 0) {
            return result(
                request,
                Outcome.UNSUPPORTED_REQUEST,
                "REQUIRES_BINARY_HOMOGENEOUS_QUARTIC_WITH_NONZERO_EXTREME_TERMS",
                WorkLedger.empty(),
                List.of(),
                BackendClaim.NONE);
        }
        if (request.maxCandidates() == 0) {
            return result(
                request,
                Outcome.BUDGET_INCONCLUSIVE,
                "MAX_CANDIDATES_IS_ZERO",
                WorkLedger.empty(),
                List.of(),
                BackendClaim.NONE);
        }
        return null;
    }

    private static Coefficients coefficients(
        SparsePolynomial<BigInteger> source
    ) {
        return new Coefficients(
            source.coefficient(4, 0),
            source.coefficient(3, 1),
            source.coefficient(2, 2),
            source.coefficient(1, 3),
            source.coefficient(0, 4));
    }

    private void enumerateProposals(
        SparsePolynomial<BigInteger> source,
        Coefficients target,
        List<BigInteger> leadingDivisors,
        List<BigInteger> trailingDivisors,
        Work work,
        Map<String, Proposal<BigInteger>> proposals
    ) {
        for (BigInteger a : leadingDivisors) {
            BigInteger d = target.c40().divide(a);
            if (!withinBound(d)) {
                continue;
            }
            for (BigInteger c : trailingDivisors) {
                work.consume("engine.factor-pair-configurations", 1);
                BigInteger f = target.c04().divide(c);
                if (!withinBound(f)) {
                    continue;
                }
                for (MiddlePair middle : solveMiddle(
                        a,
                        d,
                        c,
                        f,
                        target,
                        work)) {
                    retainProposal(
                        source,
                        target,
                        new Quadratic(a, middle.b(), c),
                        new Quadratic(d, middle.e(), f),
                        work,
                        proposals);
                }
            }
        }
    }

    private void retainProposal(
        SparsePolynomial<BigInteger> source,
        Coefficients target,
        Quadratic first,
        Quadratic second,
        Work work,
        Map<String, Proposal<BigInteger>> proposals
    ) {
        work.consume("engine.candidate-reconstructions", 1);
        BigInteger reconstructedMiddle = first.a().multiply(second.c())
            .add(first.b().multiply(second.b()))
            .add(first.c().multiply(second.a()));
        if (!reconstructedMiddle.equals(target.c22())) {
            return;
        }
        NormalizedFactorPair normalized = normalize(
            source.ring(),
            first,
            second);
        String certificateMaterial = proposalCertificateMaterial(
            source,
            normalized);
        Proposal<BigInteger> proposal = new Proposal<>(
            normalized.unit(),
            List.of(
                new PolynomialFactor<>(normalized.left(), 1),
                new PolynomialFactor<>(normalized.right(), 1)),
            SparsePolynomial.one(source.ring()),
            sha256(certificateMaterial));
        proposals.putIfAbsent(
            proposal.canonicalMaterial(),
            proposal);
    }

    private List<MiddlePair> solveMiddle(
        BigInteger a,
        BigInteger d,
        BigInteger c,
        BigInteger f,
        Coefficients target,
        Work work
    ) {
        BigInteger determinant = d.multiply(c)
            .subtract(a.multiply(f));
        if (determinant.signum() != 0) {
            work.consume("engine.middle-system-solves", 1);
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
            work.consume("engine.middle-system-solves", 1);
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

    private List<BigInteger> divisors(
        BigInteger value,
        Work work
    ) {
        BigInteger absolute = value.abs();
        List<BigInteger> result = new ArrayList<>();
        for (int divisor = 1;
                divisor <= maxCoefficientAbs
                    && BigInteger.valueOf(divisor)
                        .compareTo(absolute) <= 0;
                divisor++) {
            work.consume("engine.divisor-tests", 1);
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

    private NormalizedFactorPair normalize(
        PolynomialRing<BigInteger> ring,
        Quadratic first,
        Quadratic second
    ) {
        PrimitiveQuadratic left = primitive(first);
        PrimitiveQuadratic right = primitive(second);
        BigInteger unit = left.content().multiply(right.content());
        Quadratic leftCoefficients = left.polynomial();
        Quadratic rightCoefficients = right.polynomial();
        if (leftCoefficients.a().signum() < 0) {
            leftCoefficients = leftCoefficients.negate();
            unit = unit.negate();
        }
        if (rightCoefficients.a().signum() < 0) {
            rightCoefficients = rightCoefficients.negate();
            unit = unit.negate();
        }
        SparsePolynomial<BigInteger> leftPolynomial = quadratic(
            ring,
            leftCoefficients);
        SparsePolynomial<BigInteger> rightPolynomial = quadratic(
            ring,
            rightCoefficients);
        return leftPolynomial.canonicalMaterial().compareTo(
                rightPolynomial.canonicalMaterial()) <= 0
            ? new NormalizedFactorPair(
                unit,
                leftPolynomial,
                rightPolynomial)
            : new NormalizedFactorPair(
                unit,
                rightPolynomial,
                leftPolynomial);
    }

    private static PrimitiveQuadratic primitive(Quadratic polynomial) {
        BigInteger content = polynomial.a().abs()
            .gcd(polynomial.b().abs())
            .gcd(polynomial.c().abs());
        if (content.signum() == 0) {
            throw new IllegalArgumentException(
                "zero quadratic factor is invalid");
        }
        return new PrimitiveQuadratic(
            content,
            new Quadratic(
                polynomial.a().divide(content),
                polynomial.b().divide(content),
                polynomial.c().divide(content)));
    }

    private static SparsePolynomial<BigInteger> quadratic(
        PolynomialRing<BigInteger> ring,
        Quadratic coefficients
    ) {
        return new SparsePolynomial<>(
            ring,
            Map.of(
                Monomial.of(2, 0), coefficients.a(),
                Monomial.of(1, 1), coefficients.b(),
                Monomial.of(0, 2), coefficients.c()));
    }

    private EngineResult<BigInteger> result(
        FactorizationRequest<BigInteger> request,
        Outcome outcome,
        String detailCode,
        WorkLedger work,
        List<Proposal<BigInteger>> proposals,
        BackendClaim backendClaim
    ) {
        String resultMaterial = engineConfigurationMaterial()
            + "|source=" + request.source().canonicalMaterial()
            + "|evidence=" + request.evidenceRequirement()
            + "|maxCandidates=" + request.maxCandidates()
            + "|maxWorkUnits=" + request.maxWorkUnits()
            + "|outcome=" + outcome
            + "|detail=" + detailCode
            + "|work=" + work.canonicalMaterial()
            + "|backendClaim=" + backendClaim
            + "|proposals=" + proposals.stream()
                .map(Proposal::canonicalMaterial)
                .sorted()
                .toList();
        return new EngineResult<>(
            engineId(),
            outcome,
            detailCode,
            work,
            proposals,
            backendClaim,
            sha256(resultMaterial));
    }

    private String proposalCertificateMaterial(
        SparsePolynomial<BigInteger> source,
        NormalizedFactorPair pair
    ) {
        return engineConfigurationMaterial()
            + "|source=" + source.canonicalMaterial()
            + "|unit=" + pair.unit()
            + "|left=" + pair.left().canonicalMaterial()
            + "|right=" + pair.right().canonicalMaterial()
            + "|remainder=one";
    }

    private String engineConfigurationMaterial() {
        return ENGINE_ID
            + "|maxCoefficientAbs=" + maxCoefficientAbs
            + "|maxEngineWorkUnits=" + maxEngineWorkUnits;
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

    private record Quadratic(
        BigInteger a,
        BigInteger b,
        BigInteger c
    ) {
        private Quadratic negate() {
            return new Quadratic(a.negate(), b.negate(), c.negate());
        }
    }

    private record PrimitiveQuadratic(
        BigInteger content,
        Quadratic polynomial
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
        private final Map<String, Long> stages = new LinkedHashMap<>();
        private long total;

        private Work(long limit) {
            this.limit = limit;
        }

        private void consume(String stage, long units) {
            if (units < 0 || units > limit - total) {
                throw new BudgetExceeded();
            }
            total += units;
            stages.merge(stage, units, Math::addExact);
        }

        private WorkLedger ledger() {
            return new WorkLedger(stages);
        }
    }

    private static final class BudgetExceeded
            extends RuntimeException {
    }
}
