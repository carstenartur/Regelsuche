package de.regelsuche.benchmarks;

import de.regelsuche.benchmarks.ComparativeBenchmark.Case;
import de.regelsuche.benchmarks.ComparativeBenchmark.Configuration;
import de.regelsuche.benchmarks.ComparativeBenchmark.Disposition;
import de.regelsuche.benchmarks.ComparativeBenchmark.EvidenceMetrics;
import de.regelsuche.benchmarks.ComparativeBenchmark.ObservedVerdict;
import de.regelsuche.benchmarks.ComparativeBenchmark.ResourceMetrics;
import de.regelsuche.benchmarks.ComparativeBenchmark.Result;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.SearchSystem;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.SimplificationSystem;
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
import java.util.Comparator;
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

    /**
     * Runs one target-free simplification competitor on one case.
     *
     * <p>The competitor receives the input expression, never the pinned
     * reference form. The reference form is used afterwards by the shared
     * canonical surface judge. Internal search paths additionally retain their
     * emitted side conditions, which must be discharged by the case contract.</p>
     */
    Result runSimplification(
        SimplificationSystem system,
        Configuration configuration,
        Case benchmarkCase
    ) {
        String referenceHash =
            canonicalizer.stableHash(benchmarkCase.targetExpression());
        SimplificationAssumptionContract contract =
            SimplificationAssumptionContract.forCase(benchmarkCase);
        if (system.externalSimplifier() != null) {
            return runExternalSimplification(
                system, configuration, benchmarkCase, referenceHash, contract);
        }
        return runInternalSimplification(
            system, configuration, benchmarkCase, referenceHash, contract);
    }

    private Result runInternalSimplification(
        SimplificationSystem system,
        Configuration configuration,
        Case benchmarkCase,
        String referenceHash,
        SimplificationAssumptionContract contract
    ) {
        CountingEngine engine = new CountingEngine(
            new AstRewriteTransformationEngine());
        SearchProblem problem = new SearchProblem(
            benchmarkCase.inputExpression(),
            engine,
            scorer,
            canonicalizer,
            ComparativeBenchmarkCatalog.SEARCH_BUDGET)
            .withoutTarget();
        List<SearchState> states = system.strategy().search(problem);
        List<SearchState> matching = states.stream()
            .filter(state -> canonicalHash(state.expression())
                .equals(referenceHash))
            .sorted(Comparator.comparingInt(SearchState::depth))
            .toList();
        SearchState reached = matching.stream()
            .filter(state -> contract.discharges(state.assumptions()))
            .findFirst()
            .orElse(null);
        List<String> undischarged = reached != null || matching.isEmpty()
            ? List.of()
            : contract.undischarged(matching.getFirst().assumptions());
        ObservedVerdict verdict = reached == null
            ? ObservedVerdict.UNKNOWN
            : ObservedVerdict.TARGET_REACHED;
        ResourceMetrics resources = new ResourceMetrics(
            1,
            1,
            0,
            0,
            states.size(),
            engine.generatedTransformations(),
            reached == null ? -1 : reached.depth(),
            engine.invocations(),
            1,
            1);
        EvidenceMetrics evidence = new EvidenceMetrics(
            verdict == ObservedVerdict.TARGET_REACHED
                ? "REFERENCE_FORM_REACHED"
                : undischarged.isEmpty()
                    ? "REFERENCE_FORM_NOT_REACHED"
                    : "ASSUMPTION_NOT_DISCHARGED",
            "NOT_REQUESTED",
            "NOT_REQUESTED",
            "",
            undischarged.isEmpty()
                ? List.of()
                : List.of("ASSUMPTION_NOT_DISCHARGED"));
        String traceHash = SolverIr.sha256(
            "simplification/v1"
                + "\nsystem=" + system.id()
                + "\ncase=" + benchmarkCase.contentHash()
                + "\nassumptionContract=" + contract.contractHash()
                + "\nstates=" + states.stream()
                    .map(SearchState::expression).toList()
                + "\nreachedPath="
                    + (reached == null ? List.of() : reached.path()));
        return Result.create(
            configuration,
            benchmarkCase,
            Disposition.EXECUTED,
            verdict,
            resources,
            evidence,
            Map.of(
                "referenceFormReached", reached == null ? 0L : 1L,
                "appliedRewriteSteps",
                    reached == null ? 0L : (long) reached.depth(),
                "declaredAssumptions",
                    (long) contract.declaredAssumptions().size(),
                "undischargedAssumptions", (long) undischarged.size(),
                "engineInvocations", (long) engine.invocations()),
            List.of(traceHash));
    }

    private Result runExternalSimplification(
        SimplificationSystem system,
        Configuration configuration,
        Case benchmarkCase,
        String referenceHash,
        SimplificationAssumptionContract contract
    ) {
        ExternalSymPySimplificationBaseline.Simplification simplification =
            system.externalSimplifier().simplify(
                benchmarkCase.inputExpression(), contract);
        boolean produced = simplification.outcome()
            == ExternalSymPySimplificationBaseline.Outcome.PRODUCED;
        boolean matched = produced
            && referenceHash.equals(
                canonicalHash(simplification.producedExpression()));
        Disposition disposition = switch (simplification.outcome()) {
            case PRODUCED -> Disposition.EXECUTED;
            case UNAVAILABLE -> Disposition.FILTERED_UNSUPPORTED;
            case TIMEOUT -> Disposition.TIMED_OUT;
            case ERROR -> Disposition.FAILED;
        };
        ObservedVerdict verdict = switch (simplification.outcome()) {
            case PRODUCED -> matched
                ? ObservedVerdict.TARGET_REACHED : ObservedVerdict.UNKNOWN;
            case UNAVAILABLE -> ObservedVerdict.UNSUPPORTED;
            case TIMEOUT -> ObservedVerdict.TIMEOUT;
            case ERROR -> ObservedVerdict.ERROR;
        };
        int executedWork = produced ? 1 : 0;
        int skippedWork = disposition == Disposition.FILTERED_UNSUPPORTED
            ? 1 : 0;
        ResourceMetrics resources = new ResourceMetrics(
            1,
            executedWork,
            skippedWork,
            1 - executedWork - skippedWork,
            0,
            0,
            -1,
            executedWork,
            1,
            executedWork);
        EvidenceMetrics evidence = new EvidenceMetrics(
            matched
                ? "REFERENCE_FORM_REACHED"
                : produced ? "REFERENCE_FORM_NOT_REACHED"
                    : simplification.outcome().name(),
            "NOT_REQUESTED",
            "NOT_REQUESTED",
            "",
            simplification.issues());
        String traceHash = SolverIr.sha256(
            "simplification/v1"
                + "\nsystem=" + system.id()
                + "\ncase=" + benchmarkCase.contentHash()
                + "\nassumptionContract=" + contract.contractHash()
                + "\noutcome=" + simplification.outcome().name()
                + "\nproduced=" + simplification.producedExpression());
        return Result.create(
            configuration,
            benchmarkCase,
            disposition,
            verdict,
            resources,
            evidence,
            Map.of(
                "referenceFormReached", matched ? 1L : 0L,
                "declaredAssumptions",
                    (long) contract.declaredAssumptions().size(),
                "producedExpression", produced ? 1L : 0L),
            List.of(traceHash));
    }

    private String canonicalHash(String expression) {
        try {
            return canonicalizer.stableHash(expression);
        } catch (RuntimeException exception) {
            // An unparsable competitor output is a miss, never a crash.
            return "UNPARSABLE:" + normalize(expression);
        }
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
