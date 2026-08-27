package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic bounded Zassenhaus recombination for one primitive square-free
 * polynomial in {@code Z[x]}.
 */
public final class ZassenhausRecombination {
    public static final String METHOD_ID =
        "regelsuche.zassenhaus-recombination/v1";

    private ZassenhausRecombination() {
    }

    public static ZassenhausRecombinationResult recombine(
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingResult lifting,
        ZassenhausRecombinationPolicy policy
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(lifting, "lifting");
        Objects.requireNonNull(policy, "policy");
        PolynomialWorkBudget work =
            resume(request.maxWorkUnits(), lifting.work());
        ZassenhausSearch.CandidateAudit audit =
            new ZassenhausSearch.CandidateAudit(
                request.canonicalMaterial());
        ZassenhausRecombinationResult rejected = rejectInput(
            request,
            selection,
            lifting,
            policy,
            work,
            audit);
        if (rejected != null) {
            return rejected;
        }
        try {
            BigInteger bound =
                IntegerPolynomialArithmetic.coefficientBound(
                    request.source(),
                    policy,
                    work);
            return execute(
                request,
                selection,
                lifting,
                policy,
                bound,
                work,
                audit);
        } catch (PolynomialWorkBudget.LimitReached exception) {
            return inconclusive(
                "ZASSENHAUS_WORK_BUDGET_EXCEEDED",
                null,
                work,
                request,
                selection,
                lifting,
                policy,
                audit);
        } catch (IntegerPolynomialArithmetic.LimitReached exception) {
            return inconclusive(
                exception.detailCode(),
                null,
                work,
                request,
                selection,
                lifting,
                policy,
                audit);
        } catch (RuntimeException exception) {
            return technicalFailure(
                technicalDetail(exception),
                null,
                work,
                request,
                selection,
                lifting,
                policy,
                audit);
        }
    }

    static ZassenhausRecombinationResult recombine(
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingResult lifting,
        ZassenhausRecombinationPolicy policy,
        BigInteger coefficientBound,
        PolynomialWorkBudget work
    ) {
        Objects.requireNonNull(coefficientBound, "coefficientBound");
        ZassenhausSearch.CandidateAudit audit =
            new ZassenhausSearch.CandidateAudit(
                request.canonicalMaterial());
        ZassenhausRecombinationResult rejected = rejectInput(
            request,
            selection,
            lifting,
            policy,
            work,
            audit);
        if (rejected != null) {
            return rejected;
        }
        try {
            return execute(
                request,
                selection,
                lifting,
                policy,
                coefficientBound,
                work,
                audit);
        } catch (PolynomialWorkBudget.LimitReached exception) {
            return inconclusive(
                "ZASSENHAUS_WORK_BUDGET_EXCEEDED",
                coefficientBound,
                work,
                request,
                selection,
                lifting,
                policy,
                audit);
        } catch (IntegerPolynomialArithmetic.LimitReached exception) {
            return inconclusive(
                exception.detailCode(),
                coefficientBound,
                work,
                request,
                selection,
                lifting,
                policy,
                audit);
        } catch (RuntimeException exception) {
            return technicalFailure(
                technicalDetail(exception),
                coefficientBound,
                work,
                request,
                selection,
                lifting,
                policy,
                audit);
        }
    }

    private static ZassenhausRecombinationResult rejectInput(
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingResult lifting,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work,
        ZassenhausSearch.CandidateAudit audit
    ) {
        if (work.limit() != request.maxWorkUnits()
                || !work.ledger().equals(lifting.work())) {
            return technicalFailure(
                "ZASSENHAUS_WORK_BUDGET_AUTHORITY_MISMATCH",
                null,
                work,
                request,
                selection,
                lifting,
                policy,
                audit);
        }
        String structuralViolation =
            request.structuralViolation().orElse(null);
        if (structuralViolation != null) {
            return inconclusive(
                structuralViolation,
                null,
                work,
                request,
                selection,
                lifting,
                policy,
                audit);
        }
        SparsePolynomial<BigInteger> source = request.source();
        if (!(source.ring().coefficientDomain()
                instanceof BigIntegerDomain)) {
            return failure(
                ZassenhausRecombinationResult.Status.UNSUPPORTED_DOMAIN,
                "REQUIRES_EXACT_INTEGER_COEFFICIENT_DOMAIN",
                null,
                work,
                request,
                selection,
                lifting,
                policy,
                audit);
        }
        if (source.ring().variableCount() != 1
                || source.isConstant()
                || source.leadingCoefficient().signum() <= 0
                || !primitive(source)) {
            return failure(
                ZassenhausRecombinationResult.Status.UNSUPPORTED_SHAPE,
                "REQUIRES_POSITIVE_LEADING_PRIMITIVE_UNIVARIATE_INTEGER_POLYNOMIAL",
                null,
                work,
                request,
                selection,
                lifting,
                policy,
                audit);
        }
        if (!selection.completed() || !lifting.completed()) {
            return failure(
                ZassenhausRecombinationResult.Status.UNSUPPORTED_SHAPE,
                "REQUIRES_COMPLETED_SELECTION_AND_HENSEL_LIFT",
                null,
                work,
                request,
                selection,
                lifting,
                policy,
                audit);
        }
        if (!lifting.selectionCertificateHash().equals(
                selection.certificateHash())) {
            return technicalFailure(
                "ZASSENHAUS_SELECTION_LIFT_CERTIFICATE_MISMATCH",
                null,
                work,
                request,
                selection,
                lifting,
                policy,
                audit);
        }
        if (!selection.sourceRequestHash().equals(
                AlgorithmEvidence.sha256(
                    request.canonicalMaterial()))) {
            return technicalFailure(
                "ZASSENHAUS_SOURCE_REQUEST_MISMATCH",
                null,
                work,
                request,
                selection,
                lifting,
                policy,
                audit);
        }
        return null;
    }

    private static ZassenhausRecombinationResult execute(
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingResult lifting,
        ZassenhausRecombinationPolicy policy,
        BigInteger coefficientBound,
        PolynomialWorkBudget work,
        ZassenhausSearch.CandidateAudit audit
    ) {
        if (coefficientBound.signum() < 0) {
            throw new IllegalArgumentException(
                "coefficient bound must not be negative");
        }
        BigInteger modulus = lifting.targetModulus();
        work.consume("zassenhaus.precision.bound-check", 1);
        if (modulus.compareTo(
                coefficientBound.shiftLeft(1)) <= 0) {
            return inconclusive(
                "ZASSENHAUS_LIFT_MODULUS_INSUFFICIENT",
                coefficientBound,
                work,
                request,
                selection,
                lifting,
                policy,
                audit);
        }

        List<UnivariatePolynomialView<BigInteger>> remainingFactors =
            new ArrayList<>(
                IntegerPolynomialArithmetic.monicLiftedFactors(
                    request.source(),
                    lifting,
                    policy,
                    work));
        List<List<Integer>> remainingGroups =
            ZassenhausSearch.singletonGroups(
                remainingFactors.size());
        UnivariatePolynomialView<BigInteger> remainingSource =
            UnivariatePolynomialView.from(request.source());
        ArrayList<SparsePolynomial<BigInteger>> accepted =
            new ArrayList<>();
        ArrayList<List<Integer>> acceptedPartitions =
            new ArrayList<>();
        long candidates = 0;
        long candidateLimit = Math.min(
            policy.maxSubsetCandidates(),
            Math.max(
                0,
                (long) request.maxCandidates()
                    - selection.attempts().size()));

        while (remainingFactors.size() > 1
                && remainingSource.degree() > 1) {
            ZassenhausSearch.SearchResult search =
                ZassenhausSearch.findFactor(
                    remainingSource,
                    remainingFactors,
                    remainingGroups,
                    coefficientBound,
                    modulus,
                    policy,
                    work,
                    candidates,
                    candidateLimit,
                    audit);
            candidates = search.candidatesConsidered();
            if (!search.found()) {
                if (!search.exhaustive()) {
                    return inconclusive(
                        search.detailCode(),
                        coefficientBound,
                        work,
                        request,
                        selection,
                        lifting,
                        policy,
                        audit);
                }
                break;
            }
            accepted.add(search.factor().toSparsePolynomial());
            acceptedPartitions.add(search.partition());
            remainingSource = search.quotient();
            remainingFactors = ZassenhausSearch.removeSelected(
                remainingFactors,
                search.selectedPositions());
            remainingGroups = ZassenhausSearch.removeSelected(
                remainingGroups,
                search.selectedPositions());
        }

        accepted.add(remainingSource.toSparsePolynomial());
        acceptedPartitions.add(remainingGroups.stream()
            .flatMap(List::stream)
            .sorted()
            .toList());

        List<Integer> order = java.util.stream.IntStream
            .range(0, accepted.size())
            .boxed()
            .sorted(Comparator.comparing(index ->
                accepted.get(index).canonicalMaterial()))
            .toList();
        List<SparsePolynomial<BigInteger>> orderedFactors =
            order.stream().map(accepted::get).toList();
        List<List<Integer>> orderedPartitions =
            order.stream().map(acceptedPartitions::get).toList();

        IntegerPolynomialArithmetic.verifyProduct(
            request.source(),
            orderedFactors,
            policy,
            work);
        return ZassenhausRecombinationResult.completed(
            coefficientBound,
            modulus,
            orderedFactors,
            orderedPartitions,
            candidates,
            audit.hash(),
            work.ledger(),
            request,
            selection,
            lifting,
            policy);
    }

    private static boolean primitive(
        SparsePolynomial<BigInteger> polynomial
    ) {
        BigInteger gcd = BigInteger.ZERO;
        for (BigInteger coefficient : polynomial.terms().values()) {
            gcd = gcd.gcd(coefficient.abs());
        }
        return BigInteger.ONE.equals(gcd);
    }

    private static PolynomialWorkBudget resume(
        long limit,
        PolynomialWorkLedger prefix
    ) {
        PolynomialWorkBudget work = new PolynomialWorkBudget(limit);
        prefix.stages().forEach(work::consume);
        return work;
    }

    private static ZassenhausRecombinationResult inconclusive(
        String detailCode,
        BigInteger coefficientBound,
        PolynomialWorkBudget work,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingResult lifting,
        ZassenhausRecombinationPolicy policy,
        ZassenhausSearch.CandidateAudit audit
    ) {
        return failure(
            ZassenhausRecombinationResult.Status.BUDGET_INCONCLUSIVE,
            detailCode,
            coefficientBound,
            work,
            request,
            selection,
            lifting,
            policy,
            audit);
    }

    private static ZassenhausRecombinationResult technicalFailure(
        String detailCode,
        BigInteger coefficientBound,
        PolynomialWorkBudget work,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingResult lifting,
        ZassenhausRecombinationPolicy policy,
        ZassenhausSearch.CandidateAudit audit
    ) {
        return failure(
            ZassenhausRecombinationResult.Status.TECHNICAL_FAILURE,
            detailCode,
            coefficientBound,
            work,
            request,
            selection,
            lifting,
            policy,
            audit);
    }

    private static ZassenhausRecombinationResult failure(
        ZassenhausRecombinationResult.Status status,
        String detailCode,
        BigInteger coefficientBound,
        PolynomialWorkBudget work,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingResult lifting,
        ZassenhausRecombinationPolicy policy,
        ZassenhausSearch.CandidateAudit audit
    ) {
        return ZassenhausRecombinationResult.failure(
            status,
            detailCode,
            coefficientBound,
            audit.count(),
            audit.hash(),
            work.ledger(),
            request,
            selection,
            lifting,
            policy);
    }

    private static String technicalDetail(RuntimeException exception) {
        if (exception
                instanceof IntegerPolynomialArithmetic.AlgorithmFailure
                    failure) {
            return failure.detailCode();
        }
        if (exception instanceof ArithmeticException) {
            return "ZASSENHAUS_EXACT_ARITHMETIC_FAILED";
        }
        return "ZASSENHAUS_"
            + exception.getClass().getSimpleName()
                .toUpperCase(java.util.Locale.ROOT);
    }
}
