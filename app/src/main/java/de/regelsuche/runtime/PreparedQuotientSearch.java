package de.regelsuche.runtime;

import static de.regelsuche.runtime.RuntimeJson.*;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExactParsedSubtermProjector;
import de.regelsuche.parse.ExactParsedTerm;
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
    static final String REVISION = "regelsuche.product-prepared-quotient/v2";
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
        var prepared = arm == Arm.PREPARED_QUOTIENT
            ? BestFirstSearchStrategy.preparedQuotient().searchPrepared(problem, term, context.assumptions) : null;
        var execution = switch (arm) {
            case PREPARED_QUOTIENT -> prepared.execution();
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
        boolean stateBudgetExhausted = prepared == null ? search.states().size() == context.budget.maxVisitedExpressions()
            : prepared.stateBudgetExhausted();
        evidence.put("status", stateBudgetExhausted ? "STATE_BUDGET"
            : best != null ? "EXECUTED" : prepared == null ? "NO_IMPROVEMENT" : "EXHAUSTED");
        var replay = new AstRewriteTransformationEngine(rules, MAX_AST_GROWTH, context.budget.maxCandidatesPerState());
        Replay checked = verify(best, term, context.assumptions, replay, parser);
        String rewritten = null;
        if (checked.replacement != null) {
            var applied = context.position.replaceAt(parsed.expression(), checked.replacement);
            if (applied.selectedSubtree().orElseThrow() != term.expression()) {
                throw new IllegalStateException("replay selected a different source occurrence");
            }
            rewritten = renderReplacement(parser, source, projection.selectedRange().orElseThrow(),
                applied.rewrittenRoot().orElseThrow(), best.expression());
            if (rewritten == null) evidence.put("detail", "REPLACEMENT_TEXT_MISMATCH");
        }
        evidence.put("replay", best == null ? "NO_CANDIDATE" : rewritten == null ? "REJECTED" : "VERIFIED_PRIMITIVE_REPLAY");
        evidence.put("work", fields("projectionWorkUnits", projection.work().totalWorkUnits(),
            "visitedStates", search.metrics().exploredStates(), "expandedStates", search.metrics().expandedStates(),
            "providerCalls", provider.calls, "ruleAttempts", provider.ruleAttempts,
            "rewriteNodeVisits", provider.nodeVisits, "providerProposals", provider.proposals,
            "generatedTransformations", search.metrics().generatedTransformations(),
            "replayCalls", checked.calls, "replayProposals", checked.proposals));
        response.put("successes", improvements.size());
        if (rewritten != null) {
            response.put("bestExpression", rewritten);
            response.put("improvement", sourceScore - best.score().weightedTotal());
        } else if (best != null) {
            evidence.put("status", "REPLAY_REJECTED");
        }
        return canonical(response);
    }

    private static String renderReplacement(ExpressionParser parser, String source,
            ExactParsedTerm.SourceRange range, Expr rewrittenRoot, String replacement) {
        String formatted = ExpressionFormatter.format(rewrittenRoot);
        if (reparsesAs(parser, formatted, rewrittenRoot)) return formatted;

        // Keep the verified replacement and the source outside its range intact.
        String spliced = source.substring(0, range.startInclusive()) + "(" + replacement + ")"
            + source.substring(range.endExclusive());
        return reparsesAs(parser, spliced, rewrittenRoot) ? spliced : null;
    }

    private static boolean reparsesAs(ExpressionParser parser, String text, Expr expected) {
        try {
            return parser.parseTerm(text).equals(expected);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
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
            var result = engine.transformPreparedStructurally(parsed);
            proposals += result.transformations().size();
            ruleAttempts += result.ruleAttempts();
            nodeVisits += result.nodeVisits();
            return result.transformations();
        }
    }

    private record Replay(Expr replacement, long calls, long proposals) { }

    private static Replay verify(SearchState best, ExactParsedTerm source, List<String> assumptions,
            AstRewriteTransformationEngine reference, ExpressionParser parser) {
        if (best == null || !best.path().getFirst().equals(source.source()) || !best.assumptions().equals(assumptions)) {
            return new Replay(null, 0, 0);
        }
        Expr current = source.expression();
        long proposals = 0;
        for (int index = 0; index < best.transformations().size(); index++) {
            var alternatives = reference.transformStructurally(current);
            proposals += alternatives.size();
            var recorded = best.transformations().get(index);
            Expr next = alternatives.stream().filter(alternative -> alternative.transformation().equals(recorded))
                .map(AstRewriteTransformationEngine.GeneratedAstTransformation::result).findFirst().orElse(null);
            if (next == null || !reparsesAs(parser, best.path().get(index + 1), next)) {
                return new Replay(null, index + 1L, proposals);
            }
            current = next;
        }
        return new Replay(current, best.transformations().size(), proposals);
    }
}
