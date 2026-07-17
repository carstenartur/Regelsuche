package de.regelsuche.benchmarks;

import de.regelsuche.benchmarks.ComparativeBenchmark.Case;
import de.regelsuche.benchmarks.ComparativeBenchmark.Configuration;
import de.regelsuche.benchmarks.ComparativeBenchmark.Disposition;
import de.regelsuche.benchmarks.ComparativeBenchmark.EvidenceMetrics;
import de.regelsuche.benchmarks.ComparativeBenchmark.ObservedVerdict;
import de.regelsuche.benchmarks.ComparativeBenchmark.ResourceMetrics;
import de.regelsuche.benchmarks.ComparativeBenchmark.Result;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.SearchSystem;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.ValidationSystem;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SourceProvenance;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.solver.ir.SolverObligationFactory;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executes one case/system pair while keeping runtime outside canonical metrics. */
final class ComparativeBenchmarkExecutor {
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final SolverObligationFactory obligations =
        new SolverObligationFactory();

    Result runSearch(
        SearchSystem system,
        Configuration configuration,
        Case benchmarkCase
    ) {
        CountingEngine engine = new CountingEngine(
            new AstRewriteTransformationEngine());
        SearchProblem problem = new SearchProblem(
            benchmarkCase.inputExpression(),
            engine,
            scorer,
            canonicalizer,
            ComparativeBenchmarkCatalog.SEARCH_BUDGET)
            .withTarget(SearchProblem.SearchTarget
                .syntaxExact(benchmarkCase.targetExpression()));
        List<SearchState> states = system.strategy().search(problem);
        SearchState reached = states.stream()
            .filter(state -> normalize(state.expression()).equals(
                normalize(benchmarkCase.targetExpression())))
            .findFirst()
            .orElse(null);
        ObservedVerdict verdict = reached == null
            ? ObservedVerdict.UNKNOWN
            : ObservedVerdict.TARGET_REACHED;
        int pathLength = reached == null ? -1 : reached.depth();
        ResourceMetrics resources = new ResourceMetrics(
            1,
            1,
            0,
            0,
            states.size(),
            engine.generatedTransformations(),
            pathLength,
            engine.invocations(),
            1,
            1);
        EvidenceMetrics evidence = new EvidenceMetrics(
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            "NOT_REQUESTED",
            "",
            List.of());
        String traceHash = SolverIr.sha256(
            "system=" + system.id()
                + "\ncase=" + benchmarkCase.contentHash()
                + "\nstates=" + states.stream()
                    .map(SearchState::expression).toList()
                + "\nreachedPath="
                    + (reached == null ? List.of() : reached.path())
                + "\nruleIds="
                    + (reached == null ? List.of() : reached.appliedRuleIds()));
        return Result.create(
            configuration,
            benchmarkCase,
            Disposition.EXECUTED,
            verdict,
            resources,
            evidence,
            Map.of(
                "targetReached", reached == null ? 0L : 1L,
                "reachedDepth", reached == null ? 0L : (long) reached.depth(),
                "engineInvocations", (long) engine.invocations()),
            List.of(traceHash));
    }

    Result runValidation(
        ValidationSystem system,
        Configuration configuration,
        Case benchmarkCase
    ) {
        if (!system.available()) {
            return Result.create(
                configuration,
                benchmarkCase,
                Disposition.FILTERED_UNSUPPORTED,
                ObservedVerdict.UNSUPPORTED,
                new ResourceMetrics(
                    1, 0, 1, 0, 0, 0, -1, 0, 1, 0),
                new EvidenceMetrics(
                    "UNSUPPORTED",
                    "NOT_EVALUATED",
                    "NOT_REQUESTED",
                    "",
                    List.of("BACKEND_UNAVAILABLE")),
                Map.of("backendAvailable", 0L),
                List.of());
        }

        var obligation = obligations.equality(
            "comparative-" + benchmarkCase.id(),
            benchmarkCase.inputExpression(),
            benchmarkCase.targetExpression(),
            benchmarkCase.assumptions(),
            RequestedEvidence.SYMBOLIC_CERTIFICATE,
            new SourceProvenance(
                "comparative-benchmark",
                benchmarkCase.id(),
                benchmarkCase.contentHash()));
        SolverExecution execution = system.backend().execute(obligation);
        ResultStatus status = execution.result().status();
        Disposition disposition = disposition(status);
        ObservedVerdict verdict = verdict(status);
        int executedWork = disposition == Disposition.FILTERED_UNSUPPORTED
            ? 0 : 1;
        int skippedWork = disposition == Disposition.FILTERED_UNSUPPORTED
            ? 1 : 0;
        int completedMandatory = status == ResultStatus.CONFIRMED
                || status == ResultStatus.REFUTED
            ? 1 : 0;
        ResourceMetrics resources = new ResourceMetrics(
            1,
            executedWork,
            skippedWork,
            0,
            0,
            0,
            -1,
            executedWork,
            1,
            completedMandatory);
        String certificate = execution.result().certificateHash();
        EvidenceMetrics evidence = new EvidenceMetrics(
            status.name(),
            counterexampleStatus(execution),
            proofStatus(execution),
            certificate,
            status == ResultStatus.UNSUPPORTED
                ? execution.result().translationIssues()
                : List.of());
        return Result.create(
            configuration,
            benchmarkCase,
            disposition,
            verdict,
            resources,
            evidence,
            Map.of(
                "backendAvailable", 1L,
                "certificatePresent", certificate.isBlank() ? 0L : 1L,
                "counterexampleEntries",
                    (long) execution.result().counterexample().size(),
                "translationLossless",
                    execution.translation().status()
                        == TranslationStatus.LOSSLESS ? 1L : 0L),
            List.of(
                obligation.contentHash(),
                execution.translation().contentHash(),
                execution.result().contentHash(),
                execution.contentHash()));
    }

    private static Disposition disposition(ResultStatus status) {
        return switch (status) {
            case CONFIRMED, REFUTED, UNKNOWN -> Disposition.EXECUTED;
            case UNSUPPORTED -> Disposition.FILTERED_UNSUPPORTED;
            case TIMEOUT -> Disposition.TIMED_OUT;
            case ERROR -> Disposition.FAILED;
        };
    }

    private static ObservedVerdict verdict(ResultStatus status) {
        return switch (status) {
            case CONFIRMED -> ObservedVerdict.CONFIRMED;
            case REFUTED -> ObservedVerdict.REFUTED;
            case UNKNOWN -> ObservedVerdict.UNKNOWN;
            case UNSUPPORTED -> ObservedVerdict.UNSUPPORTED;
            case TIMEOUT -> ObservedVerdict.TIMEOUT;
            case ERROR -> ObservedVerdict.ERROR;
        };
    }

    private static String counterexampleStatus(SolverExecution execution) {
        if (execution.result().status() != ResultStatus.REFUTED) {
            return "NOT_REQUESTED";
        }
        return execution.result().counterexample().isEmpty()
            ? "NON_EQUIVALENCE_CERTIFICATE_RETAINED"
            : "COUNTERMODEL_RETAINED";
    }

    private static String proofStatus(SolverExecution execution) {
        if (execution.result().certificateHash().isBlank()) {
            return "NOT_REQUESTED";
        }
        return "z3-smt-proof".equals(execution.result().backendId())
                && execution.result().status() == ResultStatus.CONFIRMED
            ? "FORMAL_CERTIFICATE_RETAINED"
            : "VALIDATION_CERTIFICATE_RETAINED";
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "value")
            .trim()
            .replaceAll("\\s+", " ");
    }

    private static final class CountingEngine
            implements TransformationEngine {
        private final TransformationEngine delegate;
        private int invocations;
        private int generatedTransformations;

        private CountingEngine(TransformationEngine delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public List<Transformation> transform(String expression) {
            List<Transformation> result = delegate.transform(expression);
            invocations++;
            generatedTransformations += result.size();
            return result;
        }

        private int invocations() {
            return invocations;
        }

        private int generatedTransformations() {
            return generatedTransformations;
        }
    }
}
