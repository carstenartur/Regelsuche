package de.regelsuche.transform;

import de.regelsuche.ast.Expr;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExactParsedSubtermProjector;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.BinaryQuarticFactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.FactorizationVerifier;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.PolynomialWorkSink;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Measured occurrence adapter for the existing integer binary-quartic engine.
 * Construction and result issuance belong to the specialized operator. Source
 * projection, semantic inspection, engine/verifier execution, original rendering
 * and structural replacement use the caller's one cumulative work authority.
 */
public final class MeasuredPolynomialDecompositionPipeline {
    public static final String PIPELINE_ID =
        "regelsuche.measured-polynomial-decomposition-pipeline/v1";

    private final PolynomialDecompositionSynthesisOperator operator;
    private final PolynomialSemanticView semanticView;
    private final BinaryQuarticFactorizationEngine engine;
    private final FactorizationRequest.StructuralLimits structuralLimits;
    private final int maxCandidates;
    private final long maxEngineAndVerifierWork;

    MeasuredPolynomialDecompositionPipeline(PolynomialDecompositionSynthesisOperator operator,
            PolynomialSemanticView semanticView, BinaryQuarticFactorizationEngine engine,
            FactorizationRequest.StructuralLimits structuralLimits, int maxCandidates, long maxEngineAndVerifierWork) {
        this.operator = Objects.requireNonNull(operator, "operator");
        this.semanticView = Objects.requireNonNull(semanticView, "semanticView");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.structuralLimits = Objects.requireNonNull(structuralLimits, "structuralLimits");
        this.maxCandidates = maxCandidates;
        this.maxEngineAndVerifierWork = maxEngineAndVerifierWork;
    }

    Result factor(ExactParsedTerm root, TreePosition position, PolynomialWorkAuthority authority) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(authority, "authority");
        ExactParsedSubtermProjector.Result projection = new ExactParsedSubtermProjector(
            ExactParsedSubtermProjector.Policy.boundedDefaults(), authority)
            .project(root, position.path(), position.text());
        Execution execution = new Execution(projection, authority);
        if (!projection.successful()) {
            return execution.finish(projectionStatus(projection.status()), projection.detailCode());
        }
        try {
            execution.extraction = semanticView.analyze(projection.projected().orElseThrow(), authority);
            execution.work.retain(execution.extraction.work());
            PolynomialSemanticView.Analysis analysis = execution.extraction.analysis();
            if (!analysis.supported()) {
                return execution.finish(semanticStatus(analysis.status()), analysis.detailCode());
            }

            PolynomialSemanticView.PolynomialView view = analysis.view();
            execution.work.consume("exact-parsed-view.request-shape-inspections", 1);
            if (view.polynomial().isZero()) {
                return execution.finish(Status.NO_FACTORIZATION_FOUND,
                    "ZERO_POLYNOMIAL_HAS_NO_FINITE_FACTORIZATION");
            }
            if (view.polynomial().ring().variableCount() == 1) {
                execution.work.consume("exact-parsed-view.homogenization-degree-term-visits", view.polynomial().termCount());
                if (view.polynomial().totalDegree() <= 4) {
                    execution.work.consume(new PolynomialWorkLedger(Map.of(
                        "exact-parsed-view.homogenization-terms", (long) view.polynomial().termCount(),
                        // The existing view and polynomial each validate the degree again.
                        "exact-parsed-view.homogenization-degree-term-visits", 2L * view.polynomial().termCount(),
                        "exact-parsed-view.polynomial-construction-terms", (long) view.polynomial().termCount())));
                    view = view.homogenizeWithUnitAtom(4);
                }
            }
            execution.view = view;

            PolynomialWorkLedger dispatch = authority.opaqueInvocationOverhead();
            if (authority.remainingOpaqueWorkUnits() <= dispatch.totalWorkUnits()) {
                return execution.finish(Status.BUDGET_INCONCLUSIVE, "NO_FACTORIZATION_WORK_BUDGET_REMAINING");
            }
            execution.work.consume(dispatch);
            long admitted = Math.min(maxEngineAndVerifierWork, authority.remainingOpaqueWorkUnits());
            if (admitted < 1) {
                return execution.finish(Status.BUDGET_INCONCLUSIVE, "NO_FACTORIZATION_WORK_BUDGET_REMAINING");
            }
            execution.request = FactorizationRequest.verifiedDecomposition(
                view.polynomial(), structuralLimits, maxCandidates, admitted);
            execution.report = FactorizationVerifier.execute(engine, execution.request);
            // An opaque call must settle its complete unchanged ledger before any further work.
            if (!execution.report.work().within(admitted)) {
                throw new IllegalStateException("SPECIALIZED_FACTORIZATION_EXCEEDED_ADMITTED_RAW_AUTHORITY");
            }
            try {
                execution.work.consume(execution.report.work());
            } catch (PolynomialWorkAuthority.LimitReached exception) {
                throw new IllegalStateException("SPECIALIZED_FACTORIZATION_WORK_REJECTED_BY_SHARED_AUTHORITY", exception);
            }
            if (!execution.report.successful()) {
                return execution.finish(reportStatus(execution.report.status()), execution.report.detailCode());
            }

            execution.candidateIndex = 0;
            execution.rendered = operator.render(execution.report.candidates().getFirst(), view,
                execution.report, execution.work);
            String rendered = execution.rendered.transformedExpression();
            execution.work.consume("transform.exact-reparse-source-code-units", rendered.length());
            try {
                execution.reparsed = new ExpressionParser().parseExactTerm(rendered);
            } catch (IllegalArgumentException exception) {
                return execution.finish(Status.TECHNICAL_FAILURE, "SPECIALIZED_RENDERED_EXPRESSION_NOT_EXACTLY_PARSEABLE");
            }

            execution.work.consume(new PolynomialWorkLedger(Map.of(
                "nested.replacement-occurrences", 1L,
                "nested.replacement-path-navigation", (long) position.path().size(),
                "nested.replacement-ancestor-copies", (long) position.path().size())));
            execution.replacement = position.replaceAt(root.expression(), execution.reparsed.expression());
            if (!execution.replacement.success()
                    || execution.replacement.copiedAncestors() != position.path().size()
                    || execution.replacement.selectedSubtree().orElseThrow()
                        != projection.projected().orElseThrow().expression()) {
                return execution.finish(Status.TECHNICAL_FAILURE, "SPECIALIZED_REPLACEMENT_OCCURRENCE_MISMATCH");
            }
            execution.work.consume("nested.rewritten-path-replay", position.path().size() + 1L);
            Expr replayed = position.subtreeAt(execution.replacement.rewrittenRoot().orElseThrow()).orElse(null);
            if (replayed != execution.reparsed.expression()) {
                return execution.finish(Status.TECHNICAL_FAILURE, "SPECIALIZED_REWRITTEN_PATH_MISMATCH");
            }

            ExactParsedTerm.SourceRange range = projection.selectedRange().orElseThrow();
            long outputLength = position.isRoot() ? rendered.length()
                : root.source().length() - (long) (range.endExclusive() - range.startInclusive())
                    + rendered.length() + 2L;
            execution.work.consume("nested.rewritten-exact-source-code-units", outputLength);
            execution.rewrittenSource = position.isRoot() ? rendered
                : root.source().substring(0, range.startInclusive()) + "(" + rendered + ")"
                    + root.source().substring(range.endExclusive());
            return execution.finish(Status.GENERATED, execution.report.detailCode());
        } catch (PolynomialWorkAuthority.LimitReached exception) {
            return execution.finish(Status.BUDGET_INCONCLUSIVE, exception.getMessage());
        }
    }

    private final class Execution {
        private final ExactParsedSubtermProjector.Result projection;
        private final Work work;
        private PolynomialSemanticView.MeasuredAnalysis extraction;
        private PolynomialSemanticView.PolynomialView view;
        private FactorizationRequest<BigInteger> request;
        private FactorizationVerifier.Report<BigInteger> report;
        private int candidateIndex = -1;
        private ExpressionFactorizationReport.RenderedFactorization rendered;
        private ExactParsedTerm reparsed;
        private TreePosition.ReplacementResult replacement;
        private String rewrittenSource;

        private Execution(ExactParsedSubtermProjector.Result projection, PolynomialWorkAuthority authority) {
            this.projection = projection;
            this.work = new Work(authority);
            work.retain(new PolynomialWorkLedger(projection.work().stages()));
        }

        private Result finish(Status status, String detailCode) {
            return new Result(status, detailCode, projection, Optional.ofNullable(extraction),
                Optional.ofNullable(view), Optional.ofNullable(request), Optional.ofNullable(report),
                candidateIndex < 0 ? OptionalInt.empty() : OptionalInt.of(candidateIndex),
                Optional.ofNullable(rendered), Optional.ofNullable(reparsed), Optional.ofNullable(replacement),
                status == Status.GENERATED ? Optional.of(rewrittenSource) : Optional.empty(), work.ledger(),
                structuralLimits, maxCandidates, maxEngineAndVerifierWork);
        }
    }

    /** Terminal outcome; misses do not certify irreducibility. */
    public enum Status {
        GENERATED,
        UNSUPPORTED_SEMANTIC_VIEW,
        UNSUPPORTED_FACTORIZATION_REQUEST,
        NO_FACTORIZATION_FOUND,
        IRREDUCIBLE,
        BUDGET_INCONCLUSIVE,
        POSITION_NOT_PRESENT,
        POSITION_STALE,
        TECHNICAL_FAILURE
    }

    /**
     * Issuer-owned result binding the original request/report to its exact
     * occurrence and output. Its ledger contains this invocation's admitted
     * work; earlier work remains in the same caller authority. Partial render or
     * replacement evidence never exposes an authorized transformed expression.
     */
    public static final class Result {
        private final Status status;
        private final String detailCode;
        private final ExactParsedSubtermProjector.Result projection;
        private final Optional<PolynomialSemanticView.MeasuredAnalysis> extraction;
        private final Optional<FactorizationRequest<BigInteger>> request;
        private final Optional<FactorizationVerifier.Report<BigInteger>> report;
        private final OptionalInt selectedCandidateIndex;
        private final Optional<ExpressionFactorizationReport.RenderedFactorization> selectedCandidate;
        private final Optional<ExactParsedTerm> reparsedReplacement;
        private final Optional<TreePosition.ReplacementResult> replacement;
        private final Optional<String> rewrittenRootSource;
        private final PolynomialWorkLedger totalWork;
        private final String certificateHash;
        private final String canonicalMaterial;
        private final List<PrimitiveStep> primitiveExpansion;

        private Result(Status status, String detailCode, ExactParsedSubtermProjector.Result projection,
                Optional<PolynomialSemanticView.MeasuredAnalysis> extraction,
                Optional<PolynomialSemanticView.PolynomialView> view,
                Optional<FactorizationRequest<BigInteger>> request,
                Optional<FactorizationVerifier.Report<BigInteger>> report, OptionalInt selectedCandidateIndex,
                Optional<ExpressionFactorizationReport.RenderedFactorization> selectedCandidate,
                Optional<ExactParsedTerm> reparsedReplacement, Optional<TreePosition.ReplacementResult> replacement,
                Optional<String> rewrittenRootSource, PolynomialWorkLedger totalWork,
                FactorizationRequest.StructuralLimits structuralLimits, int maxCandidates, long maxEngineAndVerifierWork) {
            this.status = Objects.requireNonNull(status, "status");
            this.detailCode = Objects.requireNonNull(detailCode, "detailCode");
            this.projection = Objects.requireNonNull(projection, "projection");
            this.extraction = Objects.requireNonNull(extraction, "extraction");
            this.request = Objects.requireNonNull(request, "request");
            this.report = Objects.requireNonNull(report, "report");
            this.selectedCandidateIndex = Objects.requireNonNull(selectedCandidateIndex, "selectedCandidateIndex");
            this.selectedCandidate = Objects.requireNonNull(selectedCandidate, "selectedCandidate");
            this.reparsedReplacement = Objects.requireNonNull(reparsedReplacement, "reparsedReplacement");
            this.replacement = Objects.requireNonNull(replacement, "replacement");
            this.rewrittenRootSource = Objects.requireNonNull(rewrittenRootSource, "rewrittenRootSource");
            this.totalWork = Objects.requireNonNull(totalWork, "totalWork");
            if (detailCode.isBlank() || request.isPresent() != report.isPresent()
                    || generated() != rewrittenRootSource.isPresent()
                    || selectedCandidateIndex.isPresent() && selectedCandidateIndex.getAsInt() != 0
                    || selectedCandidate.isPresent() && (report.isEmpty() || !report.orElseThrow().successful()
                        || selectedCandidate.orElseThrow().factorization() != report.orElseThrow().candidates().getFirst())
                    || generated() && (selectedCandidate.isEmpty() || reparsedReplacement.isEmpty() || replacement.isEmpty())) {
                throw new IllegalArgumentException("measured specialized result lacks issuer continuity");
            }
            String requestMaterial = request.map(FactorizationRequest::canonicalMaterial).orElse("");
            StringBuilder renderingMaterial = new StringBuilder(PIPELINE_ID + "/rendering");
            selectedCandidate.ifPresent(candidate -> {
                append(renderingMaterial, candidate.transformedExpression());
                append(renderingMaterial, candidate.applicationKey());
                append(renderingMaterial, candidate.factorization().verificationCertificateHash());
            });
            String reparsedMaterial = reparsedReplacement.map(MeasuredPolynomialDecompositionPipeline::exactReparseMaterial)
                .orElse("");
            StringBuilder material = new StringBuilder(PIPELINE_ID);
            append(material, PolynomialDecompositionSynthesisOperator.RULE_ID);
            append(material, BinaryQuarticFactorizationEngine.ENGINE_ID);
            append(material, structuralLimits.canonicalMaterial());
            append(material, Integer.toString(maxCandidates));
            append(material, Long.toString(maxEngineAndVerifierWork));
            append(material, status.name());
            append(material, detailCode);
            append(material, projection.certificateHash());
            append(material, extraction.map(value -> value.analysis().status().name()).orElse(""));
            append(material, extraction.map(value -> value.analysis().detailCode()).orElse(""));
            append(material, view.map(PolynomialSemanticView.PolynomialView::canonicalMaterial).orElse(""));
            append(material, requestMaterial);
            append(material, report.map(FactorizationVerifier.Report::verificationHash).orElse(""));
            append(material, selectedCandidateIndex.isPresent() ? Integer.toString(selectedCandidateIndex.getAsInt()) : "");
            append(material, selectedCandidate.map(ExpressionFactorizationReport.RenderedFactorization::transformedExpression)
                .orElse(""));
            append(material, selectedCandidate.map(ExpressionFactorizationReport.RenderedFactorization::applicationKey)
                .orElse(""));
            append(material, reparsedMaterial);
            append(material, replacement.map(value -> Integer.toString(value.copiedAncestors())).orElse(""));
            append(material, rewrittenRootSource.orElse(""));
            append(material, totalWork.canonicalMaterial());
            certificateHash = sha256(material.toString());
            append(material, certificateHash);
            canonicalMaterial = material.toString();
            primitiveExpansion = generated() ? List.of(
                new PrimitiveStep("EXACT_SOURCE_EVIDENCE", sourceEvidenceHash().orElseThrow()),
                new PrimitiveStep("EXACT_INTEGER_FACTORIZATION_REQUEST", sha256(requestMaterial)),
                new PrimitiveStep("VERIFIER_SELECTED_CANDIDATE",
                    selectedCandidate.orElseThrow().factorization().verificationCertificateHash()),
                new PrimitiveStep("EXACT_FACTOR_RENDERING", sha256(renderingMaterial.toString())),
                new PrimitiveStep("EXACT_REPARSE", sha256(reparsedMaterial)),
                new PrimitiveStep("SPECIALIZED_OCCURRENCE_REPLACEMENT", certificateHash)) : List.of();
        }

        public Status status() { return status; }
        public String detailCode() { return detailCode; }
        public String engineId() { return BinaryQuarticFactorizationEngine.ENGINE_ID; }
        public String ruleId() { return PolynomialDecompositionSynthesisOperator.RULE_ID; }
        public ExactParsedSubtermProjector.Result projection() { return projection; }
        public Optional<String> rootSourceHash() { return projection.rootSourceHash(); }
        public List<Integer> path() { return projection.path(); }
        public Optional<String> sourceOccurrenceExpression() { return projection.projected().map(ExactParsedTerm::source); }
        public Optional<String> sourceEvidenceHash() {
            return projection.successful() ? Optional.of(projection.certificateHash()) : Optional.empty();
        }
        public Optional<PolynomialSemanticView.MeasuredAnalysis> extraction() { return extraction; }
        public Optional<FactorizationRequest<BigInteger>> request() { return request; }
        public Optional<FactorizationVerifier.Report<BigInteger>> report() { return report; }
        public OptionalInt selectedCandidateIndex() { return selectedCandidateIndex; }
        public Optional<ExpressionFactorizationReport.RenderedFactorization> selectedCandidate() { return selectedCandidate; }
        public Optional<String> transformedExpression() {
            return generated() ? selectedCandidate.map(ExpressionFactorizationReport.RenderedFactorization::transformedExpression)
                : Optional.empty();
        }
        public Optional<String> applicationKey() {
            return generated() ? selectedCandidate.map(ExpressionFactorizationReport.RenderedFactorization::applicationKey)
                : Optional.empty();
        }
        public Optional<ExactParsedTerm> reparsedReplacement() { return reparsedReplacement; }
        public Optional<TreePosition.ReplacementResult> replacement() { return replacement; }
        public Optional<String> rewrittenRootSource() { return rewrittenRootSource; }
        public PolynomialWorkLedger totalWork() { return totalWork; }
        public PolynomialWorkLedger rawWork() { return totalWork; }
        public String certificateHash() { return certificateHash; }
        public String canonicalMaterial() { return canonicalMaterial; }
        /** Executed proof stages, available only after the full occurrence transformation succeeds. */
        public List<PrimitiveStep> primitiveExpansion() { return primitiveExpansion; }
        public boolean generated() { return status == Status.GENERATED; }
    }

    /** One ordered proof stage issued as part of a completed specialized result. */
    public record PrimitiveStep(String stageId, String evidenceHash) {
        public PrimitiveStep {
            if (stageId == null || stageId.isBlank() || evidenceHash == null
                    || !evidenceHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException("specialized primitive evidence is invalid");
            }
        }
    }

    private static final class Work implements PolynomialWorkSink {
        private final PolynomialWorkAuthority authority;
        private final Map<String, Long> stages = new LinkedHashMap<>();

        private Work(PolynomialWorkAuthority authority) { this.authority = authority; }

        @Override
        public void consume(String stage, long units) {
            consume(new PolynomialWorkLedger(Map.of(stage, units)));
        }

        private void consume(PolynomialWorkLedger work) {
            authority.consume(work);
            retain(work);
        }

        private void retain(PolynomialWorkLedger work) {
            work.stages().forEach((stage, units) -> stages.merge(stage, units, Math::addExact));
        }

        private PolynomialWorkLedger ledger() { return new PolynomialWorkLedger(stages); }
    }

    private static Status projectionStatus(ExactParsedSubtermProjector.Status status) {
        return switch (status) {
            case POSITION_NOT_PRESENT -> Status.POSITION_NOT_PRESENT;
            case POSITION_STALE -> Status.POSITION_STALE;
            case UNSUPPORTED -> Status.UNSUPPORTED_SEMANTIC_VIEW;
            case BUDGET_INCONCLUSIVE -> Status.BUDGET_INCONCLUSIVE;
            case TECHNICAL_FAILURE -> Status.TECHNICAL_FAILURE;
            case PROJECTED -> throw new IllegalArgumentException("successful projection has no failure status");
        };
    }

    private static Status semanticStatus(PolynomialSemanticView.Status status) {
        return switch (status) {
            case PARSE_ERROR, UNSUPPORTED -> Status.UNSUPPORTED_SEMANTIC_VIEW;
            case BUDGET_EXCEEDED -> Status.BUDGET_INCONCLUSIVE;
            case SUPPORTED -> throw new IllegalArgumentException("supported semantic view has no failure status");
        };
    }

    private static Status reportStatus(FactorizationVerifier.Status status) {
        return switch (status) {
            case COMPLETE_FACTORIZATION, PARTIAL_FACTORIZATION -> Status.GENERATED;
            case IRREDUCIBLE -> Status.IRREDUCIBLE;
            case NO_FACTORIZATION_FOUND -> Status.NO_FACTORIZATION_FOUND;
            case UNSUPPORTED_DOMAIN, UNSUPPORTED_REQUEST -> Status.UNSUPPORTED_FACTORIZATION_REQUEST;
            case BUDGET_INCONCLUSIVE -> Status.BUDGET_INCONCLUSIVE;
            case TECHNICAL_FAILURE -> Status.TECHNICAL_FAILURE;
        };
    }

    private static void append(StringBuilder target, String value) {
        target.append('|').append(value.length()).append(':').append(value);
    }

    private static String exactReparseMaterial(ExactParsedTerm parsed) {
        StringBuilder result = new StringBuilder(PIPELINE_ID + "/exact-reparse");
        append(result, parsed.source());
        append(result, parsed.rootSourceRange().canonicalMaterial());
        parsed.literals().forEach(literal -> {
            append(result, Integer.toString(literal.startInclusive()));
            append(result, Integer.toString(literal.endExclusive()));
            append(result, literal.sourceLexeme());
            append(result, literal.evidence().certificateHash());
        });
        return result.toString();
    }

    private static String sha256(String material) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
