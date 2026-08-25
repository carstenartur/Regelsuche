package de.regelsuche.polynomial;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes one factorization engine and issues trusted evidence only after
 * independent contract and product verification.
 */
public final class FactorizationVerifier {
    public static final String VERIFIER_ID =
        "regelsuche.factorization-verifier/v1";

    private FactorizationVerifier() {
    }

    public static <C> Report<C> execute(
        FactorizationEngine<C> engine,
        FactorizationRequest<C> request
    ) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(request, "request");
        if (!engine.coefficientDomainId().equals(
                request.source().ring().coefficientDomain().id())) {
            return failure(
                engine.engineId(),
                Status.UNSUPPORTED_DOMAIN,
                "ENGINE_COEFFICIENT_DOMAIN_MISMATCH",
                FactorizationEngine.WorkLedger.empty(),
                ClaimStrength.NONE,
                "");
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
                FactorizationEngine.WorkLedger.empty(),
                ClaimStrength.NONE,
                "");
        }

        Report<C> invalidContract = validateEngineContract(
            engine,
            request,
            raw);
        if (invalidContract != null) {
            return invalidContract;
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
                raw.engineResultHash());
            case UNSUPPORTED_REQUEST -> failure(
                raw.engineId(),
                Status.UNSUPPORTED_REQUEST,
                raw.detailCode(),
                raw.work(),
                ClaimStrength.NONE,
                raw.engineResultHash());
            case BUDGET_INCONCLUSIVE -> failure(
                raw.engineId(),
                Status.BUDGET_INCONCLUSIVE,
                raw.detailCode(),
                raw.work(),
                ClaimStrength.NONE,
                raw.engineResultHash());
            case TECHNICAL_FAILURE -> failure(
                raw.engineId(),
                Status.TECHNICAL_FAILURE,
                raw.detailCode(),
                raw.work(),
                ClaimStrength.NONE,
                raw.engineResultHash());
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
                raw.engineResultHash());
        }
        if (!raw.work().within(request.maxWorkUnits())) {
            return failure(
                raw.engineId(),
                Status.TECHNICAL_FAILURE,
                "ENGINE_EXCEEDED_REQUEST_WORK_BUDGET",
                raw.work(),
                ClaimStrength.NONE,
                raw.engineResultHash());
        }
        if (raw.proposals().size() > request.maxCandidates()) {
            return failure(
                raw.engineId(),
                Status.TECHNICAL_FAILURE,
                "ENGINE_EXCEEDED_REQUEST_CANDIDATE_BUDGET",
                raw.work(),
                ClaimStrength.NONE,
                raw.engineResultHash());
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
        List<VerifiedCandidate<C>> verified = new ArrayList<>();
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
                    raw.engineResultHash());
            }
            if (!outcome.verified()) {
                return failure(
                    raw.engineId(),
                    Status.TECHNICAL_FAILURE,
                    outcome.detailCode(),
                    merge(raw.work(), verificationWork.ledger()),
                    ClaimStrength.NONE,
                    raw.engineResultHash());
            }
            verified.add(issueVerifiedCandidate(
                proposal,
                raw.backendClaim(),
                outcome.reconstructed(),
                request.source()));
        }

        if (request.evidenceRequirement()
                == FactorizationRequest.EvidenceRequirement
                    .INDEPENDENT_COMPLETE) {
            return failure(
                raw.engineId(),
                Status.UNSUPPORTED_REQUEST,
                "INDEPENDENT_COMPLETENESS_VERIFIER_REQUIRED",
                merge(raw.work(), verificationWork.ledger()),
                claimFor(raw.backendClaim()),
                raw.engineResultHash());
        }
        return success(
            raw.engineId(),
            Status.PARTIAL_FACTORIZATION,
            raw.detailCode(),
            merge(raw.work(), verificationWork.ledger()),
            claimFor(raw.backendClaim()),
            verified,
            raw.engineResultHash(),
            request);
    }

    private static <C> Report<C> noCandidate(
        FactorizationRequest<C> request,
        FactorizationEngine.EngineResult<C> raw
    ) {
        ClaimStrength claim = raw.backendClaim()
                == FactorizationEngine.BackendClaim.IRREDUCIBLE
            ? ClaimStrength.BACKEND_CLAIMED_IRREDUCIBLE
            : ClaimStrength.NONE;
        if (request.evidenceRequirement()
                == FactorizationRequest.EvidenceRequirement
                    .INDEPENDENT_COMPLETE
                && claim == ClaimStrength.BACKEND_CLAIMED_IRREDUCIBLE) {
            return failure(
                raw.engineId(),
                Status.UNSUPPORTED_REQUEST,
                "INDEPENDENT_IRREDUCIBILITY_VERIFIER_REQUIRED",
                raw.work(),
                claim,
                raw.engineResultHash());
        }
        return failure(
            raw.engineId(),
            Status.NO_FACTORIZATION_FOUND,
            raw.detailCode(),
            raw.work(),
            claim,
            raw.engineResultHash());
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
            SparsePolynomial<C> powered = factor.polynomial().pow(
                factor.multiplicity());
            work.consume("verify.factor-product-multiplications", 1);
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
        String material = VERIFIER_ID
            + "|source=" + source.canonicalMaterial()
            + "|proposal=" + proposal.canonicalMaterial()
            + "|backendClaim=" + backendClaim
            + "|reconstructed=" + reconstructed.canonicalMaterial();
        return new VerifiedCandidate<>(
            proposal,
            backendClaim,
            sha256(material));
    }

    private static ClaimStrength claimFor(
        FactorizationEngine.BackendClaim backendClaim
    ) {
        return backendClaim
                == FactorizationEngine.BackendClaim.COMPLETE_FACTORIZATION
            ? ClaimStrength.BACKEND_CLAIMED_COMPLETE
            : ClaimStrength.VERIFIED_DECOMPOSITION;
    }

    private static FactorizationEngine.WorkLedger merge(
        FactorizationEngine.WorkLedger first,
        FactorizationEngine.WorkLedger second
    ) {
        Map<String, Long> merged = new LinkedHashMap<>(first.stages());
        second.stages().forEach((stage, units) -> merged.merge(
            stage,
            units,
            Math::addExact));
        return new FactorizationEngine.WorkLedger(merged);
    }

    private static <C> Report<C> success(
        String engineId,
        Status status,
        String detailCode,
        FactorizationEngine.WorkLedger work,
        ClaimStrength claimStrength,
        List<VerifiedCandidate<C>> candidates,
        String engineResultHash,
        FactorizationRequest<C> request
    ) {
        if (status != Status.PARTIAL_FACTORIZATION
                && status != Status.COMPLETE_FACTORIZATION) {
            throw new IllegalArgumentException(
                "factorization success status is invalid");
        }
        String verificationHash = reportHash(
            engineId,
            status,
            detailCode,
            work,
            claimStrength,
            candidates,
            engineResultHash,
            request);
        return new Report<>(
            engineId,
            status,
            detailCode,
            work,
            claimStrength,
            candidates,
            engineResultHash,
            verificationHash);
    }

    private static <C> Report<C> failure(
        String engineId,
        Status status,
        String detailCode,
        FactorizationEngine.WorkLedger work,
        ClaimStrength claimStrength,
        String engineResultHash
    ) {
        String material = VERIFIER_ID
            + "|engine=" + engineId
            + "|status=" + status
            + "|detail=" + detailCode
            + "|work=" + work.canonicalMaterial()
            + "|claim=" + claimStrength
            + "|engineResult=" + engineResultHash;
        return new Report<>(
            engineId,
            status,
            detailCode,
            work,
            claimStrength,
            List.of(),
            engineResultHash,
            sha256(material));
    }

    private static <C> String reportHash(
        String engineId,
        Status status,
        String detailCode,
        FactorizationEngine.WorkLedger work,
        ClaimStrength claimStrength,
        List<VerifiedCandidate<C>> candidates,
        String engineResultHash,
        FactorizationRequest<C> request
    ) {
        StringBuilder material = new StringBuilder(VERIFIER_ID);
        append(material, engineId);
        append(material, status.name());
        append(material, detailCode);
        append(material, work.canonicalMaterial());
        append(material, claimStrength.name());
        append(material, engineResultHash);
        append(material, request.source().canonicalMaterial());
        append(material, request.evidenceRequirement().name());
        append(material, Integer.toString(request.maxCandidates()));
        append(material, Long.toString(request.maxWorkUnits()));
        candidates.forEach(candidate -> append(
            material,
            candidate.canonicalMaterial()));
        return sha256(material.toString());
    }

    private static String technicalDetail(RuntimeException exception) {
        String simple = exception.getClass().getSimpleName();
        return "ENGINE_EXCEPTION_" + (simple.isBlank()
            ? "RUNTIME"
            : simple.toUpperCase(java.util.Locale.ROOT));
    }

    private static void append(
        StringBuilder target,
        String value
    ) {
        target.append('|')
            .append(value.length())
            .append(':')
            .append(value);
    }

    private static String sha256(String material) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "SHA-256 unavailable",
                exception);
        }
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
            append(
                result,
                unresolvedRemainder().ring()
                    .coefficientDomain()
                    .canonicalText(unit()));
            factors().forEach(factor -> {
                append(
                    result,
                    Integer.toString(factor.multiplicity()));
                append(
                    result,
                    factor.polynomial().canonicalMaterial());
            });
            append(
                result,
                unresolvedRemainder().canonicalMaterial());
            append(result, backendClaim().name());
            append(result, engineCertificateHash());
            append(result, verificationCertificateHash());
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
            FactorizationEngine.WorkLedger work,
            ClaimStrength claimStrength,
            List<VerifiedCandidate<C>> candidates,
            String engineResultHash,
            String verificationHash
        ) {
            state = new State<>(
                engineId,
                status,
                detailCode,
                work,
                claimStrength,
                candidates,
                engineResultHash,
                verificationHash);
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

        public FactorizationEngine.WorkLedger work() {
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
            FactorizationEngine.WorkLedger work,
            ClaimStrength claimStrength,
            List<VerifiedCandidate<C>> candidates,
            String engineResultHash,
            String verificationHash
        ) {
            private State {
                if (engineId == null
                        || engineId.isBlank()
                        || status == null
                        || detailCode == null
                        || detailCode.isBlank()
                        || work == null
                        || claimStrength == null
                        || verificationHash == null
                        || !verificationHash.matches(
                            "sha256:[0-9a-f]{64}")) {
                    throw new IllegalArgumentException(
                        "factorization verification report is invalid");
                }
                candidates = List.copyOf(candidates);
                boolean success = status
                        == Status.PARTIAL_FACTORIZATION
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
                if (!engineResultHash.isEmpty()
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
        private final Map<String, Long> stages = new LinkedHashMap<>();
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
            total += units;
            stages.merge(stage, units, Math::addExact);
        }

        private FactorizationEngine.WorkLedger ledger() {
            return new FactorizationEngine.WorkLedger(stages);
        }
    }

    private static final class WorkLimitReached
            extends RuntimeException {
    }
}
