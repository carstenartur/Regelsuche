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
 * one content-addressed result. Source validation, factorization, rendering,
 * parsing and reconstruction share the original pipeline's non-resettable work
 * ceiling.</p>
 */
public final class ExactFactorizationTransformationPipeline {
    public static final String TRANSFORMATION_ID =
        "regelsuche.exact-factorization-transformation/v1";
    private static final long SOURCE_TEXT_VALIDATION_MULTIPLIER = 4L;
    private static final long SOURCE_LITERAL_VALIDATION_UNITS = 512L;

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
        SourceAuthorization authorization = authorizeSource(
            source,
            factorization);
        Result authorizationFailure = authorizationFailure(
            authorization,
            factorization,
            -1);
        if (authorizationFailure != null) {
            return authorizationFailure;
        }
        if (!factorization.executed()) {
            return unavailableFactorization(
                authorization.occurrence(),
                factorization,
                authorization.totalWork());
        }
        FactorizationVerifier.Report<ExactRational> report =
            factorization.report().orElseThrow();
        if (!report.successful()) {
            return unavailableReport(
                authorization.occurrence(),
                factorization,
                report,
                authorization.totalWork());
        }
        if (report.candidates().size() != 1) {
            return Result.failure(
                Status.UNSUPPORTED,
                "MULTIPLE_CANDIDATES_REQUIRE_EXPLICIT_SELECTION",
                authorization.occurrence(),
                factorization,
                -1,
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                authorization.totalWork());
        }
        return transformSelected(
            factorization,
            report,
            0,
            authorization);
    }

    /** Transforms the root with one explicitly selected verifier candidate. */
    public Result transformRoot(
        ExactParsedTerm source,
        ExactParsedFactorizationPipeline.Result factorization,
        int candidateIndex
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(factorization, "factorization");
        SourceAuthorization authorization = authorizeSource(
            source,
            factorization);
        Result authorizationFailure = authorizationFailure(
            authorization,
            factorization,
            candidateIndex);
        if (authorizationFailure != null) {
            return authorizationFailure;
        }
        if (!factorization.executed()) {
            return unavailableFactorization(
                authorization.occurrence(),
                factorization,
                authorization.totalWork());
        }
        FactorizationVerifier.Report<ExactRational> report =
            factorization.report().orElseThrow();
        if (!report.successful()) {
            return unavailableReport(
                authorization.occurrence(),
                factorization,
                report,
                authorization.totalWork());
        }
        if (candidateIndex < 0
                || candidateIndex >= report.candidates().size()) {
            return Result.failure(
                Status.UNSUPPORTED,
                "CANDIDATE_SELECTION_OUT_OF_RANGE",
                authorization.occurrence(),
                factorization,
                candidateIndex,
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                authorization.totalWork());
        }
        return transformSelected(
            factorization,
            report,
            candidateIndex,
            authorization);
    }

    private Result transformSelected(
        ExactParsedFactorizationPipeline.Result factorization,
        FactorizationVerifier.Report<ExactRational> report,
        int candidateIndex,
        SourceAuthorization authorization
    ) {
        FactorizationVerifier.VerifiedCandidate<ExactRational> candidate =
            report.candidates().get(candidateIndex);
        String candidateCertificate =
            candidate.verificationCertificateHash();
        long continuationCeiling = continuationCeiling();
        long remaining = factorization.policy().maxTotalWorkUnits()
            - authorization.totalWork().totalWorkUnits();
        if (remaining < continuationCeiling) {
            return Result.failure(
                Status.BUDGET_INCONCLUSIVE,
                "INSUFFICIENT_REMAINING_TRANSFORMATION_AUTHORITY",
                authorization.occurrence(),
                factorization,
                candidateIndex,
                candidateCertificate,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                authorization.totalWork());
        }

        ExactFactorizationExpressionRenderer.Result rendering =
            renderer.render(candidate);
        PolynomialWorkLedger afterRendering = merge(
            authorization.totalWork(),
            rendering.work());
        if (!withinOriginalAuthority(factorization, afterRendering)) {
            return Result.failure(
                Status.BUDGET_INCONCLUSIVE,
                "RENDERING_EXCEEDED_ORIGINAL_WORK_AUTHORITY",
                authorization.occurrence(),
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
                authorization.occurrence(),
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
                authorization.occurrence(),
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
                authorization.occurrence(),
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
                authorization.occurrence(),
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
                authorization.occurrence(),
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
                authorization.occurrence(),
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
                authorization.occurrence(),
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
                authorization.occurrence(),
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
            authorization.occurrence(),
            factorization,
            candidateIndex,
            candidateCertificate,
            rendering,
            reparsed,
            reconstruction,
            totalWork);
    }

    private static SourceAuthorization authorizeSource(
        ExactParsedTerm source,
        ExactParsedFactorizationPipeline.Result factorization
    ) {
        OccurrenceEvidence occurrence = OccurrenceEvidence.root(
            factorization);
        PolynomialWorkLedger validationWork = sourceValidationWork(
            source,
            factorization.extraction());
        long remaining = factorization.policy().maxTotalWorkUnits()
            - factorization.totalWork().totalWorkUnits();
        if (!validationWork.within(remaining)) {
            return new SourceAuthorization(
                occurrence,
                factorization.totalWork(),
                AuthorizationStatus.INSUFFICIENT_AUTHORITY,
                "SOURCE_EVIDENCE_VALIDATION_AUTHORITY_INSUFFICIENT");
        }
        PolynomialWorkLedger totalWork = merge(
            factorization.totalWork(),
            validationWork);
        String violation = sourceContinuityViolation(
            source,
            factorization);
        return new SourceAuthorization(
            occurrence,
            totalWork,
            violation == null
                ? AuthorizationStatus.AUTHORIZED
                : AuthorizationStatus.MISMATCH,
            violation == null ? "SOURCE_EVIDENCE_VALIDATED" : violation);
    }

    private static PolynomialWorkLedger sourceValidationWork(
        ExactParsedTerm source,
        ExactParsedUnivariatePolynomialView.Analysis extraction
    ) {
        long sourceCodeUnits = Math.addExact(
            (long) source.source().length(),
            extraction.source().length());
        long textWork = Math.multiplyExact(
            SOURCE_TEXT_VALIDATION_MULTIPLIER,
            sourceCodeUnits);
        long comparedLiterals = Math.max(
            source.literals().size(),
            extraction.literals().size());
        long literalWork = Math.multiplyExact(
            SOURCE_LITERAL_VALIDATION_UNITS,
            comparedLiterals);
        return new PolynomialWorkLedger(Map.of(
            "transform.source-evidence-literal-validation",
            literalWork,
            "transform.source-evidence-text-validation",
            textWork));
    }

    private static Result authorizationFailure(
        SourceAuthorization authorization,
        ExactParsedFactorizationPipeline.Result factorization,
        int candidateIndex
    ) {
        return switch (authorization.status()) {
            case AUTHORIZED -> null;
            case INSUFFICIENT_AUTHORITY -> Result.failure(
                Status.BUDGET_INCONCLUSIVE,
                authorization.detailCode(),
                authorization.occurrence(),
                factorization,
                candidateIndex,
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                authorization.totalWork());
            case MISMATCH -> Result.failure(
                Status.SOURCE_EVIDENCE_MISMATCH,
                authorization.detailCode(),
                authorization.occurrence(),
                factorization,
                candidateIndex,
                "",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                authorization.totalWork());
        };
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
        ExactParsedFactorizationPipeline.Result factorization,
        PolynomialWorkLedger totalWork
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
            totalWork);
    }

    private static Result unavailableReport(
        OccurrenceEvidence occurrence,
        ExactParsedFactorizationPipeline.Result factorization,
        FactorizationVerifier.Report<ExactRational> report,
        PolynomialWorkLedger totalWork
    ) {
        Status status = switch (report.status()) {
            case IRREDUCIBLE -> Status.IRREDUCIBLE;
            case NO_FACTORIZATION_FOUND ->
                report.claimStrength()
                    == FactorizationVerifier.ClaimStrength
                        .BACKEND_CLAIMED_IRREDUCIBLE
                    ? Status.BACKEND_CLAIMED_IRREDUCIBLE
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
            totalWork);
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
        BACKEND_CLAIMED_IRREDUCIBLE,
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

    private enum AuthorizationStatus {
        AUTHORIZED,
        INSUFFICIENT_AUTHORITY,
        MISMATCH
    }

    private record SourceAuthorization(
        OccurrenceEvidence occurrence,
        PolynomialWorkLedger totalWork,
        AuthorizationStatus status,
        String detailCode
    ) {
        private SourceAuthorization {
            Objects.requireNonNull(occurrence, "occurrence");
            Objects.requireNonNull(totalWork, "totalWork");
            Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "source authorization detail must not be blank");
            }
        }
    }

    /** Root occurrence identity plus source-bound exact extraction evidence. */
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

        private static OccurrenceEvidence root(
            ExactParsedFactorizationPipeline.Result factorization
        ) {
            return new OccurrenceEvidence(
                List.of(),
                factorization.extraction().source(),
                factorization.extraction().certificateHash());
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

        /**
         * Returns parser-compatible syntax produced by the renderer even when
         * the later reparse or reconstruction stage rejected it.
         */
        public Optional<String> renderedExpression() {
            return rendering.flatMap(
                ExactFactorizationExpressionRenderer.Result::expression);
        }

        /**
         * Returns a replacement expression only after the complete exact
         * reparse and reconstruction boundary issued a transformation.
         */
        public Optional<String> transformedExpression() {
            return transformed()
                ? renderedExpression()
                : Optional.empty();
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
