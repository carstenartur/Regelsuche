package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.SparsePolynomial;
import java.util.List;

/** Canonical engine-result and proposal evidence for the native pipeline. */
final class NativeEngineEvidence {
    private NativeEngineEvidence() {
    }

    static <C> FactorizationEngine.EngineResult<C> result(
        FactorizationRequest<C> request,
        NativeUnivariateFactorizationPolicy policy,
        String engineId,
        FactorizationEngine.Outcome outcome,
        String detailCode,
        PolynomialWorkLedger work,
        List<FactorizationEngine.Proposal<C>> proposals,
        FactorizationEngine.BackendClaim claim,
        List<String> certificates
    ) {
        StringBuilder material = new StringBuilder(
            NativeUnivariateFactorizationPipeline.METHOD_ID);
        AlgorithmEvidence.append(material, engineId);
        AlgorithmEvidence.append(
            material,
            request.canonicalMaterial());
        AlgorithmEvidence.append(
            material,
            policy.canonicalMaterial());
        AlgorithmEvidence.append(material, outcome.name());
        AlgorithmEvidence.append(material, detailCode);
        AlgorithmEvidence.append(
            material,
            work.canonicalMaterial());
        AlgorithmEvidence.append(material, claim.name());
        certificates.forEach(certificate ->
            AlgorithmEvidence.append(material, certificate));
        proposals.forEach(proposal ->
            AlgorithmEvidence.append(
                material,
                proposal.canonicalMaterial()));
        return new FactorizationEngine.EngineResult<>(
            engineId,
            outcome,
            detailCode,
            work,
            proposals,
            claim,
            AlgorithmEvidence.sha256(material.toString()));
    }

    static <C> String proposalCertificate(
        FactorizationRequest<C> request,
        NativeUnivariateFactorizationPolicy policy,
        String engineId,
        C unit,
        List<PolynomialFactor<C>> factors,
        PolynomialWorkLedger work,
        List<String> certificates
    ) {
        StringBuilder material = new StringBuilder(
            NativeUnivariateFactorizationPipeline.METHOD_ID);
        AlgorithmEvidence.append(material, engineId);
        AlgorithmEvidence.append(
            material,
            request.canonicalMaterial());
        AlgorithmEvidence.append(
            material,
            policy.canonicalMaterial());
        AlgorithmEvidence.append(
            material,
            request.source().ring().coefficientDomain()
                .canonicalText(unit));
        factors.forEach(factor -> {
            AlgorithmEvidence.append(
                material,
                Integer.toString(factor.multiplicity()));
            AlgorithmEvidence.append(
                material,
                factor.polynomial().canonicalMaterial());
        });
        AlgorithmEvidence.append(
            material,
            work.canonicalMaterial());
        certificates.forEach(certificate ->
            AlgorithmEvidence.append(material, certificate));
        return AlgorithmEvidence.sha256(material.toString());
    }

    static <C> boolean trivialAssociate(
        SparsePolynomial<C> source,
        C unit,
        List<PolynomialFactor<C>> factors,
        PolynomialWorkBudget work
    ) {
        if (factors.size() != 1
                || factors.getFirst().multiplicity() != 1) {
            return false;
        }
        work.consume("native-univariate.trivial-associate.product", 1);
        SparsePolynomial<C> associate =
            factors.getFirst().polynomial().scale(unit);
        work.consume("native-univariate.trivial-associate.compare", 1);
        return source.equals(associate);
    }
}
