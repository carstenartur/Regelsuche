package de.regelsuche.polynomial;

import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Turns one verifier-issued exact factorization into a root-occurrence
 * transformation and independently reconstructs the rendered expression.
 *
 * <p>The original parser evidence, factorization request, verifier report,
 * rendered syntax, exact reparse and reconstructed polynomial remain bound to
 * one content-addressed result. Factorization, rendering, parsing and
 * reconstruction share the original pipeline's non-resettable work ceiling.</p>
 */
public final class ExactFactorizationTransformationPipeline {
    public static final String TRANSFORMATION_ID =
        "regelsuche.exact-factorization-transformation/v1";

    private final ExactFactorizationExpressionRenderer renderer;
    private final ExpressionParser parser;
    private final ExactParsedUnivariatePolynomialView reparseView;

    public ExactFactorizationTransformationPipeline() {
        this(
            new ExactFactorizationExpressionRenderer(),
            new ExpressionParser(),
            new ExactParsedUnivariatePolynomialView());
    }

    public ExactFactorizationTransformationPipeline(
        ExactFactorizationExpressionRenderer renderer,
        ExpressionParser parser,
        ExactParsedUnivariatePolynomialView reparseView
    ) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.reparseView = Objects.requireNonNull(
            reparseView,
            "reparseView");
    }

    public ExactFactorizationExpressionRenderer renderer() {
        return renderer;
    }

    public ExactParsedUnivariatePolynomialView reparseView() {
        return reparseView;
    }

    /**
     * Transforms the root when the verifier issued exactly one candidate.
     * Multiple candidates require the explicit-index overload so no hidden
     * best-of policy enters the evidence boundary.
     */
    public Result transformRoot(
        ExactParsedTerm source,
        ExactParsedFactorizationPipeline.Result factorization
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(factorization, "factorization");
        OccurrenceEvidence occurrence = OccurrenceEvidence.root(source);
        String sourceViolation = sourceContinuityViolation(
            source,
            factorization);
        if (sourceViolation != null) {
            return Result.failure(
                Status.SOURCE_EVIDENCE_MISMATCH,
                sourceViolation,
                occurrence,
                factorization,
                -1,
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                factorization.totalWork());
        }
        if (!factorization.executed()) {
            return unavailableFactorization(
                occurrence,
                factorization);
        }
        FactorizationVerifier.Report<ExactRational> report =
            factorization.report().orElseThrow();
        if (!report.successful()) {
            return unavailableReport(
                occurrence,
                factorization,
                report);
        }
        if (report.candidates().size() != 1) {
            return Result.failure(
                Status.UNSUPPORTED,
                "MULTIPLE_CANDIDATES_REQUIRE_EXPLICIT_SELECTION",
                occurrence,
                factorization,
                -1,
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                factorization.totalWork());
        }
        return transformRoot(source, factorization, 0);
    }

    /** Transforms the root with one explicitly selected verifier candidate. */
    public Result transformRoot(
        ExactParsedTerm source,
        ExactParsedFactorizationPipeline.Result factorization,
        int candidateIndex
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(factorization, "factorization");
        OccurrenceEvidence occurrence = OccurrenceEvidence.root(source);
        String sourceViolation = sourceContinuityViolation(
            source,
            factorization);
        if (sourceViolation != null) {
            return Result.failure(
                Status.SOURCE_EVIDENCE_MISMATCH,
                sourceViolation,
                occurrence,
                factorization,
                candidateIndex,
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                factorization.totalWork());
        }
        if (!factorization.executed()) {
            return unavailableFactorization(
                occurrence,
                factorization);
        }
        FactorizationVerifier.Report<ExactRational> report =
            factorization.report().orElseThrow();
        if (!report.successful()) {
            return unavailableReport(
                occurrence,
                factorization,
                report);
        }
        if (candidateIndex < 0
                || candidateIndex >= report.candidates().size()) {
            return Result.failure(
                Status.UNSUPPORTED,
                "CANDIDATE_SELECTION_OUT_OF_RANGE",
                occurrence,
                factorization,
                candidateIndex,
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                factorization.totalWork());
        }

        FactorizationVerifier.VerifiedCandidate<ExactRational> candidate =
            report.candidates().get(candidateIndex);
        String candidateCertificate =
            candidate.verificationCertificateHash();
        long continuationCeiling = continuationCeiling();
        long remaining = factorization.policy().maxTotalWorkUnits()
            - factorization.totalWork().totalWorkUnits();
        if (remaining < continuationCeiling) {
            return Result.failure(
                Status.BUDGET_INCONCLUSIVE,
                "INSUFFICIENT_REMAINING_TRANSFORMATION_AUTHORITY",
                occurrence,
                factorization,
                candidateIndex,
                candidateCertificate,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                factorization.totalWork());
        }

        ExactFactorizationExpressionRenderer.Result rendering =
            renderer.render(candidate);
        PolynomialWorkLedger afterRendering = merge(
            factorization.totalWork(),
            rendering.work());
        if (!withinOriginalAuthority(factorization, afterRendering)) {
            return Result.failure(
                Status.BUDGET_INCONCLUSIVE,
                "RENDERING_EXCEEDED_ORIGINAL_WORK_AUTHORITY",
                occurrence,
                factorization,
                candidateIndex,
                candidateCertificate,
                Optional.of(rendering),
                Optional.empty(),
                Optional.empty(),
                afterRendering);
        }
        if (!rendering.rendered()) {
            Status status = rendering.status()
                    == ExactFactorizationExpressionRenderer.Status
                        .BUDGET_INCONCLUSIVE
                ? Status.BUDGET_INCONCLUSIVE
                : Status.UNSUPPORTED;
            return Result.failure(
                status,
                rendering.detailCode(),
                occurrence,
                factorization,
                candidateIndex,
                candidateCertificate,
                Optional.of(rendering),
                Optional.empty(),
                Optional.empty(),
                afterRendering);
        }

        String expression = rendering.expression().orElseThrow();
        PolynomialWorkLedger parseWork = new PolynomialWorkLedger(Map.of(
            "transform.exact-reparse-input-code-units",
            (long) expression.length()));
        PolynomialWorkLedger afterParse = merge(afterRendering, parseWork);
        if (!withinOriginalAuthority(factorization, afterParse)) {
            return Result.failure(
                Status.BUDGET_INCONCLUSIVE,
                "EXACT_REPARSE_EXCEEDED_ORIGINAL_WORK_AUTHORITY",
                occurrence,
                factorization,
                candidateIndex,
                candidateCertificate,
                Optional.of(rendering),
                Optional.empty(),
                Optional.empty(),
                afterParse);
        }

        ExactParsedTerm reparsed;
        try {
            reparsed = parser.parseExactTerm(expression);
        } catch (RuntimeException exception) {
            return Result.failure(
                Status.TECHNICAL_FAILURE,
                technicalDetail(
                    "RENDERED_EXPRESSION_NOT_EXACTLY_PARSEABLE",
                    exception),
                occurrence,
                factorization,
                candidateIndex,
                candidateCertificate,
                Optional.of(rendering),
                Optional.empty(),
                Optional.empty(),
                afterParse);
        }

        ExactParsedUnivariatePolynomialView.Analysis reconstruction =
            reparseView.analyze(reparsed);
        PolynomialWorkLedger totalWork = merge(
            afterParse,
            reconstruction.work().asPolynomialWorkLedger());
        if (!withinOriginalAuthority(factorization, totalWork)) {
            return Result.failure(
                Status.BUDGET_INCONCLUSIVE,
                "RECONSTRUCTION_EXCEEDED_ORIGINAL_WORK_AUTHORITY",
                occurrence,
                factorization,
                candidateIndex,
                candidateCertificate,
                Optional.of(rendering),
                Optional.of(reparsed),
                Optional.of(reconstruction),
                totalWork);
        }
        if (reconstruction.status()
                == ExactParsedUnivariatePolynomialView.Status
                    .BUDGET_INCONCLUSIVE) {
            return Result.failure(
                Status.BUDGET_INCONCLUSIVE,
                reconstruction.detailCode(),
                occurrence,
                factorization,
                candidateIndex,
                candidateCertificate,
                Optional.of(rendering),
                Optional.of(reparsed),
                Optional.of(reconstruction),
                totalWork);
        }
        if (!reconstruction.supported()) {
            return Result.failure(
                Status.TECHNICAL_FAILURE,
                "RENDERED_EXPRESSION_OUTSIDE_EXACT_POLYNOMIAL_VIEW_"
                    + reconstruction.detailCode(),
                occurrence,
                factorization,
                candidateIndex,
                candidateCertificate,
                Optional.of(rendering),
                Optional.of(reparsed),
                Optional.of(reconstruction),
                totalWork);
        }

        SparsePolynomial<ExactRational> expected =
            factorization.request().orElseThrow().source();
        SparsePolynomial<ExactRational> actual =
            reconstruction.polynomial().orElseThrow();
        if (!expected.ring().equals(actual.ring())) {
            return Result.failure(
                Status.TECHNICAL_FAILURE,
                "RENDERED_EXPRESSION_RING_MISMATCH",
                occurrence,
                factorization,
                candidateIndex,
                candidateCertificate,
                Optional.of(rendering),
                Optional.of(reparsed),
                Optional.of(reconstruction),
                totalWork);
        }
        if (!expected.equals(actual)) {
            return Result.failure(
                Status.TECHNICAL_FAILURE,
                "RENDERED_EXPRESSION_POLYNOMIAL_MISMATCH",
                occurrence,
                factorization,
                candidateIndex,
                candidateCertificate,
                Optional.of(rendering),
                Optional.of(reparsed),
                Optional.of(reconstruction),
                totalWork);
        }

        Kind kind = candidate.backendClaim()
                == FactorizationEngine.BackendClaim.COMPLETE_FACTORIZATION
            ? Kind.VERIFIED_DECOMPOSITION_WITH_COMPLETE_BACKEND_CLAIM
            : Kind.VERIFIED_DECOMPOSITION;
        return Result.transformed(
            kind,
            occurrence,
            factorization,
            candidateIndex,
            candidateCertificate,
            rendering,
            reparsed,
            reconstruction,
            totalWork);
    }

    private long continuationCeiling() {
        long result = renderer.policy().maxWorkUnits();
        result = Math.addExact(
            result,
            renderer.policy().maxOutputCodeUnits());
        result = Math.addExact(
            result,
            reparseView.budget().maxVisitedNodes());
        return Math.addExact(
            result,
            reparseView.budget().maxArithmeticOperations());
    }

    private static boolean withinOriginalAuthority(
        ExactParsedFactorizationPipeline.Result factorization,
        PolynomialWorkLedger work
    ) {
        return work.within(
            factorization.policy().maxTotalWorkUnits());
    }

    private static Result unavailableFactorization(
        OccurrenceEvidence occurrence,
        ExactParsedFactorizationPipeline.Result factorization
    ) {
        Status status = switch (factorization.status()) {
            case BUDGET_INCONCLUSIVE -> Status.BUDGET_INCONCLUSIVE;
            case UNSUPPORTED_EXPRESSION, UNSUPPORTED_REQUEST ->
                Status.UNSUPPORTED;
            case EXECUTED -> throw new IllegalStateException(
                "executed factorization must have a verifier report");
        };
        return Result.failure(
            status,
            factorization.detailCode(),
            occurrence,
            factorization,
            -1,
            "",
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            factorization.totalWork());
    }

    private static Result unavailableReport(
        OccurrenceEvidence occurrence,
        ExactParsedFactorizationPipeline.Result factorization,
        FactorizationVerifier.Report<ExactRational> report
    ) {
        Status status = switch (report.status()) {
            case IRREDUCIBLE -> Status.IRREDUCIBLE;
            case NO_FACTORIZATION_FOUND ->
                report.claimStrength()
                    == FactorizationVerifier.ClaimStrength
                        .BACKEND_CLAIMED_IRREDUCIBLE
                    ? Status.IRREDUCIBLE
                    : Status.NO_CANDIDATE;
            case BUDGET_INCONCLUSIVE -> Status.BUDGET_INCONCLUSIVE;
            case UNSUPPORTED_DOMAIN, UNSUPPORTED_REQUEST ->
                Status.UNSUPPORTED;
            case TECHNICAL_FAILURE -> Status.TECHNICAL_FAILURE;
            case COMPLETE_FACTORIZATION, PARTIAL_FACTORIZATION ->
                throw new IllegalStateException(
                    "successful factorization report requires candidates");
        };
        return Result.failure(
            status,
            report.detailCode(),
            occurrence,
            factorization,
            -1,
            "",
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            factorization.totalWork());
    }

    private static String sourceContinuityViolation(
        ExactParsedTerm source,
        ExactParsedFactorizationPipeline.Result factorization
    ) {
        ExactParsedUnivariatePolynomialView.Analysis extraction =
            factorization.extraction();
        if (!source.source().equals(extraction.source())) {
            return "SOURCE_TEXT_DOES_NOT_MATCH_FACTORIZATION_EVIDENCE";
        }
        List<ExactParsedTerm.LiteralOccurrence> actual =
            source.literals();
        List<ExactParsedUnivariatePolynomialView.LiteralBinding> expected =
            extraction.literals();
        if (actual.size() != expected.size()) {
            return "SOURCE_LITERAL_COUNT_DOES_NOT_MATCH_FACTORIZATION_EVIDENCE";
        }
        for (int index = 0; index < actual.size(); index++) {
            ExactParsedTerm.LiteralOccurrence literal = actual.get(index);
            ExactParsedUnivariatePolynomialView.LiteralBinding binding =
                expected.get(index);
            if (literal.startInclusive() != binding.startInclusive()
                    || literal.endExclusive() != binding.endExclusive()
                    || !literal.sourceLexeme().equals(binding.sourceLexeme())
                    || !literal.evidence().canonicalValue().equals(
                        binding.canonicalValue())
                    || !literal.evidence().valueId().equals(
                        binding.valueId())
                    || !literal.evidence().certificateHash().equals(
                        binding.certificateHash())) {
                return "SOURCE_LITERAL_EVIDENCE_DOES_NOT_MATCH_FACTORIZATION";
            }
        }
        if (factorization.executed()) {
            SparsePolynomial<ExactRational> extracted =
                extraction.polynomial().orElseThrow();
            if (!extracted.equals(
                    factorization.request().orElseThrow().source())) {
                return "EXTRACTION_DOES_NOT_MATCH_FACTORIZATION_REQUEST";
            }
        }
        return null;
    }

    private static String technicalDetail(
        String prefix,
        RuntimeException exception
    ) {
        String simple = exception.getClass().getSimpleName();
        return prefix + "_" + (simple.isBlank()
            ? "RUNTIME"
            : simple.toUpperCase(java.util.Locale.ROOT));
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
        TRANSFORMED,
        NO_CANDIDATE,
        IRREDUCIBLE,
        UNSUPPORTED,
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE,
        SOURCE_EVIDENCE_MISMATCH
    }

    public enum Kind {
        NONE,
        VERIFIED_DECOMPOSITION,
        VERIFIED_DECOMPOSITION_WITH_COMPLETE_BACKEND_CLAIM
    }

    /** Root occurrence identity plus source-bound exact evidence hash. */
    public record OccurrenceEvidence(
        List<Integer> path,
        String sourceText,
        String sourceEvidenceHash
    ) {
        public OccurrenceEvidence {
            path = List.copyOf(Objects.requireNonNull(path, "path"));
            if (path.stream().anyMatch(index -> index == null || index < 0)
                    || sourceText == null
                    || sourceEvidenceHash == null
                    || !sourceEvidenceHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "factorization occurrence evidence is invalid");
            }
        }

        private static OccurrenceEvidence root(ExactParsedTerm source) {
            return new OccurrenceEvidence(
                List.of(),
                source.source(),
                PolynomialEvidence.sha256(sourceMaterial(source)));
        }

        public boolean isRoot() {
            return path.isEmpty();
        }

        public String canonicalMaterial() {
            StringBuilder result = new StringBuilder();
            PolynomialEvidence.append(
                result,
                path.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(".")));
            PolynomialEvidence.append(result, sourceText);
            PolynomialEvidence.append(result, sourceEvidenceHash);
            return result.toString();
        }

        private static String sourceMaterial(ExactParsedTerm source) {
            StringBuilder result = new StringBuilder();
            PolynomialEvidence.append(result, source.source());
            PolynomialEvidence.append(
                result,
                Integer.toString(source.literals().size()));
            for (ExactParsedTerm.LiteralOccurrence literal
                    : source.literals()) {
                PolynomialEvidence.append(
                    result,
                    Integer.toString(literal.startInclusive()));
                PolynomialEvidence.append(
                    result,
                    Integer.toString(literal.endExclusive()));
                PolynomialEvidence.append(
                    result,
                    literal.sourceLexeme());
                PolynomialEvidence.append(
                    result,
                    literal.evidence().canonicalValue());
                PolynomialEvidence.append(
                    result,
                    literal.evidence().valueId());
                PolynomialEvidence.append(
                    result,
                    literal.evidence().certificateHash());
            }
            return result.toString();
        }
    }

    /** Issuer-owned exact expression transformation evidence. */
    public static final class Result {
        private final Status status;
        private final Kind kind;
        private final String detailCode;
        private final OccurrenceEvidence occurrence;
        private final ExactParsedFactorizationPipeline.Result factorization;
        private final int candidateIndex;
        private final String candidateCertificateHash;
        private final Optional<ExactFactorizationExpressionRenderer.Result>
            rendering;
        private final Optional<ExactParsedTerm> reparsed;
        private final Optional<ExactParsedUnivariatePolynomialView.Analysis>
            reconstruction;
        private final PolynomialWorkLedger totalWork;
        private final String certificateHash;

        private Result(
            Status status,
            Kind kind,
            String detailCode,
            OccurrenceEvidence occurrence,
            ExactParsedFactorizationPipeline.Result factorization,
            int candidateIndex,
            String candidateCertificateHash,
            Optional<ExactFactorizationExpressionRenderer.Result> rendering,
            Optional<ExactParsedTerm> reparsed,
            Optional<ExactParsedUnivariatePolynomialView.Analysis>
                reconstruction,
            PolynomialWorkLedger totalWork
        ) {
            this.status = Objects.requireNonNull(status, "status");
            this.kind = Objects.requireNonNull(kind, "kind");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "transformation detail code must not be blank");
            }
            this.detailCode = detailCode;
            this.occurrence = Objects.requireNonNull(
                occurrence,
                "occurrence");
            this.factorization = Objects.requireNonNull(
                factorization,
                "factorization");
            this.candidateIndex = candidateIndex;
            if (candidateCertificateHash == null
                    || !candidateCertificateHash.isEmpty()
                    && !candidateCertificateHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "transformation candidate certificate is invalid");
            }
            this.candidateCertificateHash = candidateCertificateHash;
            this.rendering = Objects.requireNonNull(
                rendering,
                "rendering");
            this.reparsed = Objects.requireNonNull(reparsed, "reparsed");
            this.reconstruction = Objects.requireNonNull(
                reconstruction,
                "reconstruction");
            this.totalWork = Objects.requireNonNull(
                totalWork,
                "totalWork");
            boolean transformed = status == Status.TRANSFORMED;
            if (transformed) {
                if (kind == Kind.NONE
                        || candidateIndex < 0
                        || candidateCertificateHash.isEmpty()
                        || rendering.isEmpty()
                        || !rendering.orElseThrow().rendered()
                        || reparsed.isEmpty()
                        || reconstruction.isEmpty()
                        || !reconstruction.orElseThrow().supported()) {
                    throw new IllegalArgumentException(
                        "transformed result lacks exact evidence");
                }
            } else if (kind != Kind.NONE) {
                throw new IllegalArgumentException(
                    "non-transformed result cannot retain a transformation kind");
            }
            if (!totalWork.within(
                    factorization.policy().maxTotalWorkUnits())) {
                throw new IllegalArgumentException(
                    "transformation work exceeds original authority");
            }
            this.certificateHash = PolynomialEvidence.sha256(
                evidenceMaterial());
        }

        private static Result transformed(
            Kind kind,
            OccurrenceEvidence occurrence,
            ExactParsedFactorizationPipeline.Result factorization,
            int candidateIndex,
            String candidateCertificateHash,
            ExactFactorizationExpressionRenderer.Result rendering,
            ExactParsedTerm reparsed,
            ExactParsedUnivariatePolynomialView.Analysis reconstruction,
            PolynomialWorkLedger totalWork
        ) {
            return new Result(
                Status.TRANSFORMED,
                kind,
                "EXACT_FACTORIZATION_TRANSFORMATION_RECONSTRUCTED",
                occurrence,
                factorization,
                candidateIndex,
                candidateCertificateHash,
                Optional.of(rendering),
                Optional.of(reparsed),
                Optional.of(reconstruction),
                totalWork);
        }

        private static Result failure(
            Status status,
            String detailCode,
            OccurrenceEvidence occurrence,
            ExactParsedFactorizationPipeline.Result factorization,
            int candidateIndex,
            String candidateCertificateHash,
            Optional<ExactFactorizationExpressionRenderer.Result> rendering,
            Optional<ExactParsedTerm> reparsed,
            Optional<ExactParsedUnivariatePolynomialView.Analysis>
                reconstruction,
            PolynomialWorkLedger totalWork
        ) {
            if (status == Status.TRANSFORMED) {
                throw new IllegalArgumentException(
                    "transformed status requires exact reconstruction");
            }
            return new Result(
                status,
                Kind.NONE,
                detailCode,
                occurrence,
                factorization,
                candidateIndex,
                candidateCertificateHash,
                rendering,
                reparsed,
                reconstruction,
                totalWork);
        }

        public Status status() {
            return status;
        }

        public Kind kind() {
            return kind;
        }

        public String detailCode() {
            return detailCode;
        }

        public OccurrenceEvidence occurrence() {
            return occurrence;
        }

        public ExactParsedFactorizationPipeline.Result factorization() {
            return factorization;
        }

        public OptionalInt candidateIndex() {
            return candidateIndex < 0
                ? OptionalInt.empty()
                : OptionalInt.of(candidateIndex);
        }

        public String candidateCertificateHash() {
            return candidateCertificateHash;
        }

        public Optional<ExactFactorizationExpressionRenderer.Result>
                rendering() {
            return rendering;
        }

        public Optional<String> transformedExpression() {
            return rendering.flatMap(
                ExactFactorizationExpressionRenderer.Result::expression);
        }

        public Optional<ExactParsedTerm> reparsed() {
            return reparsed;
        }

        public Optional<ExactParsedUnivariatePolynomialView.Analysis>
                reconstruction() {
            return reconstruction;
        }

        public PolynomialWorkLedger totalWork() {
            return totalWork;
        }

        public String certificateHash() {
            return certificateHash;
        }

        public boolean transformed() {
            return status == Status.TRANSFORMED;
        }

        public String canonicalMaterial() {
            StringBuilder result = new StringBuilder(evidenceMaterial());
            PolynomialEvidence.append(result, certificateHash);
            return result.toString();
        }

        private String evidenceMaterial() {
            StringBuilder result = new StringBuilder(TRANSFORMATION_ID);
            PolynomialEvidence.append(result, status.name());
            PolynomialEvidence.append(result, kind.name());
            PolynomialEvidence.append(result, detailCode);
            PolynomialEvidence.append(
                result,
                occurrence.canonicalMaterial());
            PolynomialEvidence.append(
                result,
                factorization.certificateHash());
            PolynomialEvidence.append(
                result,
                Integer.toString(candidateIndex));
            PolynomialEvidence.append(
                result,
                candidateCertificateHash);
            PolynomialEvidence.append(
                result,
                rendering.map(
                    ExactFactorizationExpressionRenderer.Result
                        ::certificateHash).orElse(""));
            PolynomialEvidence.append(
                result,
                reparsed.map(ExactParsedTerm::source).orElse(""));
            PolynomialEvidence.append(
                result,
                reconstruction.map(
                    ExactParsedUnivariatePolynomialView.Analysis
                        ::certificateHash).orElse(""));
            PolynomialEvidence.append(
                result,
                totalWork.canonicalMaterial());
            return result.toString();
        }
    }
}
