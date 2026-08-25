package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.PrimeField;
import de.regelsuche.polynomial.SparsePolynomial;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Deterministically selects the first suitable modular prime for one canonical
 * primitive polynomial in {@code Z[x]} and retains every rejection reason.
 */
public final class SuitablePrimeSelection {
    public static final String METHOD_ID =
        "regelsuche.suitable-prime-selection/v1";

    private SuitablePrimeSelection() {
    }

    public static SuitablePrimeSelectionResult selectAndFactor(
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionPolicy policy
    ) {
        Objects.requireNonNull(request, "request");
        return selectAndFactor(
            request,
            policy,
            new PolynomialWorkBudget(request.maxWorkUnits()));
    }

    static SuitablePrimeSelectionResult selectAndFactor(
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionPolicy policy,
        PolynomialWorkBudget work
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(work, "work");
        ArrayList<SuitablePrimeSelectionResult.PrimeAttempt> attempts =
            new ArrayList<>();

        if (work.limit() != request.maxWorkUnits()) {
            return failure(
                SuitablePrimeSelectionResult.Status.TECHNICAL_FAILURE,
                "SUITABLE_PRIME_WORK_BUDGET_AUTHORITY_MISMATCH",
                attempts,
                work.ledger(),
                request,
                policy);
        }
        SuitablePrimeSelectionResult rejected = rejectInput(
            request,
            policy,
            attempts,
            work.ledger());
        if (rejected != null) {
            return rejected;
        }

        try {
            return execute(request, policy, work, attempts);
        } catch (PolynomialWorkBudget.LimitReached exception) {
            return failure(
                SuitablePrimeSelectionResult.Status.BUDGET_INCONCLUSIVE,
                "SUITABLE_PRIME_SELECTION_WORK_BUDGET_EXCEEDED",
                attempts,
                work.ledger(),
                request,
                policy);
        } catch (ArithmeticException exception) {
            return failure(
                SuitablePrimeSelectionResult.Status.TECHNICAL_FAILURE,
                "SUITABLE_PRIME_EXACT_ARITHMETIC_FAILED",
                attempts,
                work.ledger(),
                request,
                policy);
        } catch (RuntimeException exception) {
            return failure(
                SuitablePrimeSelectionResult.Status.TECHNICAL_FAILURE,
                "SUITABLE_PRIME_"
                    + exception.getClass().getSimpleName()
                        .toUpperCase(java.util.Locale.ROOT),
                attempts,
                work.ledger(),
                request,
                policy);
        }
    }

    private static SuitablePrimeSelectionResult rejectInput(
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionPolicy policy,
        ArrayList<SuitablePrimeSelectionResult.PrimeAttempt> attempts,
        PolynomialWorkLedger work
    ) {
        String structuralViolation =
            request.structuralViolation().orElse(null);
        if (structuralViolation != null) {
            return failure(
                SuitablePrimeSelectionResult.Status.BUDGET_INCONCLUSIVE,
                structuralViolation,
                attempts,
                work,
                request,
                policy);
        }
        SparsePolynomial<BigInteger> source = request.source();
        if (!(source.ring().coefficientDomain()
                instanceof BigIntegerDomain)) {
            return failure(
                SuitablePrimeSelectionResult.Status.UNSUPPORTED_DOMAIN,
                "REQUIRES_EXACT_INTEGER_COEFFICIENT_DOMAIN",
                attempts,
                work,
                request,
                policy);
        }
        if (source.ring().variableCount() != 1
                || source.isConstant()) {
            return failure(
                SuitablePrimeSelectionResult.Status.UNSUPPORTED_SHAPE,
                "REQUIRES_NONCONSTANT_UNIVARIATE_INTEGER_POLYNOMIAL",
                attempts,
                work,
                request,
                policy);
        }
        if (request.maxCandidates() == 0) {
            return failure(
                SuitablePrimeSelectionResult.Status.BUDGET_INCONCLUSIVE,
                "MAX_CANDIDATES_IS_ZERO",
                attempts,
                work,
                request,
                policy);
        }
        return null;
    }

    private static SuitablePrimeSelectionResult execute(
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionPolicy policy,
        PolynomialWorkBudget work,
        ArrayList<SuitablePrimeSelectionResult.PrimeAttempt> attempts
    ) {
        SparsePolynomial<BigInteger> source = request.source();
        if (!isCanonicalPrimitive(source, work)) {
            return failure(
                SuitablePrimeSelectionResult.Status.UNSUPPORTED_SHAPE,
                "REQUIRES_CANONICAL_PRIMITIVE_INTEGER_INPUT",
                attempts,
                work.ledger(),
                request,
                policy);
        }

        for (int prime : policy.candidatePrimes()) {
            long workBefore = total(work);
            PrimeField field = PrimeField.of(prime);
            SparsePolynomial<BigInteger> modularSource = reduce(
                source,
                field,
                work,
                "suitable-prime.reduction.coefficients");
            work.consume(
                "suitable-prime.leading-coefficient-tests",
                1);
            if (field.isZero(source.leadingCoefficient())) {
                attempts.add(SuitablePrimeSelectionResult.issueAttempt(
                    prime,
                    SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                        .REJECTED,
                    "LEADING_COEFFICIENT_VANISHES_MOD_PRIME",
                    modularSource,
                    null,
                    total(work) - workBefore));
                continue;
            }
            if (modularSource.isZero()
                    || modularSource.degree(0) != source.degree(0)) {
                throw new IllegalStateException(
                    "modular reduction changed degree without a vanishing leading coefficient");
            }

            FactorizationRequest<BigInteger> modularRequest =
                modularRequest(request, modularSource, field);
            FiniteFieldFactorizationResult factorization =
                FiniteFieldFactorization.factorSquareFree(
                    modularRequest,
                    policy.factorizationPolicy(),
                    work);

            if (factorization.completed()) {
                try {
                    verifyModularSourceCorrespondence(
                        source,
                        field,
                        modularSource,
                        work);
                } catch (PolynomialWorkBudget.LimitReached exception) {
                    long attemptWork = total(work) - workBefore;
                    attempts.add(SuitablePrimeSelectionResult.issueAttempt(
                        prime,
                        SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                            .TERMINAL_INCONCLUSIVE,
                        "SOURCE_CORRESPONDENCE_WORK_BUDGET_EXCEEDED",
                        modularSource,
                        factorization.certificateHash(),
                        attemptWork));
                    return failure(
                        SuitablePrimeSelectionResult.Status
                            .BUDGET_INCONCLUSIVE,
                        "SOURCE_CORRESPONDENCE_INCONCLUSIVE",
                        attempts,
                        work.ledger(),
                        request,
                        policy);
                }
                long attemptWork = total(work) - workBefore;
                ArrayList<SuitablePrimeSelectionResult.PrimeAttempt>
                    completedAttempts = new ArrayList<>(attempts);
                completedAttempts.add(
                    SuitablePrimeSelectionResult.issueAttempt(
                        prime,
                        SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                            .SELECTED,
                        "SUITABLE_PRIME_SELECTED",
                        modularSource,
                        factorization.certificateHash(),
                        attemptWork));
                return SuitablePrimeSelectionResult.completed(
                    completedAttempts,
                    prime,
                    modularSource,
                    factorization,
                    work.ledger(),
                    request,
                    policy);
            }
            long attemptWork = total(work) - workBefore;
            if (factorization.status()
                    == FiniteFieldFactorizationResult.Status
                        .UNSUPPORTED_SHAPE
                    && "REQUIRES_SQUARE_FREE_INPUT".equals(
                        factorization.detailCode())) {
                attempts.add(SuitablePrimeSelectionResult.issueAttempt(
                    prime,
                    SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                        .REJECTED,
                    "MODULAR_REDUCTION_NOT_SQUARE_FREE",
                    modularSource,
                    factorization.certificateHash(),
                    attemptWork));
                continue;
            }
            if (factorization.status()
                    == FiniteFieldFactorizationResult.Status
                        .BUDGET_INCONCLUSIVE) {
                attempts.add(SuitablePrimeSelectionResult.issueAttempt(
                    prime,
                    SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                        .TERMINAL_INCONCLUSIVE,
                    factorization.detailCode(),
                    modularSource,
                    factorization.certificateHash(),
                    attemptWork));
                return failure(
                    SuitablePrimeSelectionResult.Status
                        .BUDGET_INCONCLUSIVE,
                    "MODULAR_FACTORIZATION_INCONCLUSIVE",
                    attempts,
                    work.ledger(),
                    request,
                    policy);
            }

            attempts.add(SuitablePrimeSelectionResult.issueAttempt(
                prime,
                SuitablePrimeSelectionResult.PrimeAttempt.Disposition
                    .TERMINAL_FAILURE,
                factorization.detailCode(),
                modularSource,
                factorization.certificateHash(),
                attemptWork));
            return failure(
                SuitablePrimeSelectionResult.Status.TECHNICAL_FAILURE,
                "MODULAR_FACTORIZATION_CONTRACT_FAILURE",
                attempts,
                work.ledger(),
                request,
                policy);
        }

        return failure(
            SuitablePrimeSelectionResult.Status.BUDGET_INCONCLUSIVE,
            "NO_SUITABLE_PRIME_WITHIN_POLICY",
            attempts,
            work.ledger(),
            request,
            policy);
    }

    private static boolean isCanonicalPrimitive(
        SparsePolynomial<BigInteger> source,
        PolynomialWorkBudget work
    ) {
        work.consume("suitable-prime.leading-sign-tests", 1);
        if (source.leadingCoefficient().signum() <= 0) {
            return false;
        }
        BigInteger gcd = BigInteger.ZERO;
        for (BigInteger coefficient : source.terms().values()) {
            work.consume("suitable-prime.primitive-gcd", 1);
            gcd = gcd.gcd(coefficient.abs());
        }
        work.consume("suitable-prime.primitive-comparisons", 1);
        return BigInteger.ONE.equals(gcd);
    }

    private static SparsePolynomial<BigInteger> reduce(
        SparsePolynomial<BigInteger> source,
        PrimeField field,
        PolynomialWorkBudget work,
        String workStage
    ) {
        PolynomialRing<BigInteger> modularRing = new PolynomialRing<>(
            field,
            source.ring().variables(),
            source.ring().monomialOrder());
        NavigableMap<Monomial, BigInteger> terms =
            new TreeMap<>(modularRing.monomialComparator());
        for (Map.Entry<Monomial, BigInteger> term
                : source.terms().entrySet()) {
            work.consume(workStage, 1);
            BigInteger coefficient = field.canonical(term.getValue());
            if (!field.isZero(coefficient)) {
                terms.put(term.getKey(), coefficient);
            }
        }
        return new SparsePolynomial<>(modularRing, terms);
    }

    private static void verifyModularSourceCorrespondence(
        SparsePolynomial<BigInteger> source,
        PrimeField field,
        SparsePolynomial<BigInteger> modularSource,
        PolynomialWorkBudget work
    ) {
        SparsePolynomial<BigInteger> independentlyReduced = reduce(
            source,
            field,
            work,
            "suitable-prime.verify.source-reduction.coefficients");
        work.consume(
            "suitable-prime.verify.source-reduction.comparisons",
            1);
        if (!independentlyReduced.equals(modularSource)) {
            throw new IllegalStateException(
                "selected modular source does not reduce the integer source");
        }
    }

    private static FactorizationRequest<BigInteger> modularRequest(
        FactorizationRequest<BigInteger> sourceRequest,
        SparsePolynomial<BigInteger> modularSource,
        PrimeField field
    ) {
        FactorizationRequest.StructuralLimits sourceLimits =
            sourceRequest.structuralLimits();
        int maximumResidueBits = field.modulus()
            .subtract(BigInteger.ONE)
            .bitLength();
        FactorizationRequest.StructuralLimits modularLimits =
            new FactorizationRequest.StructuralLimits(
                sourceLimits.maxVariables(),
                sourceLimits.maxTotalDegree(),
                sourceLimits.maxTerms(),
                Math.max(
                    sourceLimits.maxCoefficientBitLength(),
                    maximumResidueBits));
        return new FactorizationRequest<>(
            modularSource,
            sourceRequest.evidenceRequirement(),
            modularLimits,
            sourceRequest.maxCandidates(),
            sourceRequest.maxWorkUnits());
    }

    private static long total(PolynomialWorkBudget work) {
        return work.ledger().totalWorkUnits();
    }

    private static SuitablePrimeSelectionResult failure(
        SuitablePrimeSelectionResult.Status status,
        String detailCode,
        ArrayList<SuitablePrimeSelectionResult.PrimeAttempt> attempts,
        PolynomialWorkLedger work,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionPolicy policy
    ) {
        return SuitablePrimeSelectionResult.failure(
            status,
            detailCode,
            attempts,
            work,
            request,
            policy);
    }
}
