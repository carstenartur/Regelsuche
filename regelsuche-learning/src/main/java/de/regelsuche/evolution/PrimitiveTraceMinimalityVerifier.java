package de.regelsuche.evolution;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.reachability.BoundedReachabilityOracle;
import de.regelsuche.transform.MeasuredTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngines;
import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.transform.TransformationProvenance;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Certifies the shortest directed primitive connection for concrete endpoints
 * in one frozen genome inventory. This is not a mathematical identity proof or
 * a universal minimality claim for every substitution into a learned program.
 */
public final class PrimitiveTraceMinimalityVerifier {
    public static final String REVISION = "regelsuche.primitive-trace-minimality/v1";
    public static final String SCOPE = "CONCRETE_FORMATTED_ENDPOINTS;FROZEN_DIRECTED_GENES;ONE_AST_OCCURRENCE_PER_STEP;NO_GROWTH_FILTER";

    public enum Status { SHORTEST_CONFIRMED, SHORTER_PATH_FOUND, INCONCLUSIVE, TECHNICAL_FAILURE }

    /** Every ceiling is an execution limit: none may silently narrow the claimed graph. */
    public record Limits(int maximumStates, int maximumTransitions, int maximumCandidatesPerState,
                         int maximumAstNodes, long maximumSourceWork) {
        public Limits {
            if (maximumStates < 1 || maximumStates > 100_000 || maximumTransitions < 0 || maximumTransitions > 1_000_000
                    || maximumCandidatesPerState < 1 || maximumCandidatesPerState > 4096
                    || maximumAstNodes < 1 || maximumAstNodes > 4096 || maximumSourceWork < 1 || maximumSourceWork > 2_000_000) {
                throw new IllegalArgumentException("invalid primitive minimality limits");
            }
        }
        public static Limits defaults() { return new Limits(4096, 65_536, 512, 1024, 500_000); }
    }

    /** Issued only by a replay and complete shortest-path check, never by parsing a claim. */
    public static final class Assessment {
        private final String inventoryHash;
        private final String source;
        private final List<Transformation> observedPath;
        private final Limits limits;
        private final Status status;
        private final List<Transformation> shortestPath;
        private final Optional<BoundedReachabilityOracle.Result> oracle;
        private final long sourceWork;
        private final String detail;

        private Assessment(String inventoryHash, String source, List<Transformation> observedPath, Limits limits,
                Status status, List<Transformation> shortestPath, BoundedReachabilityOracle.Result oracle,
                long sourceWork, String detail) {
            this.inventoryHash = inventoryHash;
            this.source = source;
            this.observedPath = List.copyOf(observedPath);
            this.limits = limits;
            this.status = status;
            this.shortestPath = List.copyOf(shortestPath);
            this.oracle = Optional.ofNullable(oracle);
            this.sourceWork = sourceWork;
            this.detail = detail;
        }
        public String inventoryHash() { return inventoryHash; }
        public String source() { return source; }
        public String target() { return observedPath.isEmpty() ? source : normalize(observedPath.getLast().transformedExpression()); }
        public int observedPrimitiveSteps() { return observedPath.size(); }
        public Limits limits() { return limits; }
        public Status status() { return status; }
        public boolean minimumProved() { return status == Status.SHORTEST_CONFIRMED || status == Status.SHORTER_PATH_FOUND; }
        public int minimumPrimitiveSteps() { return minimumProved() ? shortestPath.size() : -1; }
        public List<Transformation> shortestPath() { return shortestPath; }
        public Optional<BoundedReachabilityOracle.Result> oracle() { return oracle; }
        public boolean reusableMultistepTrace() { return minimumProved() && shortestPath.size() >= 2; }
        public long measuredWork() {
            return Math.addExact(sourceWork, oracle.map(value -> (long) value.work().expandedStates()
                + value.work().generatedTransitions() + value.work().discoveredStates()).orElse(0L));
        }
        public String contentHash() { return SchematicProofPlan.hash(toCanonicalJson()); }
        public String toCanonicalJson() {
            return new JsonWriter().beginObject().property("schema", REVISION).property("scope", SCOPE)
                .property("inventoryHash", inventoryHash).property("source", source).property("target", target())
                .property("status", status.name()).property("detail", detail)
                .property("observedPrimitiveSteps", observedPrimitiveSteps()).property("minimumPrimitiveSteps", minimumPrimitiveSteps())
                .property("reusableMultistepTrace", reusableMultistepTrace()).property("sourceWork", sourceWork)
                .property("measuredWork", measuredWork())
                .property("workScope", "SOURCE_EVENTS_PLUS_ORACLE_EXPANSIONS_TRANSITIONS_AND_RETAINED_STATES;NOT_CPU_TIME")
                .object("limits", value -> writeLimits(value, limits))
                .array("observedPath", values -> observedPath.forEach(step -> values.objectValue(value -> writeStep(value, step))))
                .array("shortestPath", values -> shortestPath.forEach(step -> values.objectValue(value -> writeStep(value, step))))
                .object("oracle", value -> oracle.ifPresent(result -> writeOracle(value, result))).endObject().toString();
        }
    }

    private final EvolutionGenome inventory;
    public PrimitiveTraceMinimalityVerifier(EvolutionGenome inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        if (inventory.rewrites().stream().anyMatch(gene -> !gene.assumptions().isEmpty())) {
            throw new IllegalArgumentException("minimality v1 requires assumption-free primitive genes");
        }
    }

    public Assessment assess(String sourceExpression, List<Transformation> observedPath, Limits limits) {
        String source = normalize(sourceExpression);
        var path = List.copyOf(observedPath);
        Objects.requireNonNull(limits, "limits");
        if (path.size() > 256) throw new IllegalArgumentException("primitive trace exceeds verification envelope");
        var complete = new CompleteSource(inventory, limits);
        try {
            replayObserved(source, path, complete);
        } catch (VerificationLimit exception) {
            return assessment(source, path, limits, Status.INCONCLUSIVE, List.of(), null, complete, exception.getMessage());
        }
        String target = path.isEmpty() ? source : normalize(path.getLast().transformedExpression());
        int shorterBound = Math.max(0, path.size() - 1);
        var oracle = new BoundedReachabilityOracle().analyze(source, target, AssumptionSignature.ofExpressions(List.of()), complete,
            new BoundedReachabilityOracle.Budget(shorterBound, shorterBound, limits.maximumStates(), limits.maximumTransitions()));
        return switch (oracle.status()) {
            case UNREACHABLE_IN_COMPLETE_BOUNDED_CLOSURE -> assessment(source, path, limits,
                Status.SHORTEST_CONFIRMED, path, oracle, complete, "ALL_SHORTER_PRIMITIVE_PATHS_EXCLUDED");
            case REACHABLE -> assessment(source, path, limits, path.isEmpty() ? Status.SHORTEST_CONFIRMED : Status.SHORTER_PATH_FOUND,
                recoverWitness(oracle.witness().orElseThrow(), complete), oracle, complete, "SHORTEST_PRIMITIVE_WITNESS_RETAINED");
            case BUDGET_INCONCLUSIVE -> assessment(source, path, limits, Status.INCONCLUSIVE, List.of(), oracle, complete, oracle.detailCode());
            case TECHNICAL_FAILURE -> assessment(source, path, limits,
                complete.limitReached ? Status.INCONCLUSIVE : Status.TECHNICAL_FAILURE, List.of(), oracle, complete, oracle.technicalDetail());
            case REACHABLE_ONLY_WITH_ADDITIONAL_ASSUMPTIONS -> throw new IllegalStateException("unconditional inventory added assumptions");
        };
    }

    /** Recompute a retained assessment against this inventory; changed inventories cannot reuse its authority. */
    public void verify(Assessment assessment) {
        if (!inventory.contentHash().equals(assessment.inventoryHash())) throw new IllegalArgumentException("minimality inventory changed");
        var actual = assess(assessment.source, assessment.observedPath, assessment.limits());
        if (!actual.toCanonicalJson().equals(assessment.toCanonicalJson())) throw new IllegalArgumentException("minimality replay mismatch");
    }

    private Assessment assessment(String source, List<Transformation> path, Limits limits, Status status,
            List<Transformation> shortest, BoundedReachabilityOracle.Result oracle, CompleteSource complete, String detail) {
        return new Assessment(inventory.contentHash(), source, path, limits, status, shortest, oracle, complete.work, detail);
    }

    private static void replayObserved(String source, List<Transformation> path, CompleteSource complete) {
        String current = source;
        for (var step : path) {
            if (step.primitiveStepCount() != 1 || step.exactTheoryStepCount() != 0
                    || step.provenance() instanceof TransformationProvenance.Sequence || !step.assumptions().isEmpty()) {
                throw new IllegalArgumentException("minimality requires a real primitive trace");
            }
            if (!complete.transform(current).contains(step)) throw new IllegalArgumentException("primitive trace replay mismatch");
            current = normalize(step.transformedExpression());
        }
    }

    private static List<Transformation> recoverWitness(BoundedReachabilityOracle.Witness witness, CompleteSource complete) {
        List<Transformation> path = new ArrayList<>();
        for (int i = 0; i < witness.edges().size(); i++) {
            var edge = witness.edges().get(i);
            var candidates = complete.retained.get(witness.states().get(i).expression());
            path.add(candidates.stream().filter(step -> step.rule().equals(edge.ruleId())
                && normalize(step.transformedExpression()).equals(edge.transformedExpression())).findFirst().orElseThrow());
        }
        return List.copyOf(path);
    }

    private static final class CompleteSource implements TransformationEngine {
        private final MeasuredTransformationEngine engine;
        private final Limits limits;
        private final Map<String, List<Transformation>> retained = new HashMap<>();
        private long work;
        private boolean limitReached;
        private CompleteSource(EvolutionGenome inventory, Limits limits) {
            this.limits = limits;
            // One extra candidate detects truncation; growth is never used to prune a shorter path.
            engine = MeasuredTransformationEngines.counting(new PreparedAstRewriteTransformationEngine(
                new EvolutionGenomeCompiler().compile(inventory).rules(), Integer.MAX_VALUE, limits.maximumCandidatesPerState() + 1));
        }
        @Override public List<Transformation> transform(String expression) {
            requireWithin(nodeCount(new ExpressionParser().parseTerm(expression)) <= limits.maximumAstNodes(), "AST_NODE_LIMIT");
            var batch = engine.transformMeasured(expression);
            work = Math.addExact(work, batch.workMetrics().totalWorkUnits());
            requireWithin(work <= limits.maximumSourceWork(), "SOURCE_WORK_LIMIT");
            requireWithin(batch.transformations().size() <= limits.maximumCandidatesPerState(), "CANDIDATE_LIMIT");
            for (var step : batch.transformations()) {
                requireWithin(nodeCount(new ExpressionParser().parseTerm(step.transformedExpression())) <= limits.maximumAstNodes(), "AST_NODE_LIMIT");
            }
            retained.put(expression, batch.transformations());
            return batch.transformations();
        }
        private void requireWithin(boolean condition, String reason) {
            if (!condition) { limitReached = true; throw new VerificationLimit(reason); }
        }
    }

    private static final class VerificationLimit extends RuntimeException {
        private VerificationLimit(String reason) { super(reason); }
    }

    private static String normalize(String expression) {
        return ExpressionFormatter.format(new ExpressionParser().parseTerm(expression));
    }

    private static long nodeCount(Expr expression) {
        if (expression instanceof BinaryExpr binary) return 1 + nodeCount(binary.left()) + nodeCount(binary.right());
        if (expression instanceof FunctionExpr function) return 1 + function.arguments().stream().mapToLong(PrimitiveTraceMinimalityVerifier::nodeCount).sum();
        return 1;
    }

    static void writeLimits(JsonWriter value, Limits limits) {
        value.property("maximumStates", limits.maximumStates()).property("maximumTransitions", limits.maximumTransitions())
            .property("maximumCandidatesPerState", limits.maximumCandidatesPerState()).property("maximumAstNodes", limits.maximumAstNodes())
            .property("maximumSourceWork", limits.maximumSourceWork());
    }

    private static void writeStep(JsonWriter value, Transformation step) {
        value.property("rule", step.rule()).property("output", step.transformedExpression()).property("applicationKey", step.applicationKey());
    }

    private static void writeOracle(JsonWriter value, BoundedReachabilityOracle.Result result) {
        var work = result.work();
        value.property("oracleId", result.oracleId()).property("status", result.status().name())
            .property("shorterDepthBound", result.budget().maxDepth()).property("shorterPrimitiveBound", result.budget().maxPrimitivePathWork())
            .property("detailCode", result.detailCode()).property("technicalDetail", result.technicalDetail())
            .object("work", out -> out.property("expandedStates", work.expandedStates()).property("generatedTransitions", work.generatedTransitions())
                .property("discoveredStates", work.discoveredStates()).property("enqueuedTransitions", work.enqueuedTransitions())
                .property("dominatedTransitions", work.dominatedTransitions()).property("outsidePrimitiveWorkTransitions", work.outsidePrimitiveWorkTransitions())
                .property("visitedStateLimitTransitions", work.visitedStateLimitTransitions()).property("supersededStates", work.supersededStates())
                .property("depthBoundaryStates", work.depthBoundaryStates()).property("primitiveWorkBoundaryStates", work.primitiveWorkBoundaryStates())
                .property("maxFrontierSize", work.maxFrontierSize()).property("generatedTransitionLimitReached", work.generatedTransitionLimitReached())
                .property("visitedStateLimitReached", work.visitedStateLimitReached()))
            .array("states", values -> result.states().forEach(state -> values.objectValue(out -> out.property("id", state.id())
                .property("expression", state.expression()).property("depth", state.depth()).property("primitiveSteps", state.primitivePathWork())
                .property("nonDominatedAtTermination", state.nonDominatedAtTermination()))))
            .array("edges", values -> result.edges().forEach(edge -> values.objectValue(out -> out.property("id", edge.id())
                .property("from", edge.fromStateId()).property("to", edge.proposedStateId()).property("rule", edge.ruleId())
                .property("output", edge.transformedExpression()).property("primitiveSteps", edge.primitiveStepCount())
                .property("disposition", edge.disposition().name()))));
    }
}
