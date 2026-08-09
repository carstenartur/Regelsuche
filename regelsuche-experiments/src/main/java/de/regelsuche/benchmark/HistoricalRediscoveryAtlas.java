package de.regelsuche.benchmark;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static de.regelsuche.ast.BinaryOperator.MUL;
import static de.regelsuche.ast.BinaryOperator.POW;

import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Case;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Relation;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Role;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.TargetRelation;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalMetrics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.strategy.StructuralDiversitySearchStrategy;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.AstRewriteTransformationEngines;
import de.regelsuche.transform.BoundedRewriteReachabilityOracle;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Layered diagnostic atlas for historical identities and search-policy controls.
 *
 * <p>Target-aware reachability and guidance are retained separately from the
 * target-blind scalar and structural-diversity searches. Every primary diagnosis
 * is therefore derived from explicit evidence rather than inferred from one
 * aggregate success flag.</p>
 */
public final class HistoricalRediscoveryAtlas {
    public static final String SCHEMA =
        "regelsuche.historical-rediscovery-atlas/v1";

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final SymPyEquivalenceService equivalence =
        new SymPyEquivalenceService();

    public AtlasReport run(Corpus corpus) {
        Objects.requireNonNull(corpus, "corpus");
        List<CaseResult> results = corpus.cases().stream()
            .sorted(Comparator.comparing(Case::id))
            .map(this::runCase)
            .toList();
        return new AtlasReport(
            SCHEMA,
            corpus.schema(),
            corpus.contentSha256(),
            corpus.inventoryRevision(),
            corpus.claimBoundary(),
            results,
            directionality(results),
            Assessment.derive(results)
        );
    }

    public WrittenArtifacts write(Path directory, AtlasReport report) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(report, "report");
        try {
            Files.createDirectories(directory);
            Path json = directory.resolve("historical-rediscovery-atlas.json");
            Path markdown = directory.resolve("historical-rediscovery-atlas.md");
            Files.writeString(json, report.toJson(), StandardCharsets.UTF_8);
            Files.writeString(markdown, report.toMarkdown(), StandardCharsets.UTF_8);
            return new WrittenArtifacts(json, markdown);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private CaseResult runCase(Case benchmarkCase) {
        RepresentationEvidence representation = represent(benchmarkCase);
        if (!representation.supported()) {
            String detail = "representation unsupported";
            return new CaseResult(
                benchmarkCase,
                representation,
                EquivalenceEvidence.notEvaluated(),
                EngineEvidence.notEvaluated(
                    EngineProfile.PRODUCTION_PRIMITIVES, detail),
                EngineEvidence.notEvaluated(
                    EngineProfile.GENERIC_HYPOTHESIS_BRIDGE, detail),
                EngineEvidence.notEvaluated(
                    EngineProfile.CURATED_RECOGNITION_CONTROL, detail),
                PrimaryStatus.REPRESENTATION_UNSUPPORTED
            );
        }

        EquivalenceEvidence equivalenceEvidence = new EquivalenceEvidence(
            true,
            equivalence.areEquivalent(
                representation.formattedSource(),
                representation.formattedTarget()),
            equivalence.evidence(
                representation.formattedSource(),
                representation.formattedTarget())
        );
        EngineEvidence production = runEngine(
            benchmarkCase,
            representation,
            EngineProfile.PRODUCTION_PRIMITIVES,
            () -> productionEngine(benchmarkCase),
            true
        );
        EngineEvidence genericBridge = benchmarkCase.diagnosticPurpose()
                .equals("GENERIC_HYPOTHESIS_BRIDGE")
            ? runEngine(
                benchmarkCase,
                representation,
                EngineProfile.GENERIC_HYPOTHESIS_BRIDGE,
                () -> hypothesisEngine(benchmarkCase),
                true)
            : EngineEvidence.notApplicable(
                EngineProfile.GENERIC_HYPOTHESIS_BRIDGE);
        EngineEvidence curatedControl = benchmarkCase.family()
                .equals("COMPLETING_THE_SQUARE")
            ? runEngine(
                benchmarkCase,
                representation,
                EngineProfile.CURATED_RECOGNITION_CONTROL,
                () -> curatedControlEngine(benchmarkCase),
                false)
            : EngineEvidence.notApplicable(
                EngineProfile.CURATED_RECOGNITION_CONTROL);

        return new CaseResult(
            benchmarkCase,
            representation,
            equivalenceEvidence,
            production,
            genericBridge,
            curatedControl,
            classify(
                benchmarkCase,
                equivalenceEvidence,
                production,
                genericBridge,
                curatedControl)
        );
    }

    private RepresentationEvidence represent(Case benchmarkCase) {
        try {
            return new RepresentationEvidence(
                true,
                format(benchmarkCase.source()),
                format(benchmarkCase.target()),
                ""
            );
        } catch (IllegalArgumentException exception) {
            return new RepresentationEvidence(
                false,
                "",
                "",
                safeMessage(exception)
            );
        }
    }

    private EngineEvidence runEngine(
        Case benchmarkCase,
        RepresentationEvidence representation,
        EngineProfile profile,
        Supplier<TransformationEngine> engineFactory,
        boolean runSearchControls
    ) {
        OracleEvidence oracle = runOracle(
            benchmarkCase,
            representation,
            engineFactory.get());
        if (!runSearchControls) {
            SearchEvidence omitted = SearchEvidence.notEvaluated(
                "curated oracle control only");
            return new EngineEvidence(
                profile,
                EvidenceExecution.EXECUTED,
                "",
                oracle,
                omitted,
                omitted,
                omitted
            );
        }
        return new EngineEvidence(
            profile,
            EvidenceExecution.EXECUTED,
            "",
            oracle,
            runScalarSearch(
                benchmarkCase,
                representation,
                engineFactory.get()),
            runGuidedSearch(
                benchmarkCase,
                representation,
                engineFactory.get()),
            runDiversitySearch(
                benchmarkCase,
                representation,
                engineFactory.get())
        );
    }

    private OracleEvidence runOracle(
        Case benchmarkCase,
        RepresentationEvidence representation,
        TransformationEngine engine
    ) {
        BoundedRewriteReachabilityOracle.Result result =
            new BoundedRewriteReachabilityOracle(engine, this::format)
                .search(
                    representation.formattedSource(),
                    representation.formattedTarget(),
                    new BoundedRewriteReachabilityOracle.Budget(
                        benchmarkCase.oracleMaxDepth(),
                        benchmarkCase.oracleMaxVisitedStates()));
        return new OracleEvidence(
            EvidenceExecution.EXECUTED,
            result.status().name(),
            result.witness().stream()
                .map(BoundedRewriteReachabilityOracle.Step::expressionAfter)
                .toList(),
            result.witness().stream()
                .map(BoundedRewriteReachabilityOracle.Step::rule)
                .toList(),
            result.witness().stream()
                .mapToInt(BoundedRewriteReachabilityOracle.Step::primitiveStepCount)
                .sum(),
            result.visitedStates(),
            result.generatedTransitions(),
            result.maximumDepthReached(),
            result.depthLimitReached(),
            result.stateLimitReached(),
            ""
        );
    }

    private SearchEvidence runScalarSearch(
        Case benchmarkCase,
        RepresentationEvidence representation,
        TransformationEngine engine
    ) {
        CountingEngine counting = new CountingEngine(engine);
        GoalSearchResult result = new BestFirstSearchStrategy()
            .searchWithDiagnostics(searchProblem(
                benchmarkCase,
                representation.formattedSource(),
                counting));
        Optional<SearchState> match = findMatch(
            benchmarkCase,
            representation.formattedTarget(),
            result.states());
        return SearchEvidence.fromGoalResult(
            "SCALAR_BEST_FIRST_TARGET_BLIND",
            match,
            result,
            counting
        );
    }

    private SearchEvidence runGuidedSearch(
        Case benchmarkCase,
        RepresentationEvidence representation,
        TransformationEngine engine
    ) {
        CountingEngine counting = new CountingEngine(engine);
        GoalSearchResult result = new BestFirstSearchStrategy()
            .searchWithDiagnostics(searchProblem(
                benchmarkCase,
                representation.formattedSource(),
                counting).withTarget(searchTarget(
                    benchmarkCase,
                    representation.formattedTarget())));
        return SearchEvidence.fromGoalResult(
            "TARGET_GUIDED_BEST_FIRST_DIAGNOSTIC",
            Optional.ofNullable(result.reachedState()),
            result,
            counting
        );
    }

    private SearchEvidence runDiversitySearch(
        Case benchmarkCase,
        RepresentationEvidence representation,
        TransformationEngine engine
    ) {
        CountingEngine counting = new CountingEngine(engine);
        List<SearchState> states = new StructuralDiversitySearchStrategy()
            .search(searchProblem(
                benchmarkCase,
                representation.formattedSource(),
                counting));
        return SearchEvidence.fromStates(
            "STRUCTURAL_DIVERSITY_TARGET_BLIND",
            findMatch(
                benchmarkCase,
                representation.formattedTarget(),
                states),
            states,
            counting
        );
    }

    private SearchProblem searchProblem(
        Case benchmarkCase,
        String source,
        TransformationEngine engine
    ) {
        return new SearchProblem(
            source,
            engine,
            scorer,
            canonicalizer,
            new SearchHeuristic(
                benchmarkCase.searchMaxDepth(),
                benchmarkCase.searchMaxVisitedStates(),
                1,
                benchmarkCase.maxExpandingSteps(),
                benchmarkCase.maxCandidatesPerState(),
                benchmarkCase.beamWidth())
        );
    }

    private SearchTarget searchTarget(Case benchmarkCase, String target) {
        return benchmarkCase.targetRelation() == TargetRelation.SYNTAX_EXACT
            ? SearchTarget.syntaxExact(target)
            : SearchTarget.valueEquivalent(target);
    }

    private Optional<SearchState> findMatch(
        Case benchmarkCase,
        String target,
        List<SearchState> states
    ) {
        return states.stream()
            .filter(state -> matches(
                benchmarkCase,
                state.expression(),
                target))
            .min(Comparator
                .comparingInt(SearchState::depth)
                .thenComparing(SearchState::expression)
                .thenComparing(state ->
                    String.join("->", state.appliedRuleIds())));
    }

    private boolean matches(Case benchmarkCase, String expression, String target) {
        if (benchmarkCase.targetRelation() == TargetRelation.SYNTAX_EXACT) {
            try {
                return format(expression).equals(target);
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
        return equivalence.areEquivalent(expression, target);
    }

    private TransformationEngine productionEngine(Case benchmarkCase) {
        return AstRewriteTransformationEngines.production(
            AstRewriteTransformationEngine.defaultRules(),
            128,
            Math.max(200, benchmarkCase.maxCandidatesPerState() * 2)
        );
    }

    private TransformationEngine hypothesisEngine(Case benchmarkCase) {
        return new HypothesisTransformationEngine(
            productionEngine(benchmarkCase),
            List.of(new DifferenceOfSquaresPreparationOperator(8)),
            16
        );
    }

    private TransformationEngine curatedControlEngine(Case benchmarkCase) {
        List<RewriteRule> rules = new ArrayList<>(
            AstRewriteTransformationEngine.defaultRules());
        rules.add(completeSquareControl());
        return AstRewriteTransformationEngines.reference(
            rules,
            128,
            Math.max(200, benchmarkCase.maxCandidatesPerState() * 2)
        );
    }

    private PatternRewriteRule completeSquareControl() {
        PatternExpr x = PatternExpr.var("X");
        PatternExpr a = PatternExpr.var("A");
        PatternExpr source = PatternExpr.op(
            ADD,
            PatternExpr.op(
                ADD,
                PatternExpr.op(POW, x, PatternExpr.num(2)),
                PatternExpr.op(
                    MUL,
                    PatternExpr.op(MUL, PatternExpr.num(2), x),
                    a)
            ),
            PatternExpr.op(POW, a, PatternExpr.num(2))
        );
        PatternExpr target = PatternExpr.op(
            POW,
            PatternExpr.op(ADD, x, a),
            PatternExpr.num(2)
        );
        return new PatternRewriteRule(
            "historical-control-complete-square",
            source,
            target,
            RecognitionProfile.algebraicAc()
        );
    }

    private PrimaryStatus classify(
        Case benchmarkCase,
        EquivalenceEvidence equivalenceEvidence,
        EngineEvidence production,
        EngineEvidence genericBridge,
        EngineEvidence curatedControl
    ) {
        if (benchmarkCase.relation() == Relation.NOT_EQUIVALENT) {
            return equivalenceEvidence.equivalent()
                    || anyReached(production, genericBridge, curatedControl)
                ? PrimaryStatus.CORRECTNESS_REGRESSION
                : PrimaryStatus.NEGATIVE_CONTROL_CONFIRMED;
        }
        if (!equivalenceEvidence.equivalent()) {
            return PrimaryStatus.EQUIVALENCE_NOT_CONFIRMED;
        }
        if (production.oracle().reachable()) {
            if (production.scalar().reached()) {
                return PrimaryStatus.REACHABLE_AND_SCALAR_FOUND;
            }
            if (production.diversity().reached()) {
                return PrimaryStatus
                    .REACHABLE_BUT_SCALAR_MISSED_DIVERSITY_FOUND;
            }
            if (production.guided().reached()) {
                return PrimaryStatus
                    .REACHABLE_BUT_SCALAR_MISSED_GUIDANCE_FOUND;
            }
            return PrimaryStatus.REACHABLE_BUT_PRODUCTION_SEARCH_MISSED;
        }
        if (genericBridge.executed() && anyReached(genericBridge)) {
            return PrimaryStatus.GENERIC_BRIDGE_REQUIRED_AND_FOUND;
        }
        if (curatedControl.executed()
                && curatedControl.oracle().reachable()) {
            return PrimaryStatus
                .CURATED_CONTROL_ONLY_MISSING_PRODUCTION_PRIMITIVE;
        }
        if (production.oracle().completeClosureExhausted()) {
            return PrimaryStatus.UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE;
        }
        return PrimaryStatus.BUDGET_INCONCLUSIVE;
    }

    private boolean anyReached(EngineEvidence... evidence) {
        for (EngineEvidence item : evidence) {
            if (item.executed() && anyReached(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean anyReached(EngineEvidence evidence) {
        return evidence.oracle().reachable()
            || evidence.scalar().reached()
            || evidence.guided().reached()
            || evidence.diversity().reached();
    }

    private List<DirectionalityResult> directionality(List<CaseResult> results) {
        Map<String, CaseResult> byId = new LinkedHashMap<>();
        results.forEach(result ->
            byId.put(result.benchmarkCase().id(), result));
        List<DirectionalityResult> pairs = new ArrayList<>();
        addDirectionality(
            pairs,
            byId,
            "binomial-square",
            "complete-square",
            "expand-binomial-square");
        addDirectionality(
            pairs,
            byId,
            "difference-of-squares",
            "difference-of-squares",
            "reverse-difference-of-squares");
        return List.copyOf(pairs);
    }

    private void addDirectionality(
        List<DirectionalityResult> pairs,
        Map<String, CaseResult> byId,
        String id,
        String forwardId,
        String reverseId
    ) {
        CaseResult forward = byId.get(forwardId);
        CaseResult reverse = byId.get(reverseId);
        if (forward == null || reverse == null) {
            return;
        }
        boolean forwardReachable = forward.production().oracle().reachable();
        boolean reverseReachable = reverse.production().oracle().reachable();
        boolean inconclusive = forward.production().oracle().inconclusive()
            || reverse.production().oracle().inconclusive();
        DirectionalityStatus status;
        if (forwardReachable && reverseReachable) {
            status = DirectionalityStatus.BIDIRECTIONAL;
        } else if (inconclusive) {
            status = DirectionalityStatus.INCONCLUSIVE;
        } else if (forwardReachable) {
            status = DirectionalityStatus.FORWARD_ONLY;
        } else if (reverseReachable) {
            status = DirectionalityStatus.REVERSE_ONLY;
        } else {
            status = DirectionalityStatus.NEITHER_REACHABLE;
        }
        pairs.add(new DirectionalityResult(
            id,
            forwardId,
            reverseId,
            status,
            forwardReachable,
            reverseReachable
        ));
    }

    private String format(String expression) {
        return ExpressionFormatter.format(parser.parseTerm(expression));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
            ? throwable.getClass().getSimpleName()
            : message.replace('\n', ' ').replace('\r', ' ');
    }

    public enum EngineProfile {
        PRODUCTION_PRIMITIVES,
        GENERIC_HYPOTHESIS_BRIDGE,
        CURATED_RECOGNITION_CONTROL
    }

    public enum EvidenceExecution {
        EXECUTED,
        NOT_APPLICABLE,
        NOT_EVALUATED
    }

    public enum PrimaryStatus {
        REPRESENTATION_UNSUPPORTED,
        EQUIVALENCE_NOT_CONFIRMED,
        REACHABLE_AND_SCALAR_FOUND,
        REACHABLE_BUT_SCALAR_MISSED_DIVERSITY_FOUND,
        REACHABLE_BUT_SCALAR_MISSED_GUIDANCE_FOUND,
        REACHABLE_BUT_PRODUCTION_SEARCH_MISSED,
        GENERIC_BRIDGE_REQUIRED_AND_FOUND,
        CURATED_CONTROL_ONLY_MISSING_PRODUCTION_PRIMITIVE,
        UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE,
        BUDGET_INCONCLUSIVE,
        NEGATIVE_CONTROL_CONFIRMED,
        CORRECTNESS_REGRESSION
    }

    public enum DirectionalityStatus {
        BIDIRECTIONAL,
        FORWARD_ONLY,
        REVERSE_ONLY,
        NEITHER_REACHABLE,
        INCONCLUSIVE
    }

    public enum AssessmentDecision {
        USEFUL_DIAGNOSTIC_STEP,
        USEFUL_BUT_INCOMPLETE,
        INSUFFICIENT_SIGNAL
    }

    public record RepresentationEvidence(
        boolean supported,
        String formattedSource,
        String formattedTarget,
        String detail
    ) {
        public RepresentationEvidence {
            formattedSource = formattedSource == null ? "" : formattedSource;
            formattedTarget = formattedTarget == null ? "" : formattedTarget;
            detail = detail == null ? "" : detail;
        }
    }

    public record EquivalenceEvidence(
        boolean evaluated,
        boolean equivalent,
        String detail
    ) {
        public EquivalenceEvidence {
            detail = detail == null ? "" : detail;
        }

        private static EquivalenceEvidence notEvaluated() {
            return new EquivalenceEvidence(false, false, "not evaluated");
        }
    }

    public record OracleEvidence(
        EvidenceExecution execution,
        String status,
        List<String> witnessExpressions,
        List<String> witnessRuleIds,
        int primitiveSteps,
        int visitedStates,
        long generatedTransitions,
        int maximumDepthReached,
        boolean depthLimitReached,
        boolean stateLimitReached,
        String detail
    ) {
        public OracleEvidence {
            Objects.requireNonNull(execution, "execution");
            status = status == null ? "" : status;
            witnessExpressions = List.copyOf(witnessExpressions);
            witnessRuleIds = List.copyOf(witnessRuleIds);
            detail = detail == null ? "" : detail;
        }

        private static OracleEvidence notEvaluated(String detail) {
            return new OracleEvidence(
                EvidenceExecution.NOT_EVALUATED,
                "NOT_EVALUATED",
                List.of(),
                List.of(),
                0,
                0,
                0,
                0,
                false,
                false,
                detail
            );
        }

        private static OracleEvidence notApplicable() {
            return new OracleEvidence(
                EvidenceExecution.NOT_APPLICABLE,
                "NOT_APPLICABLE",
                List.of(),
                List.of(),
                0,
                0,
                0,
                0,
                false,
                false,
                ""
            );
        }

        public boolean reachable() {
            return execution == EvidenceExecution.EXECUTED
                && status.equals("REACHABLE");
        }

        public boolean completeClosureExhausted() {
            return execution == EvidenceExecution.EXECUTED
                && status.equals("UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE");
        }

        public boolean inconclusive() {
            return execution == EvidenceExecution.EXECUTED
                && status.equals("BUDGET_INCONCLUSIVE");
        }
    }

    public record SearchEvidence(
        EvidenceExecution execution,
        String policy,
        boolean reached,
        String terminalStatus,
        int exploredStates,
        int engineCalls,
        long generatedTransformations,
        int depth,
        List<String> path,
        List<String> ruleIds,
        GoalMetrics metrics,
        String detail
    ) {
        public SearchEvidence {
            Objects.requireNonNull(execution, "execution");
            policy = policy == null ? "" : policy;
            terminalStatus = terminalStatus == null ? "" : terminalStatus;
            path = List.copyOf(path);
            ruleIds = List.copyOf(ruleIds);
            detail = detail == null ? "" : detail;
        }

        private static SearchEvidence fromGoalResult(
            String policy,
            Optional<SearchState> match,
            GoalSearchResult result,
            CountingEngine counting
        ) {
            SearchState state = match.orElse(null);
            return new SearchEvidence(
                EvidenceExecution.EXECUTED,
                policy,
                state != null,
                result.status().name(),
                result.states().size(),
                counting.calls(),
                counting.generated(),
                state == null ? -1 : state.depth(),
                state == null ? List.of() : state.path(),
                state == null ? List.of() : state.appliedRuleIds(),
                result.metrics(),
                ""
            );
        }

        private static SearchEvidence fromStates(
            String policy,
            Optional<SearchState> match,
            List<SearchState> states,
            CountingEngine counting
        ) {
            SearchState state = match.orElse(null);
            return new SearchEvidence(
                EvidenceExecution.EXECUTED,
                policy,
                state != null,
                states.isEmpty() ? "NO_STATES" : "COMPLETED_BOUNDED_SEARCH",
                states.size(),
                counting.calls(),
                counting.generated(),
                state == null ? -1 : state.depth(),
                state == null ? List.of() : state.path(),
                state == null ? List.of() : state.appliedRuleIds(),
                null,
                ""
            );
        }

        private static SearchEvidence notEvaluated(String detail) {
            return new SearchEvidence(
                EvidenceExecution.NOT_EVALUATED,
                "",
                false,
                "NOT_EVALUATED",
                0,
                0,
                0,
                -1,
                List.of(),
                List.of(),
                null,
                detail
            );
        }

        private static SearchEvidence notApplicable() {
            return new SearchEvidence(
                EvidenceExecution.NOT_APPLICABLE,
                "",
                false,
                "NOT_APPLICABLE",
                0,
                0,
                0,
                -1,
                List.of(),
                List.of(),
                null,
                ""
            );
        }
    }

    public record EngineEvidence(
        EngineProfile profile,
        EvidenceExecution execution,
        String detail,
        OracleEvidence oracle,
        SearchEvidence scalar,
        SearchEvidence guided,
        SearchEvidence diversity
    ) {
        public EngineEvidence {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(execution, "execution");
            detail = detail == null ? "" : detail;
            Objects.requireNonNull(oracle, "oracle");
            Objects.requireNonNull(scalar, "scalar");
            Objects.requireNonNull(guided, "guided");
            Objects.requireNonNull(diversity, "diversity");
        }

        private static EngineEvidence notApplicable(EngineProfile profile) {
            return new EngineEvidence(
                profile,
                EvidenceExecution.NOT_APPLICABLE,
                "",
                OracleEvidence.notApplicable(),
                SearchEvidence.notApplicable(),
                SearchEvidence.notApplicable(),
                SearchEvidence.notApplicable()
            );
        }

        private static EngineEvidence notEvaluated(
            EngineProfile profile,
            String detail
        ) {
            return new EngineEvidence(
                profile,
                EvidenceExecution.NOT_EVALUATED,
                detail,
                OracleEvidence.notEvaluated(detail),
                SearchEvidence.notEvaluated(detail),
                SearchEvidence.notEvaluated(detail),
                SearchEvidence.notEvaluated(detail)
            );
        }

        public boolean executed() {
            return execution == EvidenceExecution.EXECUTED;
        }
    }

    public record CaseResult(
        Case benchmarkCase,
        RepresentationEvidence representation,
        EquivalenceEvidence equivalence,
        EngineEvidence production,
        EngineEvidence genericBridge,
        EngineEvidence curatedControl,
        PrimaryStatus status
    ) {
        public CaseResult {
            Objects.requireNonNull(benchmarkCase, "benchmarkCase");
            Objects.requireNonNull(representation, "representation");
            Objects.requireNonNull(equivalence, "equivalence");
            Objects.requireNonNull(production, "production");
            Objects.requireNonNull(genericBridge, "genericBridge");
            Objects.requireNonNull(curatedControl, "curatedControl");
            Objects.requireNonNull(status, "status");
        }
    }

    public record DirectionalityResult(
        String id,
        String forwardCaseId,
        String reverseCaseId,
        DirectionalityStatus status,
        boolean forwardReachable,
        boolean reverseReachable
    ) {
    }

    public record Assessment(
        AssessmentDecision decision,
        boolean representationLayerWorks,
        boolean equivalenceLayerDiscriminates,
        boolean productionPositiveControlWorks,
        boolean missingInventoryLayerIdentified,
        boolean searchPolicyDifferenceIdentified,
        boolean genericBridgeDifferenceIdentified,
        boolean negativeControlPassed,
        int distinctPrimaryStatuses,
        Map<PrimaryStatus, Integer> statusCounts,
        List<String> reasons
    ) {
        public Assessment {
            Objects.requireNonNull(decision, "decision");
            statusCounts = Map.copyOf(statusCounts);
            reasons = List.copyOf(reasons);
        }

        private static Assessment derive(List<CaseResult> results) {
            Map<PrimaryStatus, Integer> counts =
                new EnumMap<>(PrimaryStatus.class);
            results.forEach(result ->
                counts.merge(result.status(), 1, Integer::sum));
            boolean representation = results.stream()
                .allMatch(result -> result.representation().supported());
            boolean equivalence = results.stream()
                .filter(result ->
                    result.benchmarkCase().role() == Role.NEGATIVE_CONTROL)
                .allMatch(result -> result.equivalence().evaluated()
                    && !result.equivalence().equivalent())
                && results.stream()
                    .filter(result -> result.benchmarkCase().relation()
                        == Relation.EQUIVALENT)
                    .anyMatch(result -> result.equivalence().equivalent());
            boolean productionPositive = results.stream()
                .anyMatch(result -> result.production().oracle().reachable());
            boolean missingInventory = counts.getOrDefault(
                PrimaryStatus
                    .CURATED_CONTROL_ONLY_MISSING_PRODUCTION_PRIMITIVE,
                0) > 0;
            boolean searchPolicy = counts.getOrDefault(
                PrimaryStatus.REACHABLE_BUT_SCALAR_MISSED_DIVERSITY_FOUND,
                0) > 0
                || counts.getOrDefault(
                    PrimaryStatus
                        .REACHABLE_BUT_SCALAR_MISSED_GUIDANCE_FOUND,
                    0) > 0;
            boolean genericBridge = counts.getOrDefault(
                PrimaryStatus.GENERIC_BRIDGE_REQUIRED_AND_FOUND,
                0) > 0;
            boolean negative = counts.getOrDefault(
                PrimaryStatus.NEGATIVE_CONTROL_CONFIRMED,
                0) > 0
                && counts.getOrDefault(
                    PrimaryStatus.CORRECTNESS_REGRESSION,
                    0) == 0;
            int distinct = counts.size();
            int strongSignals = (missingInventory ? 1 : 0)
                + (searchPolicy ? 1 : 0)
                + (genericBridge ? 1 : 0);
            AssessmentDecision decision;
            if (representation && equivalence && productionPositive && negative
                    && strongSignals >= 2 && distinct >= 4) {
                decision = AssessmentDecision.USEFUL_DIAGNOSTIC_STEP;
            } else if (representation && equivalence && productionPositive
                    && negative && distinct >= 3) {
                decision = AssessmentDecision.USEFUL_BUT_INCOMPLETE;
            } else {
                decision = AssessmentDecision.INSUFFICIENT_SIGNAL;
            }
            List<String> reasons = new ArrayList<>();
            if (productionPositive) {
                reasons.add(
                    "production primitives recover at least one frozen identity");
            }
            if (missingInventory) {
                reasons.add(
                    "curated control separates a missing production primitive from representation failure");
            }
            if (searchPolicy) {
                reasons.add(
                    "matched-work search policies produce different reachability outcomes");
            }
            if (genericBridge) {
                reasons.add(
                    "a generic hypothesis bridge moves the reachable capability frontier");
            }
            if (negative) {
                reasons.add("the false near-miss remains rejected");
            }
            return new Assessment(
                decision,
                representation,
                equivalence,
                productionPositive,
                missingInventory,
                searchPolicy,
                genericBridge,
                negative,
                distinct,
                counts,
                reasons
            );
        }
    }

    public record AtlasReport(
        String schema,
        String corpusSchema,
        String corpusSha256,
        String inventoryRevision,
        String claimBoundary,
        List<CaseResult> cases,
        List<DirectionalityResult> directionality,
        Assessment assessment
    ) {
        public AtlasReport {
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(corpusSchema, "corpusSchema");
            Objects.requireNonNull(corpusSha256, "corpusSha256");
            Objects.requireNonNull(inventoryRevision, "inventoryRevision");
            Objects.requireNonNull(claimBoundary, "claimBoundary");
            cases = List.copyOf(cases);
            directionality = List.copyOf(directionality);
            Objects.requireNonNull(assessment, "assessment");
        }

        public String toJson() {
            JsonWriter writer = new JsonWriter().beginObject();
            writer.property("schema", schema);
            writer.property("corpusSchema", corpusSchema);
            writer.property("corpusSha256", corpusSha256);
            writer.property("inventoryRevision", inventoryRevision);
            writer.property("claimBoundary", claimBoundary);
            writer.array("cases", array -> cases.forEach(result ->
                array.objectValue(object -> writeCase(object, result))));
            writer.array("directionality", array -> directionality.forEach(result ->
                array.objectValue(object -> writeDirectionality(
                    object,
                    result))));
            writer.object("assessment", object ->
                writeAssessment(object, assessment));
            return writer.endObject().toString();
        }

        public String toMarkdown() {
            StringBuilder markdown = new StringBuilder();
            markdown.append("# Historical rediscovery and reachability atlas\n\n");
            markdown.append("- **Corpus SHA-256:** `")
                .append(corpusSha256)
                .append("`\n");
            markdown.append("- **Inventory revision:** `")
                .append(inventoryRevision)
                .append("`\n");
            markdown.append("- **Assessment:** `")
                .append(assessment.decision())
                .append("`\n\n");
            markdown.append("> ")
                .append(claimBoundary)
                .append("\n\n");
            markdown.append(
                "| Case | Family | Primary diagnosis | Production oracle | Scalar | Diversity | Guided |\n");
            markdown.append("|---|---|---|---|---:|---:|---:|\n");
            for (CaseResult result : cases) {
                markdown.append("| ")
                    .append(result.benchmarkCase().id()).append(" | ")
                    .append(result.benchmarkCase().family()).append(" | ")
                    .append(result.status()).append(" | ")
                    .append(result.production().oracle().status()).append(" | ")
                    .append(mark(result.production().scalar().reached()))
                    .append(" | ")
                    .append(mark(result.production().diversity().reached()))
                    .append(" | ")
                    .append(mark(result.production().guided().reached()))
                    .append(" |\n");
            }
            markdown.append("\n## Directionality\n\n");
            for (DirectionalityResult result : directionality) {
                markdown.append("- **")
                    .append(result.id())
                    .append(":** `")
                    .append(result.status())
                    .append("`\n");
            }
            markdown.append("\n## Assessment reasons\n\n");
            for (String reason : assessment.reasons()) {
                markdown.append("- ").append(reason).append('\n');
            }
            return markdown.toString();
        }

        private static String mark(boolean value) {
            return value ? "yes" : "no";
        }

        private static void writeDirectionality(
            JsonWriter writer,
            DirectionalityResult result
        ) {
            writer.property("id", result.id());
            writer.property("forwardCaseId", result.forwardCaseId());
            writer.property("reverseCaseId", result.reverseCaseId());
            writer.property("status", result.status().name());
            writer.property("forwardReachable", result.forwardReachable());
            writer.property("reverseReachable", result.reverseReachable());
        }

        private static void writeCase(JsonWriter writer, CaseResult result) {
            Case benchmarkCase = result.benchmarkCase();
            writer.property("id", benchmarkCase.id());
            writer.property("family", benchmarkCase.family());
            writer.property("role", benchmarkCase.role().name());
            writer.property("relation", benchmarkCase.relation().name());
            writer.property(
                "diagnosticPurpose", benchmarkCase.diagnosticPurpose());
            writer.property("provenance", benchmarkCase.provenance());
            writer.property("source", benchmarkCase.source());
            writer.property("target", benchmarkCase.target());
            writer.property(
                "targetRelation", benchmarkCase.targetRelation().name());
            writer.property("primaryStatus", result.status().name());
            writer.object("representation", object -> {
                object.property("supported", result.representation().supported());
                object.property(
                    "formattedSource",
                    result.representation().formattedSource());
                object.property(
                    "formattedTarget",
                    result.representation().formattedTarget());
                object.property("detail", result.representation().detail());
            });
            writer.object("equivalence", object -> {
                object.property("evaluated", result.equivalence().evaluated());
                object.property("equivalent", result.equivalence().equivalent());
                object.property("detail", result.equivalence().detail());
            });
            writer.object("production", object ->
                writeEngine(object, result.production()));
            writer.object("genericBridge", object ->
                writeEngine(object, result.genericBridge()));
            writer.object("curatedControl", object ->
                writeEngine(object, result.curatedControl()));
        }

        private static void writeEngine(
            JsonWriter writer,
            EngineEvidence evidence
        ) {
            writer.property("profile", evidence.profile().name());
            writer.property("execution", evidence.execution().name());
            writer.property("detail", evidence.detail());
            writer.object("oracle", object ->
                writeOracle(object, evidence.oracle()));
            writer.object("scalar", object ->
                writeSearch(object, evidence.scalar()));
            writer.object("guided", object ->
                writeSearch(object, evidence.guided()));
            writer.object("diversity", object ->
                writeSearch(object, evidence.diversity()));
        }

        private static void writeOracle(
            JsonWriter writer,
            OracleEvidence evidence
        ) {
            writer.property("execution", evidence.execution().name());
            writer.property("status", evidence.status());
            writer.stringArray(
                "witnessExpressions", evidence.witnessExpressions());
            writer.stringArray("witnessRuleIds", evidence.witnessRuleIds());
            writer.property("primitiveSteps", evidence.primitiveSteps());
            writer.property("visitedStates", evidence.visitedStates());
            writer.property(
                "generatedTransitions", evidence.generatedTransitions());
            writer.property(
                "maximumDepthReached", evidence.maximumDepthReached());
            writer.property("depthLimitReached", evidence.depthLimitReached());
            writer.property("stateLimitReached", evidence.stateLimitReached());
            writer.property("detail", evidence.detail());
        }

        private static void writeSearch(
            JsonWriter writer,
            SearchEvidence evidence
        ) {
            writer.property("execution", evidence.execution().name());
            writer.property("policy", evidence.policy());
            writer.property("reached", evidence.reached());
            writer.property("terminalStatus", evidence.terminalStatus());
            writer.property("exploredStates", evidence.exploredStates());
            writer.property("engineCalls", evidence.engineCalls());
            writer.property(
                "generatedTransformations",
                evidence.generatedTransformations());
            writer.property("depth", evidence.depth());
            writer.stringArray("path", evidence.path());
            writer.stringArray("ruleIds", evidence.ruleIds());
            if (evidence.metrics() == null) {
                writer.nullProperty("goalMetrics");
            } else {
                writer.object("goalMetrics", object ->
                    writeGoalMetrics(object, evidence.metrics()));
            }
            writer.property("detail", evidence.detail());
        }

        private static void writeGoalMetrics(
            JsonWriter writer,
            GoalMetrics metrics
        ) {
            writer.property("exploredStates", metrics.exploredStates());
            writer.property("expandedStates", metrics.expandedStates());
            writer.property(
                "generatedTransformations",
                metrics.generatedTransformations());
            writer.property("enqueuedStates", metrics.enqueuedStates());
            writer.property(
                "skippedTransformations",
                metrics.skippedTransformations());
            writer.property("duplicatePrunes", metrics.duplicatePrunes());
            writer.property(
                "transpositionPrunes", metrics.transpositionPrunes());
            writer.property("depthPrunes", metrics.depthPrunes());
            writer.property(
                "candidateBudgetPrunes", metrics.candidateBudgetPrunes());
            writer.property(
                "statesWithoutTransformations",
                metrics.statesWithoutTransformations());
            writer.property("identityCacheHits", metrics.identityCacheHits());
            writer.property(
                "identityCacheMisses", metrics.identityCacheMisses());
            writer.property("cachedExpressions", metrics.cachedExpressions());
            writer.property("internedValues", metrics.internedValues());
        }

        private static void writeAssessment(
            JsonWriter writer,
            Assessment assessment
        ) {
            writer.property("decision", assessment.decision().name());
            writer.property(
                "representationLayerWorks",
                assessment.representationLayerWorks());
            writer.property(
                "equivalenceLayerDiscriminates",
                assessment.equivalenceLayerDiscriminates());
            writer.property(
                "productionPositiveControlWorks",
                assessment.productionPositiveControlWorks());
            writer.property(
                "missingInventoryLayerIdentified",
                assessment.missingInventoryLayerIdentified());
            writer.property(
                "searchPolicyDifferenceIdentified",
                assessment.searchPolicyDifferenceIdentified());
            writer.property(
                "genericBridgeDifferenceIdentified",
                assessment.genericBridgeDifferenceIdentified());
            writer.property(
                "negativeControlPassed",
                assessment.negativeControlPassed());
            writer.property(
                "distinctPrimaryStatuses",
                assessment.distinctPrimaryStatuses());
            writer.object("statusCounts", object ->
                assessment.statusCounts().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> object.property(
                        entry.getKey().name(),
                        entry.getValue())));
            writer.stringArray("reasons", assessment.reasons());
        }
    }

    public record WrittenArtifacts(Path json, Path markdown) {
    }

    private static final class CountingEngine implements TransformationEngine {
        private final TransformationEngine delegate;
        private int calls;
        private long generated;

        private CountingEngine(TransformationEngine delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public List<Transformation> transform(String expression) {
            calls++;
            List<Transformation> result = delegate.transform(expression);
            generated += result.size();
            return result;
        }

        private int calls() {
            return calls;
        }

        private long generated() {
            return generated;
        }
    }
}
