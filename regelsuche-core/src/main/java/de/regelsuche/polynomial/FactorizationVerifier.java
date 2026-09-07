package de.regelsuche.polynomial;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Executes one factorization engine and issues trusted evidence only after
 * request, engine-contract and exact-product verification.
 */
public final class FactorizationVerifier {
    public static final String VERIFIER_ID =
        "regelsuche.factorization-verifier/v2";

    private FactorizationVerifier() {
    }

    public static <C> Report<C> execute(
        FactorizationEngine<C> engine,
        FactorizationRequest<C> request
    ) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(request, "request");

        String structuralViolation =
            request.structuralViolation().orElse(null);
        if (structuralViolation != null) {
            return failure(
                engine.engineId(),
                Status.BUDGET_INCONCLUSIVE,
                structuralViolation,
                PolynomialWorkLedger.empty(),
                ClaimStrength.NONE,
                "",
                request);
        }
        if (!engine.coefficientDomainId().equals(
                request.source().ring().coefficientDomain().id())) {
            return failure(
                engine.engineId(),
                Status.UNSUPPORTED_DOMAIN,
                "ENGINE_COEFFICIENT_DOMAIN_MISMATCH",
                PolynomialWorkLedger.empty(),
                ClaimStrength.NONE,
                "",
                request);
        }

        FactorizationEngine.EngineResult<C> raw;
        try {
            raw = Objects.requireNonNull(
                engine.propose(request),
                "factorization engine result");
        } catch (RuntimeException exception) {
            return failure(
                engine.engineId(),
                Status.TECHNICAL_FAILURE,
                technicalDetail(exception),
                PolynomialWorkLedger.empty(),
                ClaimStrength.NONE,
                "",
                request);
        }

        Report<C> invalid = validateEngineContract(
            engine,
            request,
            raw);
        if (invalid != null) {
            return invalid;
        }
        return switch (raw.outcome()) {
            case CANDIDATES -> verifyCandidates(request, raw);
            case NO_CANDIDATE -> noCandidate(request, raw);
            case UNSUPPORTED_DOMAIN -> failure(
                raw.engineId(),
                Status.UNSUPPORTED_DOMAIN,
                raw.detailCode(),
                raw.work(),
                ClaimStrength.NONE,
                raw.engineResultHash(),
                request);
            case UNSUPPORTED_REQUEST -> failure(
                raw.engineId(),
                Status.UNSUPPORTED_REQUEST,
                raw.detailCode(),
                raw.work(),
                ClaimStrength.NONE,
                raw.engineResultHash(),
                request);
            case BUDGET_INCONCLUSIVE -> failure(
                raw.engineId(),
                Status.BUDGET_INCONCLUSIVE,
                raw.detailCode(),
                raw.work(),
                ClaimStrength.NONE,
                raw.engineResultHash(),
                request);
            case TECHNICAL_FAILURE -> failure(
                raw.engineId(),
                Status.TECHNICAL_FAILURE,
                raw.detailCode(),
                raw.work(),
                ClaimStrength.NONE,
                raw.engineResultHash(),
                request);
        };
    }

    private static <C> Report<C> validateEngineContract(
        FactorizationEngine<C> engine,
        FactorizationRequest<C> request,
        FactorizationEngine.EngineResult<C> raw
    ) {
        if (!engine.engineId().equals(raw.engineId())) {
            return failure(
                engine.engineId(),
                Status.TECHNICAL_FAILURE,
                "ENGINE_RESULT_ID_MISMATCH",
                raw.work(),
                ClaimStrength.NONE,
                raw.engineResultHash(),
                request);
        }
        if (!raw.work().within(request.maxWorkUnits())) {
            return failure(
                raw.engineId(),
                Status.TECHNICAL_FAILURE,
                "ENGINE_EXCEEDED_REQUEST_WORK_BUDGET",
                raw.work(),
                ClaimStrength.NONE,
                raw.engineResultHash(),
                request);
        }
        if (raw.proposals().size() > request.maxCandidates()) {
            return failure(
                raw.engineId(),
                Status.TECHNICAL_FAILURE,
                "ENGINE_EXCEEDED_REQUEST_CANDIDATE_BUDGET",
                raw.work(),
                ClaimStrength.NONE,
                raw.engineResultHash(),
                request);
        }
        return null;
    }

    private static <C> Report<C> verifyCandidates(
        FactorizationRequest<C> request,
        FactorizationEngine.EngineResult<C> raw
    ) {
        long remaining = request.maxWorkUnits()
            - raw.work().totalWorkUnits();
        WorkCounter verificationWork = new WorkCounter(remaining);
        List<VerifiedCandidate<C>> candidates = new ArrayList<>();
        for (FactorizationEngine.Proposal<C> proposal : raw.proposals()) {
            VerificationOutcome<C> outcome;
            try {
                outcome = verifyProposal(
                    request.source(),
                    proposal,
                    verificationWork);
            } catch (WorkLimitReached exception) {
                return failure(
                    raw.engineId(),
                    Status.BUDGET_INCONCLUSIVE,
                    "INDEPENDENT_PRODUCT_VERIFICATION_BUDGET_EXCEEDED",
                    merge(raw.work(), verificationWork.ledger()),
                    ClaimStrength.NONE,
                    raw.engineResultHash(),
                    request);
            }
            if (!outcome.verified()) {
                return failure(
                    raw.engineId(),
                    Status.TECHNICAL_FAILURE,
                    outcome.detailCode(),
                    merge(raw.work(), verificationWork.ledger()),
                    ClaimStrength.NONE,
                    raw.engineResultHash(),
                    request);
            }
            candidates.add(issueVerifiedCandidate(
                proposal,
                raw.backendClaim(),
                outcome.reconstructed(),
                request.source()));
        }

        PolynomialWorkLedger combined =
            merge(raw.work(), verificationWork.ledger());
        ClaimStrength claim = claimFor(raw.backendClaim());
        if (request.evidenceRequirement()
                == FactorizationRequest.EvidenceRequirement
                    .INDEPENDENT_COMPLETE) {
            return failure(
                raw.engineId(),
                Status.UNSUPPORTED_REQUEST,
                "INDEPENDENT_COMPLETENESS_VERIFIER_REQUIRED",
                combined,
                claim,
                raw.engineResultHash(),
                request);
        }
        return success(
            raw.engineId(),
            Status.PARTIAL_FACTORIZATION,
            raw.detailCode(),
            combined,
            claim,
            candidates,
            raw.engineResultHash(),
            request);
    }

    private static <C> Report<C> noCandidate(
        FactorizationRequest<C> request,
        FactorizationEngine.EngineResult<C> raw
    ) {
        ClaimStrength claim =
            raw.backendClaim() == FactorizationEngine.BackendClaim.IRREDUCIBLE
                ? ClaimStrength.BACKEND_CLAIMED_IRREDUCIBLE
                : ClaimStrength.NONE;
        if (request.evidenceRequirement()
                == FactorizationRequest.EvidenceRequirement
                    .INDEPENDENT_COMPLETE) {
            return independentlyVerifySource(request, raw, claim);
        }
        return failure(
            raw.engineId(),
            Status.NO_FACTORIZATION_FOUND,
            raw.detailCode(),
            raw.work(),
            claim,
            raw.engineResultHash(),
            request);
    }

    private static <C> Report<C> independentlyVerifySource(
        FactorizationRequest<C> request,
        FactorizationEngine.EngineResult<C> raw,
        ClaimStrength backendClaim
    ) {
        long remaining = request.maxWorkUnits()
            - raw.work().totalWorkUnits();
        IndependentOriginalDomainIrreducibility.Result evidence =
            IndependentOriginalDomainIrreducibility.verify(
                request,
                remaining);
        IndependentIrreducibilityTrace trace =
            issueIndependentTrace(evidence);
        PolynomialWorkLedger combined = merge(
            raw.work(),
            evidence.work());
        return switch (evidence.outcome()) {
            case CERTIFIED -> report(
                raw.engineId(),
                Status.IRREDUCIBLE,
                evidence.detailCode(),
                combined,
                ClaimStrength.INDEPENDENTLY_CERTIFIED_IRREDUCIBLE,
                List.of(),
                raw.engineResultHash(),
                request,
                Optional.of(trace));
            case UNSUPPORTED_DOMAIN -> report(
                raw.engineId(),
                Status.UNSUPPORTED_DOMAIN,
                evidence.detailCode(),
                combined,
                backendClaim,
                List.of(),
                raw.engineResultHash(),
                request,
                Optional.of(trace));
            case UNSUPPORTED_SHAPE -> report(
                raw.engineId(),
                Status.UNSUPPORTED_REQUEST,
                evidence.detailCode(),
                combined,
                backendClaim,
                List.of(),
                raw.engineResultHash(),
                request,
                Optional.of(trace));
            case NO_WITNESS_WITHIN_POLICY,
                    WORK_BUDGET_EXHAUSTED,
                    DEGREE_LIMIT_EXCEEDED,
                    NORMALIZATION_LIMIT_EXCEEDED -> report(
                raw.engineId(),
                Status.BUDGET_INCONCLUSIVE,
                evidence.detailCode(),
                combined,
                backendClaim,
                List.of(),
                raw.engineResultHash(),
                request,
                Optional.of(trace));
            case TECHNICAL_FAILURE -> report(
                raw.engineId(),
                Status.TECHNICAL_FAILURE,
                evidence.detailCode(),
                combined,
                backendClaim,
                List.of(),
                raw.engineResultHash(),
                request,
                Optional.of(trace));
        };
    }

    private static IndependentIrreducibilityTrace issueIndependentTrace(
        IndependentOriginalDomainIrreducibility.Result evidence
    ) {
        return new IndependentIrreducibilityTrace(
            evidence.methodId(),
            evidence.requestHash(),
            evidence.sourceHash(),
            evidence.outcome(),
            evidence.proofMethod(),
            evidence.detailCode(),
            evidence.normalizedPrimitiveCoefficients(),
            evidence.degree(),
            evidence.primeAttempts(),
            evidence.selectedPrime(),
            evidence.work(),
            evidence.traceHash());
    }

    private static <C> VerificationOutcome<C> verifyProposal(
        SparsePolynomial<C> source,
        FactorizationEngine.Proposal<C> proposal,
        WorkCounter work
    ) {
        if (!source.ring().equals(
                proposal.unresolvedRemainder().ring())
                || proposal.factors().stream().anyMatch(factor ->
                    !source.ring().equals(
                        factor.polynomial().ring()))) {
            return new VerificationOutcome<>(
                false,
                "FACTORIZATION_PROPOSAL_RING_MISMATCH",
                null);
        }
        SparsePolynomial<C> reconstructed = SparsePolynomial.constant(
            source.ring(),
            proposal.unit());
        for (PolynomialFactor<C> factor : proposal.factors()) {
            work.consume(
                "verify.factor-power-multiplications",
                powerMultiplications(factor.multiplicity()));
            SparsePolynomial<C> powered =
                factor.polynomial().pow(factor.multiplicity());
            work.consume(
                "verify.factor-product-multiplications",
                1);
            reconstructed = reconstructed.multiply(powered);
        }
        if (!proposal.unresolvedRemainder().isOne()) {
            work.consume("verify.remainder-multiplications", 1);
            reconstructed = reconstructed.multiply(
                proposal.unresolvedRemainder());
        }
        work.consume("verify.product-comparisons", 1);
        if (!source.equals(reconstructed)) {
            return new VerificationOutcome<>(
                false,
                "FACTORIZATION_PROPOSAL_PRODUCT_MISMATCH",
                reconstructed);
        }
        return new VerificationOutcome<>(
            true,
            "FACTORIZATION_PRODUCT_RECONSTRUCTED",
            reconstructed);
    }

    private static long powerMultiplications(int exponent) {
        int remaining = exponent;
        long result = 0;
        while (remaining > 0) {
            if ((remaining & 1) == 1) {
                result++;
            }
            remaining >>>= 1;
            if (remaining > 0) {
                result++;
            }
        }
        return result;
    }

    private static <C> VerifiedCandidate<C> issueVerifiedCandidate(
        FactorizationEngine.Proposal<C> proposal,
        FactorizationEngine.BackendClaim backendClaim,
        SparsePolynomial<C> reconstructed,
        SparsePolynomial<C> source
    ) {
        StringBuilder material = new StringBuilder(VERIFIER_ID);
        PolynomialEvidence.append(
            material,
            source.canonicalMaterial());
        PolynomialEvidence.append(
            material,
            proposal.canonicalMaterial());
        PolynomialEvidence.append(
            material,
            backendClaim.name());
        PolynomialEvidence.append(
            material,
            reconstructed.canonicalMaterial());
        return new VerifiedCandidate<>(
            proposal,
            backendClaim,
            PolynomialEvidence.sha256(material.toString()));
    }

    private static ClaimStrength claimFor(
        FactorizationEngine.BackendClaim backendClaim
    ) {
        return backendClaim
                == FactorizationEngine.BackendClaim.COMPLETE_FACTORIZATION
            ? ClaimStrength.BACKEND_CLAIMED_COMPLETE
            : ClaimStrength.VERIFIED_DECOMPOSITION;
    }

    private static PolynomialWorkLedger merge(
        PolynomialWorkLedger first,
        PolynomialWorkLedger second
    ) {
        Map<String, Long> merged =
            new LinkedHashMap<>(first.stages());
        second.stages().forEach((stage, units) -> merged.merge(
            stage,
            units,
            Math::addExact));
        return new PolynomialWorkLedger(merged);
    }

    private static <C> Report<C> success(
        String engineId,
        Status status,
        String detailCode,
        PolynomialWorkLedger work,
        ClaimStrength claimStrength,
        List<VerifiedCandidate<C>> candidates,
        String engineResultHash,
        FactorizationRequest<C> request
    ) {
        return report(
            engineId,
            status,
            detailCode,
            work,
            claimStrength,
            candidates,
            engineResultHash,
            request,
            Optional.empty());
    }

    private static <C> Report<C> failure(
        String engineId,
        Status status,
        String detailCode,
        PolynomialWorkLedger work,
        ClaimStrength claimStrength,
        String engineResultHash,
        FactorizationRequest<C> request
    ) {
        return report(
            engineId,
            status,
            detailCode,
            work,
            claimStrength,
            List.of(),
            engineResultHash,
            request,
            Optional.empty());
    }

    private static <C> Report<C> report(
        String engineId,
        Status status,
        String detailCode,
        PolynomialWorkLedger work,
        ClaimStrength claimStrength,
        List<VerifiedCandidate<C>> candidates,
        String engineResultHash,
        FactorizationRequest<C> request,
        Optional<IndependentIrreducibilityTrace> independentTrace
    ) {
        String verificationHash = reportHash(
            engineId,
            status,
            detailCode,
            work,
            claimStrength,
            candidates,
            engineResultHash,
            request,
            independentTrace);
        return new Report<>(
            engineId,
            status,
            detailCode,
            work,
            claimStrength,
            candidates,
            engineResultHash,
            verificationHash,
            independentTrace);
    }

    private static <C> String reportHash(
        String engineId,
        Status status,
        String detailCode,
        PolynomialWorkLedger work,
        ClaimStrength claimStrength,
        List<VerifiedCandidate<C>> candidates,
        String engineResultHash,
        FactorizationRequest<C> request,
        Optional<IndependentIrreducibilityTrace> independentTrace
    ) {
        StringBuilder material = new StringBuilder(VERIFIER_ID);
        PolynomialEvidence.append(material, engineId);
        PolynomialEvidence.append(material, status.name());
        PolynomialEvidence.append(material, detailCode);
        PolynomialEvidence.append(
            material,
            work.canonicalMaterial());
        PolynomialEvidence.append(
            material,
            claimStrength.name());
        PolynomialEvidence.append(material, engineResultHash);
        PolynomialEvidence.append(
            material,
            request.canonicalMaterial());
        candidates.forEach(candidate ->
            PolynomialEvidence.append(
                material,
                candidate.canonicalMaterial()));
        independentTrace.ifPresent(trace ->
            PolynomialEvidence.append(
                material,
                trace.canonicalMaterial()));
        return PolynomialEvidence.sha256(material.toString());
    }

    private static String technicalDetail(
        RuntimeException exception
    ) {
        String simple = exception.getClass().getSimpleName();
        return "ENGINE_EXCEPTION_" + (simple.isBlank()
            ? "RUNTIME"
            : simple.toUpperCase(java.util.Locale.ROOT));
    }

    private static boolean validHash(String value) {
        return value != null
            && value.matches("sha256:[0-9a-f]{64}");
    }

    public enum Status {
        COMPLETE_FACTORIZATION,
        IRREDUCIBLE,
        PARTIAL_FACTORIZATION,
        NO_FACTORIZATION_FOUND,
        UNSUPPORTED_DOMAIN,
        UNSUPPORTED_REQUEST,
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE
    }

    public enum ClaimStrength {
        NONE,
        VERIFIED_DECOMPOSITION,
        BACKEND_CLAIMED_COMPLETE,
        BACKEND_CLAIMED_IRREDUCIBLE,
        INDEPENDENTLY_CERTIFIED_COMPLETE,
        INDEPENDENTLY_CERTIFIED_IRREDUCIBLE
    }

    /** Result of the bounded original-domain irreducibility verifier. */
    public enum IndependentIrreducibilityOutcome {
        CERTIFIED,
        NO_WITNESS_WITHIN_POLICY,
        WORK_BUDGET_EXHAUSTED,
        DEGREE_LIMIT_EXCEEDED,
        NORMALIZATION_LIMIT_EXCEEDED,
        UNSUPPORTED_DOMAIN,
        UNSUPPORTED_SHAPE,
        TECHNICAL_FAILURE
    }

    /** Independently executed proof method, never an engine assertion. */
    public enum IrreducibilityProofMethod {
        NONE,
        LINEAR_DEGREE,
        MODULAR_RABIN
    }

    /** Outcome of one deterministic prime attempt. */
    public enum PrimeAttemptOutcome {
        DEGREE_LOSS,
        REDUCIBLE_REDUCTION,
        IRREDUCIBLE_WITNESS,
        WORK_BUDGET_EXHAUSTED
    }

    /** One independently recomputed Rabin gcd checkpoint. */
    public record FrobeniusCheckpoint(
        int iterations,
        int gcdDegree,
        String residueHash
    ) {
        public FrobeniusCheckpoint {
            if (iterations < 1
                    || gcdDegree < 0
                    || !validHash(residueHash)) {
                throw new IllegalArgumentException(
                    "invalid Frobenius checkpoint");
            }
        }

        public String canonicalMaterial() {
            StringBuilder result = new StringBuilder();
            PolynomialEvidence.append(
                result,
                Integer.toString(iterations));
            PolynomialEvidence.append(
                result,
                Integer.toString(gcdDegree));
            PolynomialEvidence.append(result, residueHash);
            return result.toString();
        }
    }

    /** Audit data for one independently reduced prime. */
    public record PrimeAttempt(
        int prime,
        PrimeAttemptOutcome outcome,
        int reducedDegree,
        List<FrobeniusCheckpoint> checkpoints,
        boolean finalCongruence,
        String finalResidueHash,
        long workUnits,
        String attemptHash
    ) {
        public PrimeAttempt {
            if (prime < 2
                    || outcome == null
                    || reducedDegree < -1
                    || finalResidueHash == null
                    || !finalResidueHash.isEmpty()
                        && !validHash(finalResidueHash)
                    || workUnits < 0
                    || !validHash(attemptHash)) {
                throw new IllegalArgumentException(
                    "invalid independent prime attempt");
            }
            checkpoints = List.copyOf(checkpoints);
            if (outcome == PrimeAttemptOutcome.IRREDUCIBLE_WITNESS
                    && (!finalCongruence
                        || finalResidueHash.isEmpty())) {
                throw new IllegalArgumentException(
                    "irreducible witness requires final congruence");
            }
        }

        public String canonicalMaterial() {
            StringBuilder result = new StringBuilder();
            PolynomialEvidence.append(
                result,
                Integer.toString(prime));
            PolynomialEvidence.append(result, outcome.name());
            PolynomialEvidence.append(
                result,
                Integer.toString(reducedDegree));
            checkpoints.forEach(checkpoint ->
                PolynomialEvidence.append(
                    result,
                    checkpoint.canonicalMaterial()));
            PolynomialEvidence.append(
                result,
                Boolean.toString(finalCongruence));
            PolynomialEvidence.append(result, finalResidueHash);
            PolynomialEvidence.append(
                result,
                Long.toString(workUnits));
            PolynomialEvidence.append(result, attemptHash);
            return result.toString();
        }
    }

    /**
     * Verifier-issued trace for a bounded original-domain proof attempt.
     * Only the enclosing {@link Report} authorizes its mathematical status.
     */
    public static final class IndependentIrreducibilityTrace {
        private final IndependentTraceState state;

        private IndependentIrreducibilityTrace(
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
            state = new IndependentTraceState(
                methodId,
                requestHash,
                sourceHash,
                outcome,
                proofMethod,
                detailCode,
                normalizedPrimitiveCoefficients,
                degree,
                primeAttempts,
                selectedPrime,
                work,
                traceHash);
        }

        public String methodId() {
            return state.methodId();
        }

        public String requestHash() {
            return state.requestHash();
        }

        public String sourceHash() {
            return state.sourceHash();
        }

        public IndependentIrreducibilityOutcome outcome() {
            return state.outcome();
        }

        public IrreducibilityProofMethod proofMethod() {
            return state.proofMethod();
        }

        public String detailCode() {
            return state.detailCode();
        }

        public List<BigInteger> normalizedPrimitiveCoefficients() {
            return state.normalizedPrimitiveCoefficients();
        }

        public int degree() {
            return state.degree();
        }

        public List<PrimeAttempt> primeAttempts() {
            return state.primeAttempts();
        }

        public OptionalInt selectedPrime() {
            return state.selectedPrime() == 0
                ? OptionalInt.empty()
                : OptionalInt.of(state.selectedPrime());
        }

        public PolynomialWorkLedger work() {
            return state.work();
        }

        public String traceHash() {
            return state.traceHash();
        }

        public boolean certified() {
            return outcome()
                == IndependentIrreducibilityOutcome.CERTIFIED;
        }

        public String canonicalMaterial() {
            StringBuilder result = new StringBuilder();
            PolynomialEvidence.append(result, methodId());
            PolynomialEvidence.append(result, requestHash());
            PolynomialEvidence.append(result, sourceHash());
            PolynomialEvidence.append(result, outcome().name());
            PolynomialEvidence.append(result, proofMethod().name());
            PolynomialEvidence.append(result, detailCode());
            normalizedPrimitiveCoefficients().forEach(coefficient ->
                PolynomialEvidence.append(
                    result,
                    coefficient.toString()));
            PolynomialEvidence.append(
                result,
                Integer.toString(degree()));
            primeAttempts().forEach(attempt ->
                PolynomialEvidence.append(
                    result,
                    attempt.canonicalMaterial()));
            PolynomialEvidence.append(
                result,
                Integer.toString(state.selectedPrime()));
            PolynomialEvidence.append(
                result,
                work().canonicalMaterial());
            PolynomialEvidence.append(result, traceHash());
            return result.toString();
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                || other instanceof IndependentIrreducibilityTrace trace
                    && state.equals(trace.state);
        }

        @Override
        public int hashCode() {
            return state.hashCode();
        }

        @Override
        public String toString() {
            return "IndependentIrreducibilityTrace[" + state + ']';
        }

        private record IndependentTraceState(
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
            private IndependentTraceState {
                if (methodId == null
                        || methodId.isBlank()
                        || !validHash(requestHash)
                        || !validHash(sourceHash)
                        || outcome == null
                        || proofMethod == null
                        || detailCode == null
                        || detailCode.isBlank()
                        || degree < -1
                        || selectedPrime < 0
                        || work == null
                        || !validHash(traceHash)) {
                    throw new IllegalArgumentException(
                        "invalid independent irreducibility trace");
                }
                normalizedPrimitiveCoefficients = List.copyOf(
                    normalizedPrimitiveCoefficients);
                primeAttempts = List.copyOf(primeAttempts);
                if (outcome == IndependentIrreducibilityOutcome.CERTIFIED
                        && proofMethod
                            == IrreducibilityProofMethod.NONE) {
                    throw new IllegalArgumentException(
                        "certified trace requires a proof method");
                }
            }
        }
    }

    /** Issuer-owned exact decomposition evidence. */
    public static final class VerifiedCandidate<C> {
        private final State<C> state;

        private VerifiedCandidate(
            FactorizationEngine.Proposal<C> proposal,
            FactorizationEngine.BackendClaim backendClaim,
            String verificationCertificateHash
        ) {
            state = new State<>(
                proposal.unit(),
                proposal.factors(),
                proposal.unresolvedRemainder(),
                backendClaim,
                proposal.engineCertificateHash(),
                verificationCertificateHash);
        }

        public C unit() {
            return state.unit();
        }

        public List<PolynomialFactor<C>> factors() {
            return state.factors();
        }

        public SparsePolynomial<C> unresolvedRemainder() {
            return state.unresolvedRemainder();
        }

        public FactorizationEngine.BackendClaim backendClaim() {
            return state.backendClaim();
        }

        public String engineCertificateHash() {
            return state.engineCertificateHash();
        }

        public String verificationCertificateHash() {
            return state.verificationCertificateHash();
        }

        public String canonicalMaterial() {
            StringBuilder result = new StringBuilder();
            PolynomialEvidence.append(
                result,
                unresolvedRemainder().ring()
                    .coefficientDomain()
                    .canonicalText(unit()));
            factors().forEach(factor -> {
                PolynomialEvidence.append(
                    result,
                    Integer.toString(factor.multiplicity()));
                PolynomialEvidence.append(
                    result,
                    factor.polynomial().canonicalMaterial());
            });
            PolynomialEvidence.append(
                result,
                unresolvedRemainder().canonicalMaterial());
            PolynomialEvidence.append(
                result,
                backendClaim().name());
            PolynomialEvidence.append(
                result,
                engineCertificateHash());
            PolynomialEvidence.append(
                result,
                verificationCertificateHash());
            return result.toString();
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                || other instanceof VerifiedCandidate<?> candidate
                    && state.equals(candidate.state);
        }

        @Override
        public int hashCode() {
            return state.hashCode();
        }

        @Override
        public String toString() {
            return "VerifiedCandidate[" + state + ']';
        }

        private record State<C>(
            C unit,
            List<PolynomialFactor<C>> factors,
            SparsePolynomial<C> unresolvedRemainder,
            FactorizationEngine.BackendClaim backendClaim,
            String engineCertificateHash,
            String verificationCertificateHash
        ) {
            private State {
                Objects.requireNonNull(unit, "unit");
                factors = List.copyOf(factors);
                Objects.requireNonNull(
                    unresolvedRemainder,
                    "unresolvedRemainder");
                Objects.requireNonNull(backendClaim, "backendClaim");
                if (engineCertificateHash == null
                        || !engineCertificateHash.matches(
                            "sha256:[0-9a-f]{64}")
                        || verificationCertificateHash == null
                        || !verificationCertificateHash.matches(
                            "sha256:[0-9a-f]{64}")) {
                    throw new IllegalArgumentException(
                        "verified factorization evidence is invalid");
                }
            }
        }
    }

    /** Verifier-issued report; callers cannot manufacture trusted statuses. */
    public static final class Report<C> {
        private final State<C> state;

        private Report(
            String engineId,
            Status status,
            String detailCode,
            PolynomialWorkLedger work,
            ClaimStrength claimStrength,
            List<VerifiedCandidate<C>> candidates,
            String engineResultHash,
            String verificationHash,
            Optional<IndependentIrreducibilityTrace> independentTrace
        ) {
            state = new State<>(
                engineId,
                status,
                detailCode,
                work,
                claimStrength,
                candidates,
                engineResultHash,
                verificationHash,
                independentTrace);
        }

        public String engineId() {
            return state.engineId();
        }

        public Status status() {
            return state.status();
        }

        public String detailCode() {
            return state.detailCode();
        }

        public PolynomialWorkLedger work() {
            return state.work();
        }

        public ClaimStrength claimStrength() {
            return state.claimStrength();
        }

        public List<VerifiedCandidate<C>> candidates() {
            return state.candidates();
        }

        public String engineResultHash() {
            return state.engineResultHash();
        }

        public String verificationHash() {
            return state.verificationHash();
        }

        public Optional<IndependentIrreducibilityTrace>
                independentIrreducibilityTrace() {
            return state.independentTrace();
        }

        public boolean successful() {
            return status() == Status.PARTIAL_FACTORIZATION
                || status() == Status.COMPLETE_FACTORIZATION;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                || other instanceof Report<?> report
                    && state.equals(report.state);
        }

        @Override
        public int hashCode() {
            return state.hashCode();
        }

        @Override
        public String toString() {
            return "Report[" + state + ']';
        }

        private record State<C>(
            String engineId,
            Status status,
            String detailCode,
            PolynomialWorkLedger work,
            ClaimStrength claimStrength,
            List<VerifiedCandidate<C>> candidates,
            String engineResultHash,
            String verificationHash,
            Optional<IndependentIrreducibilityTrace> independentTrace
        ) {
            private State {
                if (engineId == null
                        || engineId.isBlank()
                        || status == null
                        || detailCode == null
                        || detailCode.isBlank()
                        || work == null
                        || claimStrength == null
                        || independentTrace == null
                        || verificationHash == null
                        || !verificationHash.matches(
                            "sha256:[0-9a-f]{64}")) {
                    throw new IllegalArgumentException(
                        "factorization verification report is invalid");
                }
                candidates = List.copyOf(candidates);
                independentTrace = independentTrace.map(
                    Objects::requireNonNull);
                boolean success =
                    status == Status.PARTIAL_FACTORIZATION
                        || status == Status.COMPLETE_FACTORIZATION;
                if (success == candidates.isEmpty()) {
                    throw new IllegalArgumentException(
                        "factorization report candidate/status mismatch");
                }
                if (status == Status.COMPLETE_FACTORIZATION
                        && claimStrength
                            != ClaimStrength
                                .INDEPENDENTLY_CERTIFIED_COMPLETE) {
                    throw new IllegalArgumentException(
                        "complete factorization requires independent evidence");
                }
                if (status == Status.IRREDUCIBLE
                        && claimStrength
                            != ClaimStrength
                                .INDEPENDENTLY_CERTIFIED_IRREDUCIBLE) {
                    throw new IllegalArgumentException(
                        "irreducibility requires independent evidence");
                }
                if (status == Status.IRREDUCIBLE
                        && independentTrace.filter(
                            IndependentIrreducibilityTrace::certified)
                            .isEmpty()) {
                    throw new IllegalArgumentException(
                        "irreducibility requires a certified trace");
                }
                if (status != Status.IRREDUCIBLE
                        && independentTrace.filter(
                            IndependentIrreducibilityTrace::certified)
                            .isPresent()) {
                    throw new IllegalArgumentException(
                        "certified trace requires irreducible status");
                }
                if (engineResultHash == null
                        || !engineResultHash.isEmpty()
                        && !engineResultHash.matches(
                            "sha256:[0-9a-f]{64}")) {
                    throw new IllegalArgumentException(
                        "engine result hash is invalid");
                }
            }
        }
    }

    private record VerificationOutcome<C>(
        boolean verified,
        String detailCode,
        SparsePolynomial<C> reconstructed
    ) {
    }

    private static final class WorkCounter {
        private final long limit;
        private final Map<String, Long> stages =
            new LinkedHashMap<>();
        private long total;

        private WorkCounter(long limit) {
            if (limit < 0) {
                throw new IllegalArgumentException(
                    "verification work limit must not be negative");
            }
            this.limit = limit;
        }

        private void consume(String stage, long units) {
            if (units < 0 || total > limit - units) {
                throw new WorkLimitReached();
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
    }

    private static final class WorkLimitReached
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
