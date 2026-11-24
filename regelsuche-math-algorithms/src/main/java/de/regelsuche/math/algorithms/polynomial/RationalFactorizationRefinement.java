package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.FactorizationVerifier;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Refines proper factors and retains each unresolved factor as part of the exact product. */
final class RationalFactorizationRefinement implements FactorizationEngine<ExactRational> {
    private static final String ENGINE_ID = "regelsuche.factorization.native-univariate-rational-refinement/v1";

    private final NativeUnivariateFactorizationPolicy policy;
    private final FactorizationEngine<ExactRational> base;

    RationalFactorizationRefinement(NativeUnivariateFactorizationPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
        this.base = NativeUnivariateFactorizationEngine.rationalsWithFastPath(policy);
    }

    @Override public String engineId() { return ENGINE_ID; }
    @Override public String coefficientDomainId() { return ExactRationalField.DOMAIN_ID; }

    @Override
    public EngineResult<ExactRational> propose(FactorizationRequest<ExactRational> request) {
        var initial = base.propose(request);
        var source = request.source();
        var certificates = new ArrayList<>(List.of(initial.engineResultHash()));
        if (request.evidenceRequirement() != FactorizationRequest.EvidenceRequirement.VERIFIED_DECOMPOSITION
                || initial.outcome() != Outcome.CANDIDATES || source.ring().variableCount() != 1) {
            return receipt(request, initial, initial.work(), initial.proposals(), certificates);
        }

        var seed = initial.proposals().getFirst();
        var pending = new ArrayDeque<>(factors(seed.factors(), seed.unresolvedRemainder()));
        int sourceDegree = source.degree(0);
        // The outer product verifier charges at most two operations per total linear degree,
        // plus comparison. Each power needs at most as many multiplications as its exponent.
        long finalVerificationReserve = 2L * sourceDegree + 1;
        var work = new PolynomialWorkBudget(request.maxWorkUnits());
        initial.work().stages().forEach(work::consume);
        var retained = new ArrayList<PolynomialFactor<ExactRational>>();
        var unit = seed.unit();
        boolean changed = false;
        while (!pending.isEmpty()) {
            var factor = pending.removeFirst();
            var polynomial = factor.polynomial();
            int degree = polynomial.degree(0);
            // A successful proper split has at most degree many nonconstant terms in the factor list.
            long continuation = degree + 2L;
            long available = request.maxWorkUnits() - work.totalWorkUnits()
                - finalVerificationReserve - continuation;
            if (degree <= 1 || degree >= sourceDegree || available < 2) {
                retained.add(factor);
                continue;
            }
            work.consume("native-rational-refinement.factor-visits", 1);
            var childRequest = FactorizationRequest.verifiedDecomposition(polynomial, request.structuralLimits(),
                1, available - 1);
            var report = FactorizationVerifier.execute(base, childRequest);
            report.work().stages().forEach(work::consume);
            certificates.add(report.verificationHash());
            if (!report.successful()) {
                retained.add(factor);
                continue;
            }
            var candidate = report.candidates().getFirst();
            var split = factors(candidate.factors(), candidate.unresolvedRemainder());
            if (split.stream().anyMatch(child -> child.polynomial().degree(0) >= degree)) {
                retained.add(factor);
                continue;
            }
            work.consume("native-rational-refinement.factor-splices", split.size());
            work.consume("native-rational-refinement.unit-combinations", 1);
            unit = unit.multiply(candidate.unit().pow(factor.multiplicity()));
            for (var child : split) {
                pending.addLast(new PolynomialFactor<>(child.polynomial(),
                    Math.multiplyExact(factor.multiplicity(), child.multiplicity())));
            }
            changed = true;
        }

        var ledger = work.ledger();
        if (!changed) return receipt(request, initial, ledger, initial.proposals(), certificates);
        String proof = NativeEngineEvidence.proposalCertificate(request, policy, ENGINE_ID, unit, retained, ledger, certificates);
        var proposal = new Proposal<>(unit, retained, SparsePolynomial.one(source.ring()), proof);
        return NativeEngineEvidence.result(request, policy, ENGINE_ID, Outcome.CANDIDATES,
            "NATIVE_RATIONAL_REFINED_PRODUCT_PROPOSAL", ledger, List.of(proposal), BackendClaim.NONE, certificates);
    }

    private EngineResult<ExactRational> receipt(FactorizationRequest<ExactRational> request,
            EngineResult<ExactRational> initial, PolynomialWorkLedger ledger, List<Proposal<ExactRational>> proposals,
            List<String> certificates) {
        return NativeEngineEvidence.result(request, policy, ENGINE_ID, initial.outcome(), initial.detailCode(),
            ledger, proposals, BackendClaim.NONE, certificates);
    }

    private static List<PolynomialFactor<ExactRational>> factors(List<PolynomialFactor<ExactRational>> factors,
            SparsePolynomial<ExactRational> remainder) {
        if (remainder.isOne()) return factors;
        var all = new ArrayList<>(factors);
        all.add(new PolynomialFactor<>(remainder, 1));
        return all;
    }
}
