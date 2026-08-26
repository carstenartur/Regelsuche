package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.SparsePolynomial;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic linear multifactor Hensel lifting from p to an explicit p^k.
 */
public final class HenselLifting {
    public static final String METHOD_ID =
        "regelsuche.hensel-lifting/v1";

    private HenselLifting() {
    }

    public static HenselLiftingResult lift(
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingPolicy policy
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(policy, "policy");
        if (!selection.work().within(request.maxWorkUnits())) {
            return failure(
                HenselLiftingResult.Status.TECHNICAL_FAILURE,
                "HENSEL_SELECTION_WORK_EXCEEDS_REQUEST_BUDGET",
                List.of(),
                selection.work(),
                request,
                selection,
                policy);
        }
        PolynomialWorkBudget work = new PolynomialWorkBudget(
            request.maxWorkUnits());
        selection.work().stages().forEach(
            (stage, units) -> work.consume(stage, units));
        return lift(request, selection, policy, work);
    }

    static HenselLiftingResult lift(
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingPolicy policy,
        PolynomialWorkBudget work
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(work, "work");
        ArrayList<HenselLiftStep> steps =
            new ArrayList<>();

        HenselLiftingResult rejected = rejectInput(
            request,
            selection,
            policy,
            work);
        if (rejected != null) {
            return rejected;
        }

        try {
            return HenselLiftExecution.execute(
                request,
                selection,
                policy,
                work,
                steps);
        } catch (PolynomialWorkBudget.LimitReached exception) {
            return failure(
                HenselLiftingResult.Status.BUDGET_INCONCLUSIVE,
                "HENSEL_LIFTING_WORK_BUDGET_EXCEEDED",
                steps,
                work.ledger(),
                request,
                selection,
                policy);
        } catch (RepresentationLimitReached exception) {
            return failure(
                HenselLiftingResult.Status.BUDGET_INCONCLUSIVE,
                exception.detailCode(),
                steps,
                work.ledger(),
                request,
                selection,
                policy);
        } catch (AlgorithmFailure exception) {
            return failure(
                HenselLiftingResult.Status.TECHNICAL_FAILURE,
                exception.detailCode(),
                steps,
                work.ledger(),
                request,
                selection,
                policy);
        } catch (ArithmeticException exception) {
            return failure(
                HenselLiftingResult.Status.TECHNICAL_FAILURE,
                "HENSEL_EXACT_ARITHMETIC_FAILED",
                steps,
                work.ledger(),
                request,
                selection,
                policy);
        } catch (RuntimeException exception) {
            return failure(
                HenselLiftingResult.Status.TECHNICAL_FAILURE,
                "HENSEL_"
                    + exception.getClass().getSimpleName()
                        .toUpperCase(java.util.Locale.ROOT),
                steps,
                work.ledger(),
                request,
                selection,
                policy);
        }
    }

    private static HenselLiftingResult rejectInput(
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingPolicy policy,
        PolynomialWorkBudget work
    ) {
        if (work.limit() != request.maxWorkUnits()
                || !work.ledger().equals(selection.work())) {
            return failure(
                HenselLiftingResult.Status.TECHNICAL_FAILURE,
                "HENSEL_WORK_BUDGET_AUTHORITY_MISMATCH",
                List.of(),
                work.ledger(),
                request,
                selection,
                policy);
        }
        String structuralViolation =
            request.structuralViolation().orElse(null);
        if (structuralViolation != null) {
            return failure(
                HenselLiftingResult.Status.BUDGET_INCONCLUSIVE,
                structuralViolation,
                List.of(),
                work.ledger(),
                request,
                selection,
                policy);
        }
        SparsePolynomial<BigInteger> source = request.source();
        if (!(source.ring().coefficientDomain()
                instanceof BigIntegerDomain)) {
            return failure(
                HenselLiftingResult.Status.UNSUPPORTED_DOMAIN,
                "REQUIRES_EXACT_INTEGER_COEFFICIENT_DOMAIN",
                List.of(),
                work.ledger(),
                request,
                selection,
                policy);
        }
        if (source.ring().variableCount() != 1
                || source.isConstant()) {
            return failure(
                HenselLiftingResult.Status.UNSUPPORTED_SHAPE,
                "REQUIRES_NONCONSTANT_UNIVARIATE_INTEGER_POLYNOMIAL",
                List.of(),
                work.ledger(),
                request,
                selection,
                policy);
        }
        if (!selection.completed()) {
            return failure(
                HenselLiftingResult.Status.UNSUPPORTED_SHAPE,
                "REQUIRES_COMPLETED_SUITABLE_PRIME_SELECTION",
                List.of(),
                work.ledger(),
                request,
                selection,
                policy);
        }
        String requestHash = AlgorithmEvidence.sha256(
            request.canonicalMaterial());
        if (!selection.sourceRequestHash().equals(requestHash)) {
            return failure(
                HenselLiftingResult.Status.TECHNICAL_FAILURE,
                "HENSEL_SELECTION_SOURCE_MISMATCH",
                List.of(),
                work.ledger(),
                request,
                selection,
                policy);
        }
        if (selection.attempts().size() > request.maxCandidates()) {
            return failure(
                HenselLiftingResult.Status.TECHNICAL_FAILURE,
                "HENSEL_SELECTION_EXCEEDS_REQUEST_CANDIDATE_BUDGET",
                List.of(),
                work.ledger(),
                request,
                selection,
                policy);
        }
        return null;
    }

    private static HenselLiftingResult failure(
        HenselLiftingResult.Status status,
        String detailCode,
        List<HenselLiftStep> steps,
        PolynomialWorkLedger work,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingPolicy policy
    ) {
        return HenselLiftingResult.failure(
            status,
            detailCode,
            steps,
            work,
            request,
            selection,
            policy);
    }

    static final class RepresentationLimitReached
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String detailCode;

        RepresentationLimitReached(String detailCode) {
            this.detailCode = detailCode;
        }

        String detailCode() {
            return detailCode;
        }
    }

    static final class AlgorithmFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String detailCode;

        AlgorithmFailure(String detailCode) {
            this.detailCode = detailCode;
        }

        String detailCode() {
            return detailCode;
        }
    }
}
