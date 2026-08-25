package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.PrimeField;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic complete factorization of square-free univariate polynomials
 * over an explicitly declared prime field.
 */
public final class FiniteFieldFactorization {
    public static final String METHOD_ID =
        "regelsuche.prime-field-factorization/v1";

    private FiniteFieldFactorization() {
    }

    public static FiniteFieldFactorizationResult factorSquareFree(
        FactorizationRequest<BigInteger> request,
        FiniteFieldFactorizationPolicy policy
    ) {
        Objects.requireNonNull(request, "request");
        return factorSquareFree(
            request,
            policy,
            new PolynomialWorkBudget(request.maxWorkUnits()));
    }

    static FiniteFieldFactorizationResult factorSquareFree(
        FactorizationRequest<BigInteger> request,
        FiniteFieldFactorizationPolicy policy,
        PolynomialWorkBudget work
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(work, "work");
        PrimeField field = primeField(request);

        if (work.limit() != request.maxWorkUnits()) {
            return failure(
                FiniteFieldFactorizationResult.Status.TECHNICAL_FAILURE,
                "FINITE_FIELD_WORK_BUDGET_AUTHORITY_MISMATCH",
                request,
                policy,
                field,
                work.ledger());
        }
        FiniteFieldFactorizationResult rejected = rejectInput(
            request,
            policy,
            field,
            work.ledger());
        if (rejected != null) {
            return rejected;
        }

        try {
            return execute(request, policy, field, work);
        } catch (PolynomialWorkBudget.LimitReached exception) {
            return failure(
                FiniteFieldFactorizationResult.Status.BUDGET_INCONCLUSIVE,
                "FINITE_FIELD_FACTORIZATION_WORK_BUDGET_EXCEEDED",
                request,
                policy,
                field,
                work.ledger());
        } catch (AlgorithmFailure exception) {
            return failure(
                FiniteFieldFactorizationResult.Status.TECHNICAL_FAILURE,
                exception.detailCode(),
                request,
                policy,
                field,
                work.ledger());
        } catch (ArithmeticException exception) {
            return failure(
                FiniteFieldFactorizationResult.Status.TECHNICAL_FAILURE,
                "FINITE_FIELD_EXACT_ARITHMETIC_FAILED",
                request,
                policy,
                field,
                work.ledger());
        } catch (RuntimeException exception) {
            return failure(
                FiniteFieldFactorizationResult.Status.TECHNICAL_FAILURE,
                "FINITE_FIELD_"
                    + exception.getClass().getSimpleName()
                        .toUpperCase(java.util.Locale.ROOT),
                request,
                policy,
                field,
                work.ledger());
        }
    }

    private static FiniteFieldFactorizationResult rejectInput(
        FactorizationRequest<BigInteger> request,
        FiniteFieldFactorizationPolicy policy,
        PrimeField field,
        PolynomialWorkLedger work
    ) {
        String structuralViolation =
            request.structuralViolation().orElse(null);
        if (structuralViolation != null) {
            return failure(
                FiniteFieldFactorizationResult.Status.BUDGET_INCONCLUSIVE,
                structuralViolation,
                request,
                policy,
                field,
                work);
        }
        if (field == null) {
            return failure(
                FiniteFieldFactorizationResult.Status.UNSUPPORTED_DOMAIN,
                "REQUIRES_DECLARED_PRIME_FIELD_COEFFICIENT_DOMAIN",
                request,
                policy,
                null,
                work);
        }
        SparsePolynomial<BigInteger> source = request.source();
        if (source.ring().variableCount() != 1
                || source.isConstant()) {
            return failure(
                FiniteFieldFactorizationResult.Status.UNSUPPORTED_SHAPE,
                "REQUIRES_NONCONSTANT_UNIVARIATE_POLYNOMIAL",
                request,
                policy,
                field,
                work);
        }
        if (request.maxCandidates() == 0) {
            return failure(
                FiniteFieldFactorizationResult.Status.BUDGET_INCONCLUSIVE,
                "MAX_CANDIDATES_IS_ZERO",
                request,
                policy,
                field,
                work);
        }
        if (field.prime() > policy.maxEnumeratedFieldElements()) {
            return failure(
                FiniteFieldFactorizationResult.Status.BUDGET_INCONCLUSIVE,
                "PRIME_FIELD_ENUMERATION_POLICY_EXCEEDED",
                request,
                policy,
                field,
                work);
        }
        if (!policy.permitsMatrixDegree(source.degree(0))) {
            return failure(
                FiniteFieldFactorizationResult.Status.BUDGET_INCONCLUSIVE,
                "BERLEKAMP_MATRIX_CELL_POLICY_EXCEEDED",
                request,
                policy,
                field,
                work);
        }
        return null;
    }

    private static FiniteFieldFactorizationResult execute(
        FactorizationRequest<BigInteger> request,
        FiniteFieldFactorizationPolicy policy,
        PrimeField field,
        PolynomialWorkBudget work
    ) {
        UnivariatePolynomialView<BigInteger> original =
            UnivariatePolynomialView.from(request.source());
        BigInteger unit = original.leadingCoefficient();
        UnivariatePolynomialView<BigInteger> monic = original.monic(
            field,
            work,
            "finite-field.leading-normalization");
        if (!isSquareFree(monic, field, work)) {
            return failure(
                FiniteFieldFactorizationResult.Status.UNSUPPORTED_SHAPE,
                "REQUIRES_SQUARE_FREE_INPUT",
                request,
                policy,
                field,
                work.ledger());
        }

        BerlekampKernel.Kernel kernel = BerlekampKernel.compute(
            monic,
            field,
            work);
        List<UnivariatePolynomialView<BigInteger>> factors = split(
            monic,
            kernel,
            field,
            work);
        Verification verification = verify(
            original,
            unit,
            factors,
            field,
            work);
        List<PolynomialFactor<BigInteger>> issuedFactors = factors.stream()
            .map(factor -> new PolynomialFactor<>(
                factor.toSparsePolynomial(),
                1))
            .toList();
        return FiniteFieldFactorizationResult.completed(
            unit,
            issuedFactors,
            kernel.nullity(),
            kernel.certificateHash(),
            verification.irreducibilityCertificateHashes(),
            work.ledger(),
            request,
            policy,
            field);
    }

    private static boolean isSquareFree(
        UnivariatePolynomialView<BigInteger> source,
        PrimeField field,
        PolynomialWorkBudget work
    ) {
        UnivariatePolynomialView<BigInteger> derivative = source.derivative(
            work,
            "finite-field.square-free.derivative");
        work.consume(
            "finite-field.square-free.derivative-zero-tests",
            1);
        if (derivative.isZero()) {
            return false;
        }
        UnivariatePolynomialView<BigInteger> gcd =
            UnivariatePolynomialAlgorithms.gcd(
                source,
                derivative,
                field,
                work,
                "finite-field.square-free.gcd");
        work.consume(
            "finite-field.square-free.comparisons",
            1);
        return gcd.isOne();
    }

    private static List<UnivariatePolynomialView<BigInteger>> split(
        UnivariatePolynomialView<BigInteger> source,
        BerlekampKernel.Kernel kernel,
        PrimeField field,
        PolynomialWorkBudget work
    ) {
        ArrayList<UnivariatePolynomialView<BigInteger>> factors =
            new ArrayList<>();
        factors.add(source);
        if (kernel.nullity() == 1) {
            return List.copyOf(factors);
        }

        for (UnivariatePolynomialView<BigInteger> vector
                : kernel.basis()) {
            if (vector.isConstant()) {
                continue;
            }
            for (int residue = 0;
                    residue < field.prime()
                        && factors.size() < kernel.nullity();
                    residue++) {
                work.consume("berlekamp.split.residues", 1);
                UnivariatePolynomialView<BigInteger> splitter =
                    FiniteFieldPolynomialArithmetic.subtractConstant(
                        vector,
                        BigInteger.valueOf(residue),
                        field,
                        work,
                        "berlekamp.split.constant-subtractions");
                factors = splitCurrentFactors(
                    factors,
                    splitter,
                    field,
                    work);
            }
            if (factors.size() == kernel.nullity()) {
                break;
            }
        }
        if (factors.size() != kernel.nullity()) {
            throw new AlgorithmFailure(
                "BERLEKAMP_SPLITTING_DID_NOT_REACH_KERNEL_NULLITY");
        }
        return canonicalFactors(factors);
    }

    private static ArrayList<UnivariatePolynomialView<BigInteger>>
            splitCurrentFactors(
        List<UnivariatePolynomialView<BigInteger>> factors,
        UnivariatePolynomialView<BigInteger> splitter,
        PrimeField field,
        PolynomialWorkBudget work
    ) {
        ArrayList<UnivariatePolynomialView<BigInteger>> next =
            new ArrayList<>();
        for (UnivariatePolynomialView<BigInteger> factor : factors) {
            if (factor.degree() == 1) {
                next.add(factor);
                continue;
            }
            UnivariatePolynomialView<BigInteger> reducedSplitter =
                FiniteFieldPolynomialArithmetic.remainder(
                    splitter,
                    factor,
                    field,
                    work,
                    "berlekamp.split.reduce-splitter");
            UnivariatePolynomialView<BigInteger> gcd =
                UnivariatePolynomialAlgorithms.gcd(
                    factor,
                    reducedSplitter,
                    field,
                    work,
                    "berlekamp.split.gcd");
            work.consume("berlekamp.split.gcd-comparisons", 2);
            if (gcd.isOne() || gcd.equals(factor)) {
                next.add(factor);
                continue;
            }
            UnivariatePolynomialView<BigInteger> quotient =
                UnivariatePolynomialAlgorithms.exactQuotient(
                    factor,
                    gcd,
                    field,
                    work,
                    "berlekamp.split.quotient");
            next.add(gcd.monic(
                field,
                work,
                "berlekamp.split.normalize-gcd"));
            next.add(quotient.monic(
                field,
                work,
                "berlekamp.split.normalize-quotient"));
        }
        return canonicalFactors(next);
    }

    private static ArrayList<UnivariatePolynomialView<BigInteger>>
            canonicalFactors(
        List<UnivariatePolynomialView<BigInteger>> factors
    ) {
        ArrayList<UnivariatePolynomialView<BigInteger>> result =
            new ArrayList<>(factors);
        result.sort(Comparator.comparing(
            UnivariatePolynomialView::canonicalMaterial));
        return result;
    }

    private static Verification verify(
        UnivariatePolynomialView<BigInteger> source,
        BigInteger unit,
        List<UnivariatePolynomialView<BigInteger>> factors,
        PrimeField field,
        PolynomialWorkBudget work
    ) {
        UnivariatePolynomialView<BigInteger> reconstructed =
            FiniteFieldPolynomialArithmetic.constant(
                source.ring(),
                unit);
        for (UnivariatePolynomialView<BigInteger> factor : factors) {
            work.consume(
                "finite-field.verify.monic-comparisons",
                1);
            if (!field.isOne(factor.leadingCoefficient())) {
                throw new AlgorithmFailure(
                    "FINITE_FIELD_FACTOR_IS_NOT_MONIC");
            }
            reconstructed = reconstructed.multiply(
                factor,
                work,
                "finite-field.verify.product");
        }
        work.consume("finite-field.verify.product-comparisons", 1);
        if (!source.equals(reconstructed)) {
            throw new AlgorithmFailure(
                "FINITE_FIELD_FACTORS_DO_NOT_RECONSTRUCT_SOURCE");
        }

        verifyPairwiseCoprime(factors, field, work);
        ArrayList<String> certificates = new ArrayList<>();
        for (int index = 0; index < factors.size(); index++) {
            FiniteFieldPolynomialArithmetic.IrreducibilityEvidence evidence =
                FiniteFieldPolynomialArithmetic.verifyIrreducible(
                    factors.get(index),
                    field,
                    work,
                    "finite-field.verify.irreducible-" + index);
            if (!evidence.irreducible()) {
                throw new AlgorithmFailure(
                    "FINITE_FIELD_FACTOR_FAILED_IRREDUCIBILITY_CHECK");
            }
            certificates.add(evidence.certificateHash());
        }
        return new Verification(List.copyOf(certificates));
    }

    private static void verifyPairwiseCoprime(
        List<UnivariatePolynomialView<BigInteger>> factors,
        PrimeField field,
        PolynomialWorkBudget work
    ) {
        for (int left = 0; left < factors.size(); left++) {
            for (int right = left + 1;
                    right < factors.size();
                    right++) {
                UnivariatePolynomialView<BigInteger> gcd =
                    UnivariatePolynomialAlgorithms.gcd(
                        factors.get(left),
                        factors.get(right),
                        field,
                        work,
                        "finite-field.verify.pairwise-gcd");
                work.consume(
                    "finite-field.verify.pairwise-comparisons",
                    1);
                if (!gcd.isOne()) {
                    throw new AlgorithmFailure(
                        "FINITE_FIELD_FACTORS_ARE_NOT_PAIRWISE_COPRIME");
                }
            }
        }
    }

    private static PrimeField primeField(
        FactorizationRequest<BigInteger> request
    ) {
        return request.source().ring().coefficientDomain()
                instanceof PrimeField field
            ? field
            : null;
    }

    private static FiniteFieldFactorizationResult failure(
        FiniteFieldFactorizationResult.Status status,
        String detailCode,
        FactorizationRequest<BigInteger> request,
        FiniteFieldFactorizationPolicy policy,
        PrimeField field,
        PolynomialWorkLedger work
    ) {
        return FiniteFieldFactorizationResult.failure(
            status,
            detailCode,
            work,
            request,
            policy,
            field);
    }

    private record Verification(
        List<String> irreducibilityCertificateHashes
    ) {
        private Verification {
            irreducibilityCertificateHashes =
                List.copyOf(irreducibilityCertificateHashes);
        }
    }

    private static final class AlgorithmFailure
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String detailCode;

        private AlgorithmFailure(String detailCode) {
            this.detailCode = detailCode;
        }

        private String detailCode() {
            return detailCode;
        }
    }
}
