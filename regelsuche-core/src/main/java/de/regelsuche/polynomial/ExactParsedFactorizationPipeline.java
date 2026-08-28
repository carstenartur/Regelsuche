package de.regelsuche.polynomial;

import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.scalar.ExactRational;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Binds parser-issued exact provenance directly to the domain-aware
 * factorization request and verifier boundary.
 *
 * <p>Extraction, backend proposal work and independent product verification
 * share one total-work ceiling. The extraction layer never renders or reparses
 * coefficients and no legacy polynomial value becomes an authority.</p>
 */
public final class ExactParsedFactorizationPipeline {
    public static final String PIPELINE_ID =
        "regelsuche.exact-parsed-factorization-pipeline/v1";

    private final ExactParsedUnivariatePolynomialView view;
    private final Policy policy;

    public ExactParsedFactorizationPipeline() {
        this(
            new ExactParsedUnivariatePolynomialView(),
            Policy.boundedDefaults());
    }

    public ExactParsedFactorizationPipeline(
        ExactParsedUnivariatePolynomialView view,
        Policy policy
    ) {
        this.view = Objects.requireNonNull(view, "view");
        this.policy = Objects.requireNonNull(policy, "policy");
        long extractionCeiling = Math.addExact(
            (long) view.budget().maxVisitedNodes(),
            view.budget().maxArithmeticOperations());
        if (extractionCeiling > policy.maxTotalWorkUnits()) {
            throw new IllegalArgumentException(
                "PIPELINE_TOTAL_WORK_BELOW_EXTRACTION_CEILING");
        }
    }

    public ExactParsedUnivariatePolynomialView view() {
        return view;
    }

    public Policy policy() {
        return policy;
    }

    public Result factor(
        ExactParsedTerm parsed,
        FactorizationEngine<ExactRational> engine
    ) {
        Objects.requireNonNull(parsed, "parsed");
        Objects.requireNonNull(engine, "engine");
        String engineId = requireEngineId(engine.engineId());
        ExactParsedUnivariatePolynomialView.Analysis extraction =
            view.analyze(parsed);
        PolynomialWorkLedger extractionWork =
            extraction.work().asPolynomialWorkLedger();
        long extractionUnits = extractionWork.totalWorkUnits();

        if (extractionUnits > policy.maxTotalWorkUnits()) {
            throw new IllegalStateException(
                "exact extraction exceeded its declared work ceiling");
        }
        if (extraction.status()
                == ExactParsedUnivariatePolynomialView.Status.UNSUPPORTED) {
            return Result.failure(
                Status.UNSUPPORTED_EXPRESSION,
                extraction.detailCode(),
                engineId,
                policy,
                extraction,
                extractionWork);
        }
        if (extraction.status()
                == ExactParsedUnivariatePolynomialView.Status
                    .BUDGET_INCONCLUSIVE) {
            return Result.failure(
                Status.BUDGET_INCONCLUSIVE,
                extraction.detailCode(),
                engineId,
                policy,
                extraction,
                extractionWork);
        }

        SparsePolynomial<ExactRational> polynomial =
            extraction.polynomial().orElseThrow();
        if (polynomial.isZero()) {
            return Result.failure(
                Status.UNSUPPORTED_REQUEST,
                "ZERO_POLYNOMIAL_HAS_NO_FINITE_FACTORIZATION_CONTRACT",
                engineId,
                policy,
                extraction,
                extractionWork);
        }
        if (polynomial.ring().variableCount() != 1
                || polynomial.isConstant()) {
            return Result.failure(
                Status.UNSUPPORTED_REQUEST,
                "CONSTANT_POLYNOMIAL_HAS_NO_NONTRIVIAL_FACTORIZATION_REQUEST",
                engineId,
                policy,
                extraction,
                extractionWork);
        }

        long remainingWork = policy.maxTotalWorkUnits() - extractionUnits;
        if (remainingWork < 1) {
            return Result.failure(
                Status.BUDGET_INCONCLUSIVE,
                "NO_FACTORIZATION_WORK_BUDGET_REMAINING",
                engineId,
                policy,
                extraction,
                extractionWork);
        }

        FactorizationRequest<ExactRational> request =
            new FactorizationRequest<>(
                polynomial,
                policy.evidenceRequirement(),
                policy.structuralLimits(),
                policy.maxCandidates(),
                remainingWork);
        FactorizationVerifier.Report<ExactRational> report =
            FactorizationVerifier.execute(engine, request);
        PolynomialWorkLedger totalWork = merge(
            extractionWork,
            report.work());
        if (!totalWork.within(policy.maxTotalWorkUnits())) {
            throw new IllegalStateException(
                "factorization pipeline exceeded its total-work authority");
        }
        return Result.executed(
            engineId,
            policy,
            extraction,
            request,
            report,
            totalWork);
    }

    private static String requireEngineId(String engineId) {
        if (engineId == null || engineId.isBlank()) {
            throw new IllegalArgumentException(
                "factorization engine ID must not be blank");
        }
        return engineId;
    }

    private static PolynomialWorkLedger merge(
        PolynomialWorkLedger first,
        PolynomialWorkLedger second
    ) {
        Map<String, Long> stages = new LinkedHashMap<>(first.stages());
        second.stages().forEach((stage, units) -> stages.merge(
            stage,
            units,
            Math::addExact));
        return new PolynomialWorkLedger(stages);
    }

    public enum Status {
        EXECUTED,
        UNSUPPORTED_EXPRESSION,
        UNSUPPORTED_REQUEST,
        BUDGET_INCONCLUSIVE
    }

    /** Frozen request and total-work policy for one exact parsed invocation. */
    public record Policy(
        FactorizationRequest.StructuralLimits structuralLimits,
        int maxCandidates,
        long maxTotalWorkUnits,
        FactorizationRequest.EvidenceRequirement evidenceRequirement
    ) {
        public Policy {
            Objects.requireNonNull(structuralLimits, "structuralLimits");
            Objects.requireNonNull(
                evidenceRequirement,
                "evidenceRequirement");
            if (maxCandidates < 0 || maxTotalWorkUnits < 1) {
                throw new IllegalArgumentException(
                    "exact parsed factorization policy is invalid");
            }
        }

        public static Policy boundedDefaults() {
            return new Policy(
                new FactorizationRequest.StructuralLimits(
                    1,
                    ExactParsedUnivariatePolynomialView.MAX_DEGREE,
                    ExactParsedUnivariatePolynomialView.MAX_DEGREE + 1,
                    ExactParsedUnivariatePolynomialView
                        .MAX_COEFFICIENT_BITS),
                250_000,
                20_000_000,
                FactorizationRequest.EvidenceRequirement
                    .VERIFIED_DECOMPOSITION);
        }

        public String canonicalMaterial() {
            StringBuilder result = new StringBuilder();
            PolynomialEvidence.append(
                result,
                structuralLimits.canonicalMaterial());
            PolynomialEvidence.append(
                result,
                Integer.toString(maxCandidates));
            PolynomialEvidence.append(
                result,
                Long.toString(maxTotalWorkUnits));
            PolynomialEvidence.append(
                result,
                evidenceRequirement.name());
            return result.toString();
        }
    }

    /** Content-addressed result of extraction plus optional verified execution. */
    public static final class Result {
        private final Status status;
        private final String detailCode;
        private final String engineId;
        private final Policy policy;
        private final ExactParsedUnivariatePolynomialView.Analysis extraction;
        private final Optional<FactorizationRequest<ExactRational>> request;
        private final Optional<FactorizationVerifier.Report<ExactRational>> report;
        private final PolynomialWorkLedger totalWork;
        private final String certificateHash;

        private Result(
            Status status,
            String detailCode,
            String engineId,
            Policy policy,
            ExactParsedUnivariatePolynomialView.Analysis extraction,
            Optional<FactorizationRequest<ExactRational>> request,
            Optional<FactorizationVerifier.Report<ExactRational>> report,
            PolynomialWorkLedger totalWork
        ) {
            this.status = Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "factorization pipeline detail code must not be blank");
            }
            this.detailCode = detailCode;
            this.engineId = requireEngineId(engineId);
            this.policy = Objects.requireNonNull(policy, "policy");
            this.extraction = Objects.requireNonNull(
                extraction,
                "extraction");
            this.request = Objects.requireNonNull(request, "request");
            this.report = Objects.requireNonNull(report, "report");
            this.totalWork = Objects.requireNonNull(totalWork, "totalWork");
            boolean executed = status == Status.EXECUTED;
            if (executed != request.isPresent()
                    || executed != report.isPresent()) {
                throw new IllegalArgumentException(
                    "factorization pipeline status/payload mismatch");
            }
            if (!totalWork.within(policy.maxTotalWorkUnits())) {
                throw new IllegalArgumentException(
                    "factorization pipeline work exceeds policy");
            }
            this.certificateHash = PolynomialEvidence.sha256(
                evidenceMaterial());
        }

        private static Result executed(
            String engineId,
            Policy policy,
            ExactParsedUnivariatePolynomialView.Analysis extraction,
            FactorizationRequest<ExactRational> request,
            FactorizationVerifier.Report<ExactRational> report,
            PolynomialWorkLedger totalWork
        ) {
            return new Result(
                Status.EXECUTED,
                report.detailCode(),
                engineId,
                policy,
                extraction,
                Optional.of(request),
                Optional.of(report),
                totalWork);
        }

        private static Result failure(
            Status status,
            String detailCode,
            String engineId,
            Policy policy,
            ExactParsedUnivariatePolynomialView.Analysis extraction,
            PolynomialWorkLedger totalWork
        ) {
            if (status == Status.EXECUTED) {
                throw new IllegalArgumentException(
                    "executed status requires factorization evidence");
            }
            return new Result(
                status,
                detailCode,
                engineId,
                policy,
                extraction,
                Optional.empty(),
                Optional.empty(),
                totalWork);
        }

        public Status status() {
            return status;
        }

        public String detailCode() {
            return detailCode;
        }

        public String engineId() {
            return engineId;
        }

        public Policy policy() {
            return policy;
        }

        public ExactParsedUnivariatePolynomialView.Analysis extraction() {
            return extraction;
        }

        public Optional<FactorizationRequest<ExactRational>> request() {
            return request;
        }

        public Optional<FactorizationVerifier.Report<ExactRational>> report() {
            return report;
        }

        public PolynomialWorkLedger totalWork() {
            return totalWork;
        }

        public String certificateHash() {
            return certificateHash;
        }

        public boolean executed() {
            return status == Status.EXECUTED;
        }

        public String canonicalMaterial() {
            StringBuilder result = new StringBuilder(evidenceMaterial());
            PolynomialEvidence.append(result, certificateHash);
            return result.toString();
        }

        private String evidenceMaterial() {
            StringBuilder result = new StringBuilder(PIPELINE_ID);
            PolynomialEvidence.append(result, status.name());
            PolynomialEvidence.append(result, detailCode);
            PolynomialEvidence.append(result, engineId);
            PolynomialEvidence.append(
                result,
                policy.canonicalMaterial());
            PolynomialEvidence.append(
                result,
                extraction.canonicalMaterial());
            PolynomialEvidence.append(
                result,
                request.map(FactorizationRequest::canonicalMaterial)
                    .orElse(""));
            PolynomialEvidence.append(
                result,
                report.map(FactorizationVerifier.Report::verificationHash)
                    .orElse(""));
            PolynomialEvidence.append(
                result,
                totalWork.canonicalMaterial());
            return result.toString();
        }
    }
}
