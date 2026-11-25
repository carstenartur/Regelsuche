package de.regelsuche.runtime;

import static de.regelsuche.runtime.RuntimeJson.*;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExactParsedSubtermProjector;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.SearchProfile;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.strategy.StateLocalTransformationEngine;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Invocation-owned, explicitly qualified product search. */
final class PreparedQuotientSearch {
    static final String REVISION = "regelsuche.product-prepared-quotient/v1";
    static final SearchProfile PROFILE = SearchProfile.PREPARED_QUOTIENT_DISCOVERY;
    private static final int MAX_SOURCE_LENGTH = 1_024;
    private static final int MAX_POSITION_DEPTH = 64;
    private static final int MAX_SOURCE_NODES = 512;
    private static final int MAX_AST_GROWTH = 12;
    enum Arm { APPLICATION_HISTORY, LEGACY_QUOTIENT, PREPARED_QUOTIENT }

    private PreparedQuotientSearch() { }

    static String run(String source, TransformationGoal goal, Map<String, ?> options) {
        return run(source, goal, options, Arm.PREPARED_QUOTIENT);
    }

    /** Same bound invocation with alternative identities for the cost comparison. */
    static String run(String source, TransformationGoal goal, Map<String, ?> options, Arm arm) {
        if (source.length() > MAX_SOURCE_LENGTH || source.isBlank()) {
            throw new IllegalArgumentException("prepared quotient requires a term of at most " + MAX_SOURCE_LENGTH + " code units");
        }
        var parser = new ExpressionParser();
        var parsed = parser.parseExactTerm(source);
        var context = Context.read(options, parsed.expression());
        var projection = ExactParsedSubtermProjector.withParserOccurrenceReuse(
            new ExactParsedSubtermProjector.Policy(MAX_POSITION_DEPTH, MAX_SOURCE_LENGTH, MAX_SOURCE_NODES,
                MAX_SOURCE_LENGTH, MAX_SOURCE_LENGTH * 4, 1_000_000L), PolynomialWorkAuthority.unbounded())
            .project(parsed, context.position.path(), context.position.text());
        var rules = qualifiedRules();
        var evidence = fields("revision", REVISION, "provider", "FIRST_PARTY_INDEXED_AST",
            "ruleInventory", RuleInventoryFingerprint.contentHash(rules),
            "assumptions", context.assumptions, "position", context.position.pathKey(),
            "budget", context.budget, "goal", goal.name(), "projectionCertificate", projection.certificateHash(),
            "rootSourceHash", projection.rootSourceHash(), "identity", arm.name());
        evidence.put("sourceBinding", hash(canonical(evidence)));
        evidence.put("status", projection.status().name());
        evidence.put("detail", projection.detailCode());
        evidence.put("actualPositionText", projection.actualFormattedText());
        evidence.put("selectedRange", projection.selectedRange());
        evidence.put("work", fields("projectionWorkUnits", projection.work().totalWorkUnits()));
        var response = fields("profile", PROFILE.name(), "goal", goal.name(), "inputType", "TERM",
            "expression", source, "successes", 0, "bestExpression", null, "continuation", evidence);
        if (!projection.successful()) return canonical(response);

        var term = projection.projected().orElseThrow();
        var provider = new CountedProvider(rules, context.budget.maxCandidatesPerState());
        var problem = new SearchProblem(term.source(), provider, new ExpressionScorer(),
            new ExpressionCanonicalizer(), context.budget).withObjective(goal);
        var execution = switch (arm) {
            case PREPARED_QUOTIENT -> BestFirstSearchStrategy.preparedQuotient().searchPrepared(problem, term, context.assumptions);
            case APPLICATION_HISTORY -> new BestFirstSearchStrategy(BestFirstSearchStrategy.IdentityPolicy.APPLICATION_HISTORY_V1,
                BestFirstSearchStrategy.TieOrder.SYNTAX_V1).searchWithExecutionDiagnostics(problem);
            case LEGACY_QUOTIENT -> new BestFirstSearchStrategy(BestFirstSearchStrategy.IdentityPolicy.CONTINUATION_QUOTIENT_V1,
                BestFirstSearchStrategy.TieOrder.SYNTAX_V1).searchWithExecutionDiagnostics(problem);
        };
        var search = execution.search();
        int sourceScore = problem.scorer().score(term.source()).weightedTotal();
        var improvements = search.states().stream().filter(state -> state.depth() != 0
            && state.score().weightedTotal() < sourceScore).toList();
        var best = improvements.stream().min(Comparator.comparingInt((SearchState state) -> state.score().weightedTotal())
            .thenComparingInt(SearchState::depth).thenComparing(SearchState::expression)).orElse(null);

        evidence.put("quotient", execution.quotient());
        evidence.put("bestRules", best == null ? List.of() : best.appliedRuleIds());
        evidence.put("status", search.states().size() == context.budget.maxVisitedExpressions() ? "STATE_BUDGET"
            : best == null ? "NO_IMPROVEMENT" : "EXECUTED");
        var replay = new AstRewriteTransformationEngine(rules, MAX_AST_GROWTH, context.budget.maxCandidatesPerState());
        Replay checked = verify(best, term.source(), context.assumptions, replay);
        evidence.put("replay", best == null ? "NO_CANDIDATE" : checked.valid ? "VERIFIED_PRIMITIVE_REPLAY" : "REJECTED");
        evidence.put("work", fields("projectionWorkUnits", projection.work().totalWorkUnits(),
            "visitedStates", search.metrics().exploredStates(), "expandedStates", search.metrics().expandedStates(),
            "providerCalls", provider.calls, "ruleAttempts", provider.ruleAttempts,
            "rewriteNodeVisits", provider.nodeVisits, "providerProposals", provider.proposals,
            "generatedTransformations", search.metrics().generatedTransformations(),
            "replayCalls", checked.calls, "replayProposals", checked.proposals));
        response.put("successes", improvements.size());
        if (best != null && checked.valid) {
            Expr replacement = parser.parseTerm(best.expression());
            var applied = context.position.replaceAt(parsed.expression(), replacement);
            if (applied.selectedSubtree().orElseThrow() != term.expression()) {
                throw new IllegalStateException("replay selected a different source occurrence");
            }
            response.put("bestExpression", ExpressionFormatter.format(applied.rewrittenRoot().orElseThrow()));
            response.put("improvement", sourceScore - best.score().weightedTotal());
        } else if (best != null) {
            evidence.put("status", "REPLAY_REJECTED");
        }
        return canonical(response);
    }

    private static List<RewriteRule> qualifiedRules() {
        return AstRewriteTransformationEngine.defaultRules().stream()
            .filter(rule -> rule.getClass() == PatternRewriteRule.class)
            .filter(rule -> preservesBindings((PatternRewriteRule) rule)).toList();
    }

    private static boolean preservesBindings(PatternRewriteRule rule) {
        if (!rule.recognitionProfile().equals(RecognitionProfile.exact())) return false;
        var before = new HashSet<String>();
        var after = new HashSet<String>();
        placeholders(rule.source(), before);
        placeholders(rule.target(), after);
        return before.equals(after);
    }

    private static void placeholders(PatternExpr pattern, Set<String> names) {
        switch (pattern) {
            case PatternExpr.Placeholder variable -> names.add(variable.name());
            case PatternExpr.Operation operation -> {
                placeholders(operation.left(), names);
                placeholders(operation.right(), names);
            }
            case PatternExpr.Function function -> function.arguments().forEach(argument -> placeholders(argument, names));
            default -> { }
        }
    }

    private record Context(TreePosition position, List<String> assumptions, SearchHeuristic budget) {
        private static Context read(Map<String, ?> options, Expr root) {
            only(options, "position", "assumptions", "budget");
            var assumptions = strings(options, "assumptions");
            if (assumptions.size() > 64 || assumptions.stream().anyMatch(value -> value.length() > 256)) {
                throw new IllegalArgumentException("prepared quotient assumptions exceed their representation limits");
            }
            var position = options.containsKey("position") ? readPosition(object(options.get("position")))
                : new TreePosition(List.of(), ExpressionFormatter.format(root));
            var limits = options.containsKey("budget") ? object(options.get("budget")) : Map.<String, Object>of();
            only(limits, "maxDepth", "maxVisitedExpressions", "maxExpandingSteps", "maxCandidatesPerState");
            var maximum = PROFILE.heuristic();
            var budget = new SearchHeuristic(integer(limits, "maxDepth", maximum.maxDepth(), 0, maximum.maxDepth()),
                integer(limits, "maxVisitedExpressions", maximum.maxVisitedExpressions(), 1, maximum.maxVisitedExpressions()),
                maximum.significantImprovementThreshold(), integer(limits, "maxExpandingSteps", maximum.maxExpandingSteps(), 0, maximum.maxExpandingSteps()),
                integer(limits, "maxCandidatesPerState", maximum.maxCandidatesPerState(), 1, maximum.maxCandidatesPerState()),
                maximum.beamWidth());
            return new Context(position, AssumptionSignature.ofExpressions(assumptions).normalizedAssumptions(), budget);
        }

        private static TreePosition readPosition(Map<String, ?> position) {
            only(position, "path", "text");
            if (!(position.get("path") instanceof List<?> path) || path.size() > MAX_POSITION_DEPTH) {
                throw new IllegalArgumentException("position requires a bounded path array");
            }
            var indices = path.stream().map(Context::readIndex).toList();
            String snapshot = text(position, "text", "");
            if (snapshot.length() > MAX_SOURCE_LENGTH * 4 || snapshot.isBlank()) {
                throw new IllegalArgumentException("position requires bounded formatter text");
            }
            return new TreePosition(indices, snapshot);
        }

        private static int readIndex(Object value) {
            if (!(value instanceof Integer || value instanceof Long)) {
                throw new IllegalArgumentException("position index must be an integer");
            }
            long index = ((Number) value).longValue();
            if (index < 0 || index > Integer.MAX_VALUE) throw new IllegalArgumentException("position index outside bounds");
            return (int) index;
        }
    }

    private static final class CountedProvider implements StateLocalTransformationEngine {
        private final PreparedAstRewriteTransformationEngine engine;
        private long calls, proposals, ruleAttempts, nodeVisits;

        private CountedProvider(List<RewriteRule> rules, int candidates) {
            engine = new PreparedAstRewriteTransformationEngine(rules, MAX_AST_GROWTH, candidates).withRuleIndex();
        }

        @Override
        public List<Transformation> transformPrepared(String source, Expr parsed, List<String> assumptions) {
            calls++;
            var result = engine.transformPrepared(parsed);
            proposals += result.transformations().size();
            ruleAttempts += result.ruleAttempts();
            nodeVisits += result.nodeVisits();
            return result.transformations();
        }
    }

    private record Replay(boolean valid, long calls, long proposals) { }

    private static Replay verify(SearchState best, String source, List<String> assumptions,
            AstRewriteTransformationEngine reference) {
        if (best == null) return new Replay(false, 0, 0);
        if (!best.path().getFirst().equals(source) || !best.assumptions().equals(assumptions)) {
            return new Replay(false, 0, 0);
        }
        long proposals = 0;
        for (int index = 0; index < best.transformations().size(); index++) {
            var alternatives = reference.transform(best.path().get(index));
            proposals += alternatives.size();
            if (!alternatives.contains(best.transformations().get(index))) return new Replay(false, index + 1L, proposals);
        }
        return new Replay(true, best.transformations().size(), proposals);
    }
}
