package de.regelsuche.benchmark;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalMetrics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.search.strategy.SearchProblem.TargetRelation;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.value.ExprValueFactory;
import de.regelsuche.value.ExprValueFactory.ExprValue;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Reproducible A/B harness for the goal-conditioned capability frontier.
 *
 * <p>The control and guided runs use the same engine, scorer and budgets. The
 * target changes ordering and early termination only; the report is search
 * telemetry and never mathematical proof.</p>
 */
public final class CapabilityFrontierExperiment {
    public static final String SCHEMA = "regelsuche.capability-frontier/v1";

    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    public FrontierReport run(List<FrontierCase> cases) {
        Objects.requireNonNull(cases, "cases");
        List<CaseResult> results = cases.stream()
            .sorted(Comparator.comparing(FrontierCase::id))
            .map(this::runCase)
            .toList();
        return new FrontierReport(SCHEMA, results);
    }

    public Path write(Path output, FrontierReport report) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(report, "report");
        try {
            Path parent = output.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(output, report.toJson(), StandardCharsets.UTF_8);
            return output;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private CaseResult runCase(FrontierCase frontierCase) {
        SearchProblem problem = new SearchProblem(
            frontierCase.rootExpression(),
            frontierCase.engine(),
            scorer,
            canonicalizer,
            frontierCase.heuristic());

        BaselineEvidence baseline = runBaseline(problem, frontierCase.target());
        GoalSearchResult guidedResult = new BestFirstSearchStrategy()
            .searchWithDiagnostics(problem.withTarget(frontierCase.target()));
        GuidedEvidence guided = GuidedEvidence.from(guidedResult);
        FrontierOutcome outcome = classify(frontierCase.expectation(), baseline, guided);
        double reduction = reductionRatio(baseline.statesUntilTarget(), guided.metrics().exploredStates());
        boolean material = isMaterial(outcome, baseline, guided, reduction);

        return new CaseResult(
            frontierCase.id(),
            frontierCase.expectation(),
            outcome,
            baseline,
            guided,
            reduction,
            material);
    }

    private BaselineEvidence runBaseline(SearchProblem problem, SearchTarget target) {
        List<SearchState> states = new BestFirstSearchStrategy().search(problem);
        try (TargetMatcher matcher = new TargetMatcher(target, canonicalizer)) {
            for (int index = 0; index < states.size(); index++) {
                SearchState state = states.get(index);
                if (matcher.matches(state.expression())) {
                    return new BaselineEvidence(
                        true,
                        index + 1,
                        state.depth(),
                        state.path(),
                        state.appliedRuleIds());
                }
            }
        }
        return new BaselineEvidence(false, states.size(), -1, List.of(), List.of());
    }

    private static FrontierOutcome classify(
        ConnectivityExpectation expectation,
        BaselineEvidence baseline,
        GuidedEvidence guided
    ) {
        if (guided.reached() && !baseline.reached()) {
            return FrontierOutcome.GUIDED_ONLY_REACHED;
        }
        if (guided.reached() && baseline.reached()
                && guided.metrics().exploredStates() < baseline.statesUntilTarget()) {
            return FrontierOutcome.GUIDED_FEWER_STATES;
        }
        if (guided.reached() && baseline.reached()) {
            return FrontierOutcome.BOTH_REACHED;
        }
        if (baseline.reached()) {
            return FrontierOutcome.GUIDANCE_REGRESSION;
        }
        return switch (guided.status()) {
            case STATE_BUDGET -> FrontierOutcome.STATE_BUDGET;
            case DEPTH_BUDGET -> FrontierOutcome.DEPTH_BUDGET;
            case CANDIDATE_BUDGET -> FrontierOutcome.CANDIDATE_BUDGET;
            case NO_TRANSFORMATIONS -> FrontierOutcome.NO_TRANSFORMATIONS;
            case UNPARSEABLE_TARGET -> FrontierOutcome.UNPARSEABLE_TARGET;
            case FRONTIER_EXHAUSTED -> expectation == ConnectivityExpectation.MISSING_OPERATOR
                ? FrontierOutcome.MISSING_OPERATOR
                : FrontierOutcome.CONNECTED_NOT_REACHED;
            case UNTARGETED, ROOT_ALREADY_TARGET, REACHED -> FrontierOutcome.CONNECTED_NOT_REACHED;
        };
    }

    private static double reductionRatio(int baselineStates, int guidedStates) {
        return baselineStates <= 0
            ? 0.0
            : (baselineStates - guidedStates) / (double) baselineStates;
    }

    private static boolean isMaterial(
        FrontierOutcome outcome,
        BaselineEvidence baseline,
        GuidedEvidence guided,
        double reduction
    ) {
        return outcome == FrontierOutcome.GUIDED_ONLY_REACHED
            || outcome == FrontierOutcome.GUIDED_FEWER_STATES && reduction >= 0.5
            || baseline.reached() && guided.reached() && guided.depth() < baseline.depth();
    }

    public enum ConnectivityExpectation {
        CONNECTED,
        MISSING_OPERATOR
    }

    public enum FrontierOutcome {
        GUIDED_ONLY_REACHED,
        GUIDED_FEWER_STATES,
        BOTH_REACHED,
        GUIDANCE_REGRESSION,
        STATE_BUDGET,
        DEPTH_BUDGET,
        CANDIDATE_BUDGET,
        NO_TRANSFORMATIONS,
        UNPARSEABLE_TARGET,
        MISSING_OPERATOR,
        CONNECTED_NOT_REACHED
    }

    public record FrontierCase(
        String id,
        String rootExpression,
        SearchTarget target,
        TransformationEngine engine,
        SearchHeuristic heuristic,
        ConnectivityExpectation expectation
    ) {
        public FrontierCase {
            requireText(id, "id");
            requireText(rootExpression, "rootExpression");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(engine, "engine");
            Objects.requireNonNull(heuristic, "heuristic");
            Objects.requireNonNull(expectation, "expectation");
        }
    }

    public record BaselineEvidence(
        boolean reached,
        int statesUntilTarget,
        int depth,
        List<String> path,
        List<String> ruleIds
    ) {
        public BaselineEvidence {
            path = List.copyOf(path);
            ruleIds = List.copyOf(ruleIds);
        }
    }

    public record GuidedEvidence(
        boolean reached,
        GoalStatus status,
        int depth,
        int bestDistance,
        List<String> path,
        List<String> ruleIds,
        GoalMetrics metrics
    ) {
        public GuidedEvidence {
            Objects.requireNonNull(status, "status");
            path = List.copyOf(path);
            ruleIds = List.copyOf(ruleIds);
            Objects.requireNonNull(metrics, "metrics");
        }

        private static GuidedEvidence from(GoalSearchResult result) {
            SearchState reached = result.reachedState();
            return new GuidedEvidence(
                result.reached(),
                result.status(),
                reached == null ? -1 : reached.depth(),
                result.bestDistance(),
                reached == null ? List.of() : reached.path(),
                reached == null ? List.of() : reached.appliedRuleIds(),
                result.metrics());
        }
    }

    public record CaseResult(
        String id,
        ConnectivityExpectation expectation,
        FrontierOutcome outcome,
        BaselineEvidence baseline,
        GuidedEvidence guided,
        double stateReductionRatio,
        boolean materialSuccess
    ) {
    }

    public record FrontierReport(String schema, List<CaseResult> cases) {
        public FrontierReport {
            Objects.requireNonNull(schema, "schema");
            cases = List.copyOf(cases);
        }

        public long materialSuccesses() {
            return cases.stream().filter(CaseResult::materialSuccess).count();
        }

        public String toJson() {
            JsonWriter writer = new JsonWriter().beginObject();
            writer.property("schema", schema);
            writer.array("cases", array -> cases.forEach(result ->
                array.objectValue(object -> writeCase(object, result))));
            return writer.endObject().toString();
        }

        private static void writeCase(JsonWriter writer, CaseResult result) {
            writer.property("id", result.id());
            writer.property("expectation", result.expectation().name());
            writer.property("outcome", result.outcome().name());
            writer.property("stateReductionRatio", result.stateReductionRatio());
            writer.property("materialSuccess", result.materialSuccess());
            writer.object("baseline", baseline -> writeBaseline(baseline, result.baseline()));
            writer.object("guided", guided -> writeGuided(guided, result.guided()));
        }

        private static void writeBaseline(JsonWriter writer, BaselineEvidence evidence) {
            writer.property("reached", evidence.reached());
            writer.property("statesUntilTarget", evidence.statesUntilTarget());
            writer.property("depth", evidence.depth());
            writer.stringArray("path", evidence.path());
            writer.stringArray("ruleIds", evidence.ruleIds());
        }

        private static void writeGuided(JsonWriter writer, GuidedEvidence evidence) {
            writer.property("reached", evidence.reached());
            writer.property("status", evidence.status().name());
            writer.property("depth", evidence.depth());
            writer.property("bestDistance", evidence.bestDistance());
            writer.stringArray("path", evidence.path());
            writer.stringArray("ruleIds", evidence.ruleIds());
            writer.object("metrics", metrics -> writeMetrics(metrics, evidence.metrics()));
        }

        private static void writeMetrics(JsonWriter writer, GoalMetrics metrics) {
            writer.property("exploredStates", metrics.exploredStates());
            writer.property("expandedStates", metrics.expandedStates());
            writer.property("generatedTransformations", metrics.generatedTransformations());
            writer.property("enqueuedStates", metrics.enqueuedStates());
            writer.property("skippedTransformations", metrics.skippedTransformations());
            writer.property("duplicatePrunes", metrics.duplicatePrunes());
            writer.property("transpositionPrunes", metrics.transpositionPrunes());
            writer.property("depthPrunes", metrics.depthPrunes());
            writer.property("candidateBudgetPrunes", metrics.candidateBudgetPrunes());
            writer.property("statesWithoutTransformations", metrics.statesWithoutTransformations());
            writer.property("identityCacheHits", metrics.identityCacheHits());
            writer.property("identityCacheMisses", metrics.identityCacheMisses());
            writer.property("cachedExpressions", metrics.cachedExpressions());
            writer.property("internedValues", metrics.internedValues());
        }
    }

    private static final class TargetMatcher implements AutoCloseable {
        private final SearchTarget target;
        private final String normalizedTarget;
        private final ExpressionParser parser = new ExpressionParser();
        private final ExpressionCanonicalizer canonicalizer;
        private final ExprValueFactory values = new ExprValueFactory();
        private final ExprValue targetValue;

        private TargetMatcher(SearchTarget target, ExpressionCanonicalizer canonicalizer) {
            this.target = Objects.requireNonNull(target, "target");
            this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
            normalizedTarget = normalize(target.targetExpression());
            targetValue = target.relation() == TargetRelation.VALUE_EQUIVALENT
                ? value(normalizedTarget)
                : null;
        }

        private boolean matches(String expression) {
            if (target.relation() == TargetRelation.SYNTAX_EXACT) {
                return normalize(expression).equals(normalizedTarget);
            }
            ExprValue candidate = value(expression);
            return candidate != null && targetValue != null && candidate.sameValue(targetValue);
        }

        private ExprValue value(String expression) {
            try {
                return values.fromExpr(canonicalizer.canonicalize(parser.parseTerm(expression)));
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        @Override
        public void close() {
            values.close();
        }
    }

    private static String normalize(String expression) {
        return Objects.requireNonNull(expression, "expression").trim().replaceAll("\\s+", " ");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
