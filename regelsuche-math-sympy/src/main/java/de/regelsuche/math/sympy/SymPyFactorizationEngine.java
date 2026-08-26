package de.regelsuche.math.sympy;

import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.SparsePolynomial;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Common exact proposal and evidence mapping for all SymPy transports. */
abstract class SymPyFactorizationEngine<C>
        implements FactorizationEngine<C> {
    private final String engineId;
    private final SymPyFactorizationCodec<C> codec;
    private final SymPyFactorizationPolicy policy;
    private final AtomicReference<SymPyExecutionMetrics> lastMetrics =
        new AtomicReference<>();

    SymPyFactorizationEngine(
        String engineId,
        SymPyFactorizationCodec<C> codec,
        SymPyFactorizationPolicy policy
    ) {
        if (engineId == null || engineId.isBlank()) {
            throw new IllegalArgumentException(
                "SymPy engineId must not be blank");
        }
        this.engineId = engineId;
        this.codec = Objects.requireNonNull(codec, "codec");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public final String engineId() {
        return engineId;
    }

    @Override
    public final String coefficientDomainId() {
        return codec.coefficientDomainId();
    }

    public final SymPyFactorizationPolicy policy() {
        return policy;
    }

    public final Optional<SymPyExecutionMetrics> lastExecutionMetrics() {
        return Optional.ofNullable(lastMetrics.get());
    }

    @Override
    public final EngineResult<C> propose(
        FactorizationRequest<C> request
    ) {
        Objects.requireNonNull(request, "request");
        lastMetrics.set(null);
        Work work = new Work(request.maxWorkUnits());
        String structuralViolation =
            request.structuralViolation().orElse(null);
        if (structuralViolation != null) {
            return result(
                request,
                Outcome.BUDGET_INCONCLUSIVE,
                structuralViolation,
                work.ledger(),
                List.of(),
                BackendClaim.NONE,
                "",
                "");
        }
        if (!coefficientDomainId().equals(
                request.source().ring().coefficientDomain().id())) {
            return result(
                request,
                Outcome.UNSUPPORTED_DOMAIN,
                "REQUIRES_" + coefficientDomainId(),
                work.ledger(),
                List.of(),
                BackendClaim.NONE,
                "",
                "");
        }
        if (request.source().isConstant()) {
            return result(
                request,
                Outcome.UNSUPPORTED_REQUEST,
                "SYMPY_REQUIRES_NONCONSTANT_POLYNOMIAL",
                work.ledger(),
                List.of(),
                BackendClaim.NONE,
                "",
                "");
        }
        if (request.maxCandidates() == 0) {
            return result(
                request,
                Outcome.BUDGET_INCONCLUSIVE,
                "MAX_CANDIDATES_IS_ZERO",
                work.ledger(),
                List.of(),
                BackendClaim.NONE,
                "",
                "");
        }

        SymPyFactorizationCodec.Encoded encoded;
        try {
            work.consume(
                "sympy.encode.source-terms",
                request.source().termCount());
            encoded = codec.encode(request);
        } catch (Work.LimitReached exception) {
            return budgetFailure(
                request,
                work,
                "SYMPY_ADAPTER_WORK_BUDGET_EXCEEDED");
        } catch (RuntimeException exception) {
            return technicalFailure(
                request,
                work,
                "SYMPY_INPUT_ENCODING_FAILED",
                "",
                "");
        }
        String inputHash = SymPyEvidence.sha256(encoded.payload());
        if (encoded.byteLength() > policy.maxInputBytes()) {
            return result(
                request,
                Outcome.BUDGET_INCONCLUSIVE,
                "SYMPY_INPUT_SIZE_POLICY_EXCEEDED",
                work.ledger(),
                List.of(),
                BackendClaim.NONE,
                inputHash,
                "");
        }

        SymPyInvocation invocation;
        try {
            work.consume("sympy.invoke.calls", 1);
            invocation = invoke(encoded.payload());
        } catch (Work.LimitReached exception) {
            return budgetFailure(
                request,
                work,
                "SYMPY_ADAPTER_WORK_BUDGET_EXCEEDED");
        } catch (RuntimeException exception) {
            return technicalFailure(
                request,
                work,
                "SYMPY_TRANSPORT_EXCEPTION",
                inputHash,
                "");
        }
        if (invocation.status() != SymPyInvocation.Status.COMPLETED) {
            Outcome outcome = invocation.status()
                    == SymPyInvocation.Status.TIMEOUT
                ? Outcome.BUDGET_INCONCLUSIVE
                : Outcome.TECHNICAL_FAILURE;
            return result(
                request,
                outcome,
                invocation.detailCode(),
                work.ledger(),
                List.of(),
                BackendClaim.NONE,
                inputHash,
                invocationMaterial(invocation));
        }

        int outputBytes = invocation.output()
            .getBytes(StandardCharsets.UTF_8).length;
        if (outputBytes > policy.maxOutputBytes()) {
            return result(
                request,
                Outcome.BUDGET_INCONCLUSIVE,
                "SYMPY_OUTPUT_SIZE_POLICY_EXCEEDED",
                work.ledger(),
                List.of(),
                BackendClaim.NONE,
                inputHash,
                invocationMaterial(invocation));
        }

        SymPyFactorizationCodec.Decoded<C> decoded;
        try {
            decoded = codec.decode(
                invocation.output(),
                request.source(),
                policy);
            work.consume(
                "sympy.decode.factors",
                decoded.factors().size());
            work.consume(
                "sympy.decode.factor-terms",
                decoded.factorTerms());
            work.consume("sympy.issue.proposals", 1);
        } catch (Work.LimitReached exception) {
            return budgetFailure(
                request,
                work,
                "SYMPY_ADAPTER_WORK_BUDGET_EXCEEDED");
        } catch (RuntimeException exception) {
            return technicalFailure(
                request,
                work,
                "SYMPY_OUTPUT_DECODING_FAILED",
                inputHash,
                invocationMaterial(invocation));
        }

        String rawOutputHash =
            SymPyEvidence.sha256(invocation.output());
        String semanticOutputHash = SymPyEvidence.sha256(
            decoded.canonicalMaterial(request.source().ring()));
        lastMetrics.set(new SymPyExecutionMetrics(
            invocation.runtimeId(),
            invocation.runtimeVersion(),
            decoded.symPyVersion(),
            inputHash,
            rawOutputHash,
            SymPyScript.sourceHash(),
            invocation.coldStart(),
            invocation.initializationNanos(),
            invocation.invocationNanos(),
            decoded.factorNanos(),
            decoded.totalNanos()));
        if (!policy.expectedSymPyVersion().equals(
                decoded.symPyVersion())) {
            return technicalFailure(
                request,
                work,
                "SYMPY_VERSION_MISMATCH",
                inputHash,
                invocationMaterial(invocation));
        }

        String certificate = proposalCertificate(
            request,
            decoded,
            inputHash,
            semanticOutputHash,
            invocation);
        Proposal<C> proposal;
        try {
            proposal = new Proposal<>(
                decoded.unit(),
                decoded.factors(),
                SparsePolynomial.one(request.source().ring()),
                certificate);
        } catch (RuntimeException exception) {
            return technicalFailure(
                request,
                work,
                "SYMPY_PROPOSAL_CONTRACT_FAILED",
                inputHash,
                invocationMaterial(invocation));
        }
        return result(
            request,
            Outcome.CANDIDATES,
            "SYMPY_EXACT_FACTORIZATION_PROPOSAL",
            work.ledger(),
            List.of(proposal),
            BackendClaim.COMPLETE_FACTORIZATION,
            inputHash,
            successMaterial(
                invocation,
                decoded,
                semanticOutputHash));
    }

    abstract SymPyInvocation invoke(String payload);

    private EngineResult<C> budgetFailure(
        FactorizationRequest<C> request,
        Work work,
        String detailCode
    ) {
        return result(
            request,
            Outcome.BUDGET_INCONCLUSIVE,
            detailCode,
            work.ledger(),
            List.of(),
            BackendClaim.NONE,
            "",
            "");
    }

    private EngineResult<C> technicalFailure(
        FactorizationRequest<C> request,
        Work work,
        String detailCode,
        String inputHash,
        String transportMaterial
    ) {
        return result(
            request,
            Outcome.TECHNICAL_FAILURE,
            detailCode,
            work.ledger(),
            List.of(),
            BackendClaim.NONE,
            inputHash,
            transportMaterial);
    }

    private EngineResult<C> result(
        FactorizationRequest<C> request,
        Outcome outcome,
        String detailCode,
        PolynomialWorkLedger work,
        List<Proposal<C>> proposals,
        BackendClaim claim,
        String inputHash,
        String transportMaterial
    ) {
        StringBuilder material = new StringBuilder(engineId());
        SymPyEvidence.append(material, request.canonicalMaterial());
        SymPyEvidence.append(material, policy.canonicalMaterial());
        SymPyEvidence.append(material, SymPyScript.sourceHash());
        SymPyEvidence.append(material, outcome.name());
        SymPyEvidence.append(material, detailCode);
        SymPyEvidence.append(material, work.canonicalMaterial());
        SymPyEvidence.append(material, claim.name());
        SymPyEvidence.append(material, inputHash);
        SymPyEvidence.append(material, transportMaterial);
        proposals.forEach(proposal ->
            SymPyEvidence.append(material, proposal.canonicalMaterial()));
        return new EngineResult<>(
            engineId(),
            outcome,
            detailCode,
            work,
            proposals,
            claim,
            SymPyEvidence.sha256(material.toString()));
    }

    private String proposalCertificate(
        FactorizationRequest<C> request,
        SymPyFactorizationCodec.Decoded<C> decoded,
        String inputHash,
        String semanticOutputHash,
        SymPyInvocation invocation
    ) {
        StringBuilder material = new StringBuilder(engineId());
        SymPyEvidence.append(material, request.canonicalMaterial());
        SymPyEvidence.append(material, policy.canonicalMaterial());
        SymPyEvidence.append(material, SymPyScript.sourceHash());
        SymPyEvidence.append(material, inputHash);
        SymPyEvidence.append(material, semanticOutputHash);
        SymPyEvidence.append(material, invocation.runtimeId());
        SymPyEvidence.append(material, invocation.runtimeVersion());
        SymPyEvidence.append(
            material,
            decoded.canonicalMaterial(request.source().ring()));
        return SymPyEvidence.sha256(material.toString());
    }

    private static String invocationMaterial(
        SymPyInvocation invocation
    ) {
        StringBuilder material = new StringBuilder();
        SymPyEvidence.append(material, invocation.runtimeId());
        SymPyEvidence.append(material, invocation.runtimeVersion());
        SymPyEvidence.append(material, invocation.status().name());
        SymPyEvidence.append(material, invocation.detailCode());
        return material.toString();
    }

    private static <C> String successMaterial(
        SymPyInvocation invocation,
        SymPyFactorizationCodec.Decoded<C> decoded,
        String semanticOutputHash
    ) {
        StringBuilder material = new StringBuilder(
            invocationMaterial(invocation));
        SymPyEvidence.append(material, decoded.symPyVersion());
        SymPyEvidence.append(material, decoded.pythonImplementation());
        SymPyEvidence.append(material, decoded.pythonVersion());
        SymPyEvidence.append(material, semanticOutputHash);
        return material.toString();
    }

    private static final class Work {
        private final long limit;
        private final Map<String, Long> stages =
            new LinkedHashMap<>();
        private long total;

        private Work(long limit) {
            this.limit = limit;
        }

        private void consume(String stage, long units) {
            if (units < 0 || total > limit - units) {
                throw new LimitReached();
            }
            if (units == 0) {
                return;
            }
            total += units;
            stages.merge(stage, units, Math::addExact);
        }

        private PolynomialWorkLedger ledger() {
            return new PolynomialWorkLedger(stages);
        }

        private static final class LimitReached
                extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }
    }
}
