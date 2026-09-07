package de.regelsuche.polynomial;

import static de.regelsuche.polynomial.IndependentPrimeFieldPolynomialArithmetic.degree;
import static de.regelsuche.polynomial.IndependentPrimeFieldPolynomialArithmetic.distinctPrimeDivisors;
import static de.regelsuche.polynomial.IndependentPrimeFieldPolynomialArithmetic.frobenius;
import static de.regelsuche.polynomial.IndependentPrimeFieldPolynomialArithmetic.gcd;
import static de.regelsuche.polynomial.IndependentPrimeFieldPolynomialArithmetic.monic;
import static de.regelsuche.polynomial.IndependentPrimeFieldPolynomialArithmetic.reduce;
import static de.regelsuche.polynomial.IndependentPrimeFieldPolynomialArithmetic.subtract;

import de.regelsuche.polynomial.FactorizationVerifier.FrobeniusCheckpoint;
import de.regelsuche.polynomial.FactorizationVerifier.IndependentIrreducibilityOutcome;
import de.regelsuche.polynomial.FactorizationVerifier.IrreducibilityProofMethod;
import de.regelsuche.polynomial.FactorizationVerifier.PrimeAttempt;
import de.regelsuche.polynomial.FactorizationVerifier.PrimeAttemptOutcome;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Independent sufficient irreducibility verifier for univariate Z/Q inputs.
 *
 * <p>This implementation deliberately does not call the factorization
 * backend, suitable-prime selection, Berlekamp factorization, Hensel lifting
 * or Zassenhaus recombination. It proves only the sufficient direction:
 * one degree-preserving irreducible reduction implies irreducibility of the
 * primitive integer associate over Q, by Gauss' lemma.</p>
 */
final class IndependentOriginalDomainIrreducibility {
    static final String METHOD_ID =
        "regelsuche.original-domain-irreducibility/v1";
    private static final int MAX_DEGREE = 256;
    private static final int MAX_NORMALIZED_COEFFICIENT_BITS = 16_384;
    private static final int[] PRIME_POLICY = {
        2, 3, 5, 7, 11, 13, 17, 19,
        23, 29, 31, 37, 41, 43, 47
    };

    private IndependentOriginalDomainIrreducibility() {
    }

    static <C> Result verify(
        FactorizationRequest<C> request,
        long maxWorkUnits
    ) {
        String requestHash = PolynomialEvidence.sha256(
            request.canonicalMaterial());
        String sourceHash = PolynomialEvidence.sha256(
            request.source().canonicalMaterial());
        WorkBudget work = new WorkBudget(maxWorkUnits);
        ArrayList<PrimeAttempt> attempts = new ArrayList<>();
        List<BigInteger> normalized = List.of();
        int degree = -1;
        try {
            SparsePolynomial<C> source = request.source();
            if (source.ring().variableCount() != 1
                    || source.isConstant()) {
                return result(
                    requestHash,
                    sourceHash,
                    IndependentIrreducibilityOutcome.UNSUPPORTED_SHAPE,
                    IrreducibilityProofMethod.NONE,
                    "INDEPENDENT_IRREDUCIBILITY_REQUIRES_NONCONSTANT_UNIVARIATE_SOURCE",
                    normalized,
                    degree,
                    attempts,
                    0,
                    work.ledger());
            }
            if (!supportedDomain(source)) {
                return result(
                    requestHash,
                    sourceHash,
                    IndependentIrreducibilityOutcome.UNSUPPORTED_DOMAIN,
                    IrreducibilityProofMethod.NONE,
                    "INDEPENDENT_IRREDUCIBILITY_REQUIRES_INTEGER_OR_RATIONAL_DOMAIN",
                    normalized,
                    degree,
                    attempts,
                    0,
                    work.ledger());
            }
            degree = source.degree(0);
            if (degree > MAX_DEGREE) {
                return result(
                    requestHash,
                    sourceHash,
                    IndependentIrreducibilityOutcome.DEGREE_LIMIT_EXCEEDED,
                    IrreducibilityProofMethod.NONE,
                    "INDEPENDENT_IRREDUCIBILITY_DEGREE_LIMIT_EXCEEDED",
                    normalized,
                    degree,
                    attempts,
                    0,
                    work.ledger());
            }

            normalized = normalize(request, work);
            degree = normalized.size() - 1;
            if (degree == 1) {
                return result(
                    requestHash,
                    sourceHash,
                    IndependentIrreducibilityOutcome.CERTIFIED,
                    IrreducibilityProofMethod.LINEAR_DEGREE,
                    "INDEPENDENT_LINEAR_IRREDUCIBILITY_CERTIFIED",
                    normalized,
                    degree,
                    attempts,
                    0,
                    work.ledger());
            }

            String normalizedHash = normalizedHash(normalized);
            for (int prime : PRIME_POLICY) {
                PrimeAttempt attempt = verifyPrime(
                    normalized,
                    normalizedHash,
                    prime,
                    work);
                attempts.add(attempt);
                if (attempt.outcome()
                        == PrimeAttemptOutcome.WORK_BUDGET_EXHAUSTED) {
                    return result(
                        requestHash,
                        sourceHash,
                        IndependentIrreducibilityOutcome
                            .WORK_BUDGET_EXHAUSTED,
                        IrreducibilityProofMethod.MODULAR_RABIN,
                        "INDEPENDENT_IRREDUCIBILITY_WORK_BUDGET_EXCEEDED",
                        normalized,
                        degree,
                        attempts,
                        0,
                        work.ledger());
                }
                if (attempt.outcome()
                        == PrimeAttemptOutcome.IRREDUCIBLE_WITNESS) {
                    return result(
                        requestHash,
                        sourceHash,
                        IndependentIrreducibilityOutcome.CERTIFIED,
                        IrreducibilityProofMethod.MODULAR_RABIN,
                        "INDEPENDENT_MODULAR_IRREDUCIBILITY_CERTIFIED",
                        normalized,
                        degree,
                        attempts,
                        prime,
                        work.ledger());
                }
            }
            return result(
                requestHash,
                sourceHash,
                IndependentIrreducibilityOutcome.NO_WITNESS_WITHIN_POLICY,
                IrreducibilityProofMethod.MODULAR_RABIN,
                "INDEPENDENT_IRREDUCIBILITY_WITNESS_NOT_FOUND_WITHIN_POLICY",
                normalized,
                degree,
                attempts,
                0,
                work.ledger());
        } catch (WorkLimitReached exception) {
            return result(
                requestHash,
                sourceHash,
                IndependentIrreducibilityOutcome.WORK_BUDGET_EXHAUSTED,
                IrreducibilityProofMethod.NONE,
                "INDEPENDENT_IRREDUCIBILITY_WORK_BUDGET_EXCEEDED",
                normalized,
                degree,
                attempts,
                0,
                work.ledger());
        } catch (NormalizationLimitReached exception) {
            return result(
                requestHash,
                sourceHash,
                IndependentIrreducibilityOutcome
                    .NORMALIZATION_LIMIT_EXCEEDED,
                IrreducibilityProofMethod.NONE,
                exception.detailCode(),
                normalized,
                degree,
                attempts,
                0,
                work.ledger());
        } catch (RuntimeException exception) {
            return result(
                requestHash,
                sourceHash,
                IndependentIrreducibilityOutcome.TECHNICAL_FAILURE,
                IrreducibilityProofMethod.NONE,
                "INDEPENDENT_IRREDUCIBILITY_"
                    + exception.getClass().getSimpleName()
                        .toUpperCase(java.util.Locale.ROOT),
                normalized,
                degree,
                attempts,
                0,
                work.ledger());
        }
    }

    private static boolean supportedDomain(SparsePolynomial<?> source) {
        Object domain = source.ring().coefficientDomain();
        return domain == BigIntegerDomain.INSTANCE
            || domain == ExactRationalField.INSTANCE;
    }

    private static <C> List<BigInteger> normalize(
        FactorizationRequest<C> request,
        WorkBudget work
    ) {
        SparsePolynomial<C> source = request.source();
        int coefficientLimit = Math.min(
            request.structuralLimits().maxCoefficientBitLength(),
            MAX_NORMALIZED_COEFFICIENT_BITS);
        BigInteger[] integers = new BigInteger[source.degree(0) + 1];
        Arrays.fill(integers, BigInteger.ZERO);
        if (source.ring().coefficientDomain()
                == BigIntegerDomain.INSTANCE) {
            for (Map.Entry<Monomial, C> term : source.terms().entrySet()) {
                work.consume(
                    "independent-irreducibility.normalization.coefficients",
                    1);
                integers[term.getKey().exponent(0)] =
                    (BigInteger) term.getValue();
            }
        } else {
            ExactRational[] rationals =
                new ExactRational[integers.length];
            Arrays.fill(rationals, ExactRational.ZERO);
            for (Map.Entry<Monomial, C> term : source.terms().entrySet()) {
                work.consume(
                    "independent-irreducibility.normalization.coefficients",
                    1);
                rationals[term.getKey().exponent(0)] =
                    (ExactRational) term.getValue();
            }
            BigInteger denominatorLcm = BigInteger.ONE;
            for (ExactRational coefficient : rationals) {
                if (coefficient.isZero()) {
                    continue;
                }
                work.consume(
                    "independent-irreducibility.normalization.denominator-lcm",
                    1);
                BigInteger gcd = denominatorLcm.gcd(
                    coefficient.denominator());
                denominatorLcm = denominatorLcm.divide(gcd)
                    .multiply(coefficient.denominator());
                requireBitLength(denominatorLcm, coefficientLimit);
            }
            for (int index = 0; index < rationals.length; index++) {
                ExactRational coefficient = rationals[index];
                if (coefficient.isZero()) {
                    continue;
                }
                work.consume(
                    "independent-irreducibility.normalization.integer-scaling",
                    1);
                integers[index] = coefficient.numerator().multiply(
                    denominatorLcm.divide(coefficient.denominator()));
                requireBitLength(integers[index], coefficientLimit);
            }
        }

        BigInteger content = BigInteger.ZERO;
        for (BigInteger coefficient : integers) {
            if (coefficient.signum() == 0) {
                continue;
            }
            work.consume(
                "independent-irreducibility.normalization.content-gcd",
                1);
            content = content.gcd(coefficient.abs());
        }
        if (content.signum() == 0) {
            throw new IllegalArgumentException(
                "nonzero source normalized to zero");
        }
        if (!content.equals(BigInteger.ONE)) {
            for (int index = 0; index < integers.length; index++) {
                if (integers[index].signum() != 0) {
                    work.consume(
                        "independent-irreducibility.normalization.primitive-division",
                        1);
                    integers[index] = integers[index].divide(content);
                }
            }
        }
        if (integers[integers.length - 1].signum() < 0) {
            for (int index = 0; index < integers.length; index++) {
                if (integers[index].signum() != 0) {
                    work.consume(
                        "independent-irreducibility.normalization.sign",
                        1);
                    integers[index] = integers[index].negate();
                }
            }
        }
        for (BigInteger coefficient : integers) {
            requireBitLength(coefficient, coefficientLimit);
        }
        return List.copyOf(Arrays.asList(integers));
    }

    private static void requireBitLength(
        BigInteger value,
        int maximum
    ) {
        if (value.abs().bitLength() > maximum) {
            throw new NormalizationLimitReached(
                "INDEPENDENT_IRREDUCIBILITY_NORMALIZATION_LIMIT_EXCEEDED");
        }
    }

    private static PrimeAttempt verifyPrime(
        List<BigInteger> normalized,
        String normalizedHash,
        int prime,
        WorkBudget work
    ) {
        PrimeProgress progress = new PrimeProgress(
            prime,
            normalizedHash,
            work.total());
        try {
            int sourceDegree = normalized.size() - 1;
            int[] reduction = reduce(normalized, prime, work);
            progress.reducedDegree = degree(reduction);
            if (progress.reducedDegree != sourceDegree) {
                return progress.finish(
                    PrimeAttemptOutcome.DEGREE_LOSS,
                    work.total());
            }
            int[] modulus = monic(reduction, prime, work);
            int[] x = {0, 1};
            for (int divisor : distinctPrimeDivisors(sourceDegree)) {
                int iterations = sourceDegree / divisor;
                int[] residue = frobenius(
                    x,
                    iterations,
                    prime,
                    modulus,
                    work);
                int[] difference = subtract(
                    residue,
                    x,
                    prime,
                    work);
                int gcdDegree = degree(gcd(
                    modulus,
                    difference,
                    prime,
                    work));
                progress.checkpoints.add(new FrobeniusCheckpoint(
                    iterations,
                    gcdDegree,
                    residueHash(prime, residue)));
                if (gcdDegree != 0) {
                    return progress.finish(
                        PrimeAttemptOutcome.REDUCIBLE_REDUCTION,
                        work.total());
                }
            }
            int[] finalResidue = frobenius(
                x,
                sourceDegree,
                prime,
                modulus,
                work);
            progress.finalResidueHash = residueHash(
                prime,
                finalResidue);
            work.consume(
                "independent-irreducibility.polynomial-comparisons",
                Math.max(finalResidue.length, x.length));
            progress.finalCongruence = Arrays.equals(
                finalResidue,
                x);
            return progress.finish(
                progress.finalCongruence
                    ? PrimeAttemptOutcome.IRREDUCIBLE_WITNESS
                    : PrimeAttemptOutcome.REDUCIBLE_REDUCTION,
                work.total());
        } catch (WorkLimitReached exception) {
            return progress.finish(
                PrimeAttemptOutcome.WORK_BUDGET_EXHAUSTED,
                work.total());
        }
    }

    private static String normalizedHash(
        List<BigInteger> coefficients
    ) {
        StringBuilder material = new StringBuilder(METHOD_ID);
        coefficients.forEach(coefficient ->
            PolynomialEvidence.append(
                material,
                coefficient.toString()));
        return PolynomialEvidence.sha256(material.toString());
    }

    private static String residueHash(int prime, int[] polynomial) {
        StringBuilder material = new StringBuilder(METHOD_ID);
        PolynomialEvidence.append(material, Integer.toString(prime));
        for (int coefficient : polynomial) {
            PolynomialEvidence.append(
                material,
                Integer.toString(coefficient));
        }
        return PolynomialEvidence.sha256(material.toString());
    }

    private static String attemptHash(
        String normalizedHash,
        int prime,
        PrimeAttemptOutcome outcome,
        int reducedDegree,
        List<FrobeniusCheckpoint> checkpoints,
        boolean finalCongruence,
        String finalResidueHash,
        long workUnits
    ) {
        StringBuilder material = new StringBuilder(METHOD_ID);
        PolynomialEvidence.append(material, normalizedHash);
        PolynomialEvidence.append(material, Integer.toString(prime));
        PolynomialEvidence.append(material, outcome.name());
        PolynomialEvidence.append(
            material,
            Integer.toString(reducedDegree));
        checkpoints.forEach(checkpoint ->
            PolynomialEvidence.append(
                material,
                checkpoint.canonicalMaterial()));
        PolynomialEvidence.append(
            material,
            Boolean.toString(finalCongruence));
        PolynomialEvidence.append(material, finalResidueHash);
        PolynomialEvidence.append(material, Long.toString(workUnits));
        return PolynomialEvidence.sha256(material.toString());
    }

    private static Result result(
        String requestHash,
        String sourceHash,
        IndependentIrreducibilityOutcome outcome,
        IrreducibilityProofMethod proofMethod,
        String detailCode,
        List<BigInteger> normalized,
        int degree,
        List<PrimeAttempt> attempts,
        int selectedPrime,
        PolynomialWorkLedger work
    ) {
        List<BigInteger> retainedNormalized = List.copyOf(normalized);
        List<PrimeAttempt> retainedAttempts = List.copyOf(attempts);
        StringBuilder material = new StringBuilder(METHOD_ID);
        PolynomialEvidence.append(material, requestHash);
        PolynomialEvidence.append(material, sourceHash);
        PolynomialEvidence.append(material, outcome.name());
        PolynomialEvidence.append(material, proofMethod.name());
        PolynomialEvidence.append(material, detailCode);
        retainedNormalized.forEach(coefficient ->
            PolynomialEvidence.append(
                material,
                coefficient.toString()));
        PolynomialEvidence.append(material, Integer.toString(degree));
        retainedAttempts.forEach(attempt ->
            PolynomialEvidence.append(
                material,
                attempt.canonicalMaterial()));
        PolynomialEvidence.append(
            material,
            Integer.toString(selectedPrime));
        PolynomialEvidence.append(material, work.canonicalMaterial());
        return new Result(
            METHOD_ID,
            requestHash,
            sourceHash,
            outcome,
            proofMethod,
            detailCode,
            retainedNormalized,
            degree,
            retainedAttempts,
            selectedPrime,
            work,
            PolynomialEvidence.sha256(material.toString()));
    }

    record Result(
        String methodId,
        String requestHash,
        String sourceHash,
        IndependentIrreducibilityOutcome outcome,
        IrreducibilityProofMethod proofMethod,
        String detailCode,
        List<BigInteger> normalizedPrimitiveCoefficients,
        int degree,
        List<PrimeAttempt> primeAttempts,
        int selectedPrime,
        PolynomialWorkLedger work,
        String traceHash
    ) {
    }

    private static final class PrimeProgress {
        private final int prime;
        private final String normalizedHash;
        private final long workAtStart;
        private final List<FrobeniusCheckpoint> checkpoints =
            new ArrayList<>();
        private int reducedDegree = -1;
        private boolean finalCongruence;
        private String finalResidueHash = "";

        private PrimeProgress(
            int prime,
            String normalizedHash,
            long workAtStart
        ) {
            this.prime = prime;
            this.normalizedHash = normalizedHash;
            this.workAtStart = workAtStart;
        }

        private PrimeAttempt finish(
            PrimeAttemptOutcome outcome,
            long currentWork
        ) {
            long workUnits = currentWork - workAtStart;
            String hash = attemptHash(
                normalizedHash,
                prime,
                outcome,
                reducedDegree,
                checkpoints,
                finalCongruence,
                finalResidueHash,
                workUnits);
            return new PrimeAttempt(
                prime,
                outcome,
                reducedDegree,
                checkpoints,
                finalCongruence,
                finalResidueHash,
                workUnits,
                hash);
        }
    }

    private static final class WorkBudget implements PolynomialWorkSink {
        private final long limit;
        private final Map<String, Long> stages = new LinkedHashMap<>();
        private long total;

        private WorkBudget(long limit) {
            if (limit < 0) {
                throw new IllegalArgumentException(
                    "independent verification budget must not be negative");
            }
            this.limit = limit;
        }

        @Override
        public void consume(String stage, long units) {
            if (units < 0 || total > limit - units) {
                throw new WorkLimitReached();
            }
            if (units == 0) {
                return;
            }
            total += units;
            stages.merge(stage, units, Math::addExact);
        }

        private long total() {
            return total;
        }

        private PolynomialWorkLedger ledger() {
            return new PolynomialWorkLedger(stages);
        }
    }

    private static final class WorkLimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class NormalizationLimitReached
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String detailCode;

        private NormalizationLimitReached(String detailCode) {
            this.detailCode = detailCode;
        }

        private String detailCode() {
            return detailCode;
        }
    }
}
