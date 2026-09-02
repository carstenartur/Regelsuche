package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Shared orchestration for complete bounded native univariate engines. */
final class NativeUnivariateFactorizationPipeline {
    static final String METHOD_ID =
        "regelsuche.native-univariate-factorization/v1";

    private NativeUnivariateFactorizationPipeline() {
    }

    static <C> FactorizationEngine.EngineResult<C> factor(
        FactorizationRequest<C> request,
        NativeUnivariateFactorizationPolicy policy,
        NativeCoefficientAdapter<C> adapter
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(adapter, "adapter");
        long engineWorkLimit = Math.min(
            request.maxWorkUnits(),
            policy.maxEngineWorkUnits()
        );
        FactorizationRequest<C> engineRequest = engineRequest(
            request,
            engineWorkLimit
        );
        PolynomialWorkBudget work =
            new PolynomialWorkBudget(engineWorkLimit);
        ArrayList<String> certificates = new ArrayList<>();

        FactorizationEngine.EngineResult<C> rejected =
            rejectInput(request, policy, adapter, work);
        if (rejected != null) {
            return rejected;
        }

        try {
            return execute(
                request,
                engineRequest,
                policy,
                adapter,
                work,
                certificates);
        } catch (PolynomialWorkBudget.LimitReached exception) {
            return failure(
                request,
                policy,
                adapter,
                FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE,
                "NATIVE_UNIVARIATE_WORK_BUDGET_EXCEEDED",
                work.ledger(),
                certificates);
        } catch (IntegerPolynomialArithmetic.LimitReached exception) {
            return failure(
                request,
                policy,
                adapter,
                FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE,
                exception.detailCode(),
                work.ledger(),
                certificates);
        } catch (ArithmeticException exception) {
            return failure(
                request,
                policy,
                adapter,
                FactorizationEngine.Outcome.TECHNICAL_FAILURE,
                "NATIVE_UNIVARIATE_EXACT_ARITHMETIC_FAILED",
                work.ledger(),
                certificates);
        } catch (RuntimeException exception) {
            return failure(
                request,
                policy,
                adapter,
                FactorizationEngine.Outcome.TECHNICAL_FAILURE,
                "NATIVE_UNIVARIATE_"
                    + exception.getClass().getSimpleName()
                        .toUpperCase(java.util.Locale.ROOT),
                work.ledger(),
                certificates);
        }
    }

    private static <C> FactorizationEngine.EngineResult<C> execute(
        FactorizationRequest<C> request,
        FactorizationRequest<C> engineRequest,
        NativeUnivariateFactorizationPolicy policy,
        NativeCoefficientAdapter<C> adapter,
        PolynomialWorkBudget work,
        ArrayList<String> certificates
    ) {
        UnivariateContentResult content = adapter.normalize(
            engineRequest,
            policy.contentPolicy(),
            work);
        certificates.add(content.certificateHash());
        if (!content.completed()) {
            return failure(
                request,
                policy,
                adapter,
                map(content.status()),
                content.detailCode(),
                work.ledger(),
                certificates);
        }

        SparsePolynomial<BigInteger> primitive =
            content.primitivePart();
        if (primitive.isConstant()) {
            return result(
                request,
                policy,
                adapter,
                FactorizationEngine.Outcome.NO_CANDIDATE,
                "NATIVE_UNIVARIATE_CONSTANT_UNIT_ONLY",
                work.ledger(),
                List.of(),
                FactorizationEngine.BackendClaim.NONE,
                certificates);
        }

        FactorizationRequest.StructuralLimits internalLimits =
            internalLimits(
                engineRequest.structuralLimits(),
                primitive);
        PolynomialRing<ExactRational> rationalRing =
            new PolynomialRing<>(
                ExactRationalField.INSTANCE,
                request.source().ring().variables(),
                request.source().ring().monomialOrder());
        SparsePolynomial<ExactRational> rationalPrimitive =
            toRational(primitive, rationalRing, work);

        SquareFreeDecomposition.Result<ExactRational> squareFree =
            SquareFreeDecomposition.decompose(
                rationalPrimitive,
                internalLimits,
                remainingWork(engineRequest, work));
        mergeWork(work, squareFree.work());
        certificates.add(squareFree.certificateHash());
        if (!squareFree.completed()) {
            return failure(
                request,
                policy,
                adapter,
                map(squareFree.status()),
                squareFree.detailCode(),
                work.ledger(),
                certificates);
        }

        ExactRational unit = content.scalar()
            .multiply(squareFree.unit());
        ArrayList<PolynomialFactor<BigInteger>> integerFactors =
            new ArrayList<>();
        int remainingCandidates = engineRequest.maxCandidates();

        for (PolynomialFactor<ExactRational> layer :
                squareFree.factors()) {
            FactorizationRequest<ExactRational> layerRequest =
                new FactorizationRequest<>(
                    layer.polynomial(),
                    FactorizationRequest.EvidenceRequirement
                        .VERIFIED_DECOMPOSITION,
                    internalLimits,
                    remainingCandidates,
                    engineRequest.maxWorkUnits());
            UnivariateContentResult layerContent =
                UnivariateContentNormalization.normalizeRational(
                    layerRequest,
                    policy.contentPolicy(),
                    work);
            certificates.add(layerContent.certificateHash());
            if (!layerContent.completed()) {
                return failure(
                    request,
                    policy,
                    adapter,
                    map(layerContent.status()),
                    layerContent.detailCode(),
                    work.ledger(),
                    certificates);
            }

            unit = unit.multiply(
                layerContent.scalar().pow(
                    layer.multiplicity()));
            SparsePolynomial<BigInteger> layerPrimitive =
                layerContent.primitivePart();
            FactorizationRequest.StructuralLimits layerLimits =
                internalLimits(internalLimits, layerPrimitive);

            if (layerPrimitive.degree(0) <= 1) {
                integerFactors.add(new PolynomialFactor<>(
                    layerPrimitive,
                    layer.multiplicity()));
                continue;
            }

            NativeSquareFreeLayerFactorization.Result layerResult =
                NativeSquareFreeLayerFactorization.factor(
                    layerPrimitive,
                    layerLimits,
                    engineRequest.maxWorkUnits(),
                    policy,
                    remainingCandidates,
                    work);
            certificates.addAll(layerResult.certificates());
            if (!layerResult.completed()) {
                return failure(
                    request,
                    policy,
                    adapter,
                    layerResult.outcome(),
                    layerResult.detailCode(),
                    work.ledger(),
                    certificates);
            }
            remainingCandidates = Math.subtractExact(
                remainingCandidates,
                layerResult.candidatesUsed());
            for (SparsePolynomial<BigInteger> factor :
                    layerResult.factors()) {
                integerFactors.add(new PolynomialFactor<>(
                    factor,
                    layer.multiplicity()));
            }
        }

        C targetUnit = adapter.targetUnit(unit);
        List<PolynomialFactor<C>> targetFactors =
            integerFactors.stream()
                .map(factor -> new PolynomialFactor<>(
                    adapter.targetFactor(
                        factor.polynomial(),
                        request.source().ring()),
                    factor.multiplicity()))
                .toList();

        if (NativeEngineEvidence.trivialAssociate(
                request.source(),
                targetUnit,
                targetFactors,
                work)) {
            return result(
                request,
                policy,
                adapter,
                FactorizationEngine.Outcome.NO_CANDIDATE,
                "NATIVE_UNIVARIATE_IRREDUCIBILITY_CLAIM",
                work.ledger(),
                List.of(),
                FactorizationEngine.BackendClaim.IRREDUCIBLE,
                certificates);
        }

        String proposalCertificate =
            NativeEngineEvidence.proposalCertificate(
                request,
                policy,
                adapter.engineId(),
                targetUnit,
                targetFactors,
                work.ledger(),
                certificates);
        FactorizationEngine.Proposal<C> proposal =
            new FactorizationEngine.Proposal<>(
                targetUnit,
                targetFactors,
                SparsePolynomial.one(request.source().ring()),
                proposalCertificate);
        return result(
            request,
            policy,
            adapter,
            FactorizationEngine.Outcome.CANDIDATES,
            "NATIVE_UNIVARIATE_COMPLETE_FACTORIZATION_PROPOSAL",
            work.ledger(),
            List.of(proposal),
            FactorizationEngine.BackendClaim.COMPLETE_FACTORIZATION,
            certificates);
    }

    private static <C> FactorizationRequest<C> engineRequest(
        FactorizationRequest<C> request,
        long maxWorkUnits
    ) {
        if (maxWorkUnits == request.maxWorkUnits()) {
            return request;
        }
        return new FactorizationRequest<>(
            request.source(),
            request.evidenceRequirement(),
            request.structuralLimits(),
            request.maxCandidates(),
            maxWorkUnits
        );
    }

    private static <C> FactorizationEngine.EngineResult<C> rejectInput(
        FactorizationRequest<C> request,
        NativeUnivariateFactorizationPolicy policy,
        NativeCoefficientAdapter<C> adapter,
        PolynomialWorkBudget work
    ) {
        if (!adapter.domainId().equals(
                request.source().ring().coefficientDomain().id())) {
            return result(
                request,
                policy,
                adapter,
                FactorizationEngine.Outcome.UNSUPPORTED_DOMAIN,
                "NATIVE_UNIVARIATE_COEFFICIENT_DOMAIN_MISMATCH",
                work.ledger(),
                List.of(),
                FactorizationEngine.BackendClaim.NONE,
                List.of());
        }
        String structuralViolation =
            request.structuralViolation().orElse(null);
        if (structuralViolation != null) {
            return result(
                request,
                policy,
                adapter,
                FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE,
                structuralViolation,
                work.ledger(),
                List.of(),
                FactorizationEngine.BackendClaim.NONE,
                List.of());
        }
        if (request.source().ring().variableCount() != 1) {
            return result(
                request,
                policy,
                adapter,
                FactorizationEngine.Outcome.UNSUPPORTED_REQUEST,
                "NATIVE_UNIVARIATE_REQUIRES_ONE_VARIABLE",
                work.ledger(),
                List.of(),
                FactorizationEngine.BackendClaim.NONE,
                List.of());
        }
        return null;
    }

    private static FactorizationRequest.StructuralLimits internalLimits(
        FactorizationRequest.StructuralLimits sourceLimits,
        SparsePolynomial<?> polynomial
    ) {
        return new FactorizationRequest.StructuralLimits(
            Math.max(
                sourceLimits.maxVariables(),
                polynomial.ring().variableCount()),
            Math.max(
                sourceLimits.maxTotalDegree(),
                polynomial.totalDegree()),
            Math.max(
                sourceLimits.maxTerms(),
                polynomial.termCount()),
            Math.max(
                sourceLimits.maxCoefficientBitLength(),
                polynomial.maxCoefficientBitLength()));
    }

    private static long remainingWork(
        FactorizationRequest<?> request,
        PolynomialWorkBudget work
    ) {
        long remaining = request.maxWorkUnits()
            - work.ledger().totalWorkUnits();
        if (remaining < 1) {
            throw new PolynomialWorkBudget.LimitReached();
        }
        return remaining;
    }

    private static void mergeWork(
        PolynomialWorkBudget target,
        PolynomialWorkLedger addition
    ) {
        addition.stages().forEach(target::consume);
    }

    private static SparsePolynomial<ExactRational> toRational(
        SparsePolynomial<BigInteger> source,
        PolynomialRing<ExactRational> targetRing,
        PolynomialWorkBudget work
    ) {
        NavigableMap<Monomial, ExactRational> terms =
            new TreeMap<>(targetRing.monomialComparator());
        for (Map.Entry<Monomial, BigInteger> term :
                source.terms().entrySet()) {
            work.consume(
                "native-univariate.integer-to-rational",
                1);
            terms.put(
                term.getKey(),
                ExactRational.integer(term.getValue()));
        }
        return new SparsePolynomial<>(targetRing, terms);
    }

    private static <C> FactorizationEngine.EngineResult<C> failure(
        FactorizationRequest<C> request,
        NativeUnivariateFactorizationPolicy policy,
        NativeCoefficientAdapter<C> adapter,
        FactorizationEngine.Outcome outcome,
        String detailCode,
        PolynomialWorkLedger work,
        List<String> certificates
    ) {
        return result(
            request,
            policy,
            adapter,
            outcome,
            detailCode,
            work,
            List.of(),
            FactorizationEngine.BackendClaim.NONE,
            certificates);
    }

    private static <C> FactorizationEngine.EngineResult<C> result(
        FactorizationRequest<C> request,
        NativeUnivariateFactorizationPolicy policy,
        NativeCoefficientAdapter<C> adapter,
        FactorizationEngine.Outcome outcome,
        String detailCode,
        PolynomialWorkLedger work,
        List<FactorizationEngine.Proposal<C>> proposals,
        FactorizationEngine.BackendClaim claim,
        List<String> certificates
    ) {
        return NativeEngineEvidence.result(
            request,
            policy,
            adapter.engineId(),
            outcome,
            detailCode,
            work,
            proposals,
            claim,
            certificates);
    }

    private static FactorizationEngine.Outcome map(
        UnivariateContentResult.Status status
    ) {
        return switch (status) {
            case COMPLETED -> throw new IllegalArgumentException(
                "completed status cannot map to failure");
            case UNSUPPORTED_DOMAIN ->
                FactorizationEngine.Outcome.UNSUPPORTED_DOMAIN;
            case UNSUPPORTED_SHAPE ->
                FactorizationEngine.Outcome.UNSUPPORTED_REQUEST;
            case BUDGET_INCONCLUSIVE ->
                FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE;
            case TECHNICAL_FAILURE ->
                FactorizationEngine.Outcome.TECHNICAL_FAILURE;
        };
    }

    private static FactorizationEngine.Outcome map(
        SquareFreeDecomposition.Status status
    ) {
        return switch (status) {
            case COMPLETED -> throw new IllegalArgumentException(
                "completed status cannot map to failure");
            case UNSUPPORTED_DOMAIN ->
                FactorizationEngine.Outcome.UNSUPPORTED_DOMAIN;
            case UNSUPPORTED_SHAPE ->
                FactorizationEngine.Outcome.UNSUPPORTED_REQUEST;
            case BUDGET_INCONCLUSIVE ->
                FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE;
            case TECHNICAL_FAILURE ->
                FactorizationEngine.Outcome.TECHNICAL_FAILURE;
        };
    }
}
