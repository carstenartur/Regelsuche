package de.regelsuche.benchmark;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.IdentityPolicy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.QuotientMetrics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.TieOrder;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.AstRewriteTransformationEngines;
import de.regelsuche.transform.AstRewriteTransformationEngines.Backend;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Public target-visible diagnostic; never a held-out discovery or runtime superiority claim. */
public final class ContinuationQuotientComparison {
    public static final String SCHEMA = "regelsuche.continuation-quotient-comparison/v1";
    private ContinuationQuotientComparison() {}

    public record Row(String caseId, String family, String source, String target, String backend, String policy,
            int maxDepth, int maxStates, boolean targetReached, boolean replayVerified, int explored,
            int distinctSyntax, long engineCalls, long generatedProposals, long generatedPrimitiveSteps,
            int duplicatePrunes, QuotientMetrics arena, long replayCalls, long replayProposals) {}
    public record Report(String corpusSha256, String inventoryRevision, List<Row> rows) {
        public Report { rows = List.copyOf(rows); }
        public String json() {
            var out = new JsonWriter().beginObject().property("schema", SCHEMA)
                .property("corpusSha256", corpusSha256).property("inventoryRevision", inventoryRevision)
                .property("tieOrder", TieOrder.SYNTAX_V1.name())
                .property("regime", "PUBLIC_TARGET_VISIBLE_DIAGNOSTIC")
                .property("claim", "FINITE_WORK_COUNTS_NOT_RUNTIME_OR_HEAP")
                .array("rows", values -> rows.forEach(row -> values.objectValue(o -> writeRow(o, row))));
            return out.endObject().toString() + "\n";
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("output JSON path required");
        var output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, run().json());
    }

    public static Report run() {
        var corpus = HistoricalRediscoveryCorpus.load();
        var rows = new ArrayList<Row>();
        for (var sample : corpus.cases()) {
            var heuristic = new SearchHeuristic(sample.searchMaxDepth(), sample.searchMaxVisitedStates(), 1,
                sample.maxExpandingSteps(), sample.maxCandidatesPerState(), sample.beamWidth());
            var target = sample.targetRelation() == HistoricalRediscoveryCorpus.TargetRelation.SYNTAX_EXACT
                ? SearchProblem.SearchTarget.syntaxExact(format(sample.target()))
                : SearchProblem.SearchTarget.valueEquivalent(format(sample.target()));
            compare(rows, sample.id(), sample.family(), sample.source(), sample.target(), target,
                heuristic, AstRewriteTransformationEngine.defaultRules(), 128,
                Math.max(200, sample.maxCandidatesPerState() * 2));
        }
        control(rows, "independent-three-factor", "pair(a*x+a*y,b*x+b*y,c*x+c*y)",
            "pair(a*(x+y),b*(x+y),c*(x+y))", 3,
            Set.of("ast_factor_common_left", "ast_factor_common_right"));
        control(rows, "nested-power-control", "((x^2)^2)^2", "x^8", 4, Set.of("ast_power_of_power"));
        return new Report(corpus.contentSha256(), corpus.inventoryRevision(), rows);
    }

    private static void control(List<Row> rows, String id, String source, String target, int depth, Set<String> inventory) {
        var rules = AstRewriteTransformationEngine.defaultRules().stream().filter(r -> inventory.contains(r.id())).toList();
        if (rules.size() != inventory.size()) throw new IllegalStateException("control inventory changed");
        compare(rows, id, "DECLARED_PRODUCTION_CONTROL", source, target, SearchProblem.SearchTarget.syntaxExact(format(target)),
            new SearchHeuristic(depth, 10_000, 1, depth, 10_000, 10_000), rules, 128, 10_000);
    }

    private static void compare(List<Row> rows, String id, String family, String source, String targetText,
            SearchProblem.SearchTarget target, SearchHeuristic heuristic, List<RewriteRule> rules, int growth, int candidates) {
        for (var backend : Backend.values()) {
            List<List<String>> legacyPaths = null;
            for (var policy : IdentityPolicy.values()) {
                var engine = new CountedEngine(AstRewriteTransformationEngines.create(backend, rules, growth, candidates));
                var problem = new SearchProblem(format(source), engine, new ExpressionScorer(), new ExpressionCanonicalizer(), heuristic)
                    .withTarget(target.continueAfterReached());
                var run = new BestFirstSearchStrategy(policy, TieOrder.SYNTAX_V1).searchWithExecutionDiagnostics(problem);
                var paths = run.search().states().stream().map(SearchState::path).toList();
                if (policy == IdentityPolicy.APPLICATION_HISTORY_V1) legacyPaths = paths;
                if (policy == IdentityPolicy.ARENA_APPLICATION_HISTORY_V1 && !paths.equals(legacyPaths))
                    throw new IllegalStateException("arena-only path parity failed: " + id + " " + backend);
                var replay = new Replay(rules, growth, candidates);
                for (var state : run.search().states()) replay.require(state);
                rows.add(new Row(id, family, source, targetText, backend.name(), policy.version(), heuristic.maxDepth(),
                    heuristic.maxVisitedExpressions(), run.search().reached(), true, run.search().states().size(),
                    (int) run.search().states().stream().map(SearchState::expression).distinct().count(),
                    engine.calls, engine.proposals, engine.primitiveSteps, run.search().metrics().duplicatePrunes(),
                    run.quotient(), replay.engine.calls, replay.engine.proposals));
            }
        }
    }

    private static String format(String expression) {
        return ExpressionFormatter.format(new ExpressionParser().parseTerm(expression));
    }

    private static final class CountedEngine implements TransformationEngine {
        private final TransformationEngine delegate;
        long calls, proposals, primitiveSteps;
        CountedEngine(TransformationEngine delegate) { this.delegate = delegate; }
        @Override public List<Transformation> transform(String expression) {
            calls++;
            var result = delegate.transform(expression);
            proposals += result.size();
            primitiveSteps += result.stream().mapToLong(Transformation::primitiveStepCount).sum();
            return result;
        }
    }

    /** A separately constructed reference matcher checks every retained primitive edge. */
    private static final class Replay {
        private final CountedEngine engine;
        private final Map<String, List<Transformation>> cache = new HashMap<>();
        Replay(List<RewriteRule> rules, int growth, int candidates) {
            engine = new CountedEngine(AstRewriteTransformationEngines.reference(rules, growth, candidates));
        }
        void require(SearchState state) {
            if (state.depth() == 0) return;
            var path = state.path();
            var moves = state.transformations();
            if (moves == null || moves.size() + 1 != path.size()) throw new IllegalStateException("incomplete lineage");
            for (int i = 0; i < moves.size(); i++) {
                var move = moves.get(i);
                if (!move.transformedExpression().equals(path.get(i + 1))
                        || !cache.computeIfAbsent(path.get(i), engine::transform).contains(move))
                    throw new IllegalStateException("primitive replay differs: " + state.expression());
            }
        }
    }

    private static void writeRow(JsonWriter o, Row r) {
        o.property("caseId", r.caseId()).property("family", r.family()).property("source", r.source())
            .property("target", r.target()).property("backend", r.backend()).property("policy", r.policy())
            .property("maxDepth", r.maxDepth()).property("maxStates", r.maxStates())
            .property("targetReached", r.targetReached()).property("replayVerified", r.replayVerified())
            .property("explored", r.explored()).property("distinctSyntax", r.distinctSyntax())
            .property("engineCalls", r.engineCalls()).property("generatedProposals", r.generatedProposals())
            .property("generatedPrimitiveSteps", r.generatedPrimitiveSteps()).property("duplicatePrunes", r.duplicatePrunes())
            .property("semanticNodes", r.arena().semanticNodes()).property("syntaxObservations", r.arena().syntaxObservations())
            .property("retainedLabels", r.arena().retainedLabels()).property("successorCacheHits", r.arena().successorCacheHits())
            .property("arenaEntries", r.arena().arenaEntries()).property("arenaStructuralBytes", r.arena().arenaStructuralBytes())
            .property("digestComputations", r.arena().digestComputations()).property("replayCalls", r.replayCalls())
            .property("replayProposals", r.replayProposals());
    }
}
