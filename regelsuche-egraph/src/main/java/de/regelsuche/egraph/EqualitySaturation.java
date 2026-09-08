package de.regelsuche.egraph;

import de.regelsuche.ast.Expr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Egg-style equality saturation: apply a set of {@link RewriteRule}s to
 * an {@link EGraph} until no rule fires anymore, the iteration budget is
 * exhausted, or the graph hits a configured node-count guard.
 *
 * <p>The crucial difference to the existing path-based search strategies
 * is that <em>all</em> matches of <em>all</em> rules in a given iteration
 * are collected first, and only then applied/unioned. As a consequence
 * the saturated e-graph holds every reachable equivalent form
 * simultaneously, and the final {@link #saturate} step extracts the
 * cheapest representative according to a pluggable cost function — no
 * combinatorial explosion of separate search paths.</p>
 *
 * <p>Bridging to the existing rule set:
 * <ul>
 *   <li><b>Pattern-based rules</b> ({@link PatternRewriteRule}) are
 *       matched directly on e-nodes / e-classes via {@link
 *       EGraphPatternMatcher}, and their target is instantiated directly
 *       into the e-graph via {@link EGraphPatternApplier} — no AST round
 *       trip.</li>
 *   <li><b>Custom rules</b> (the {@code MetadataRule} subclasses living
 *       inside {@code AstRewriteTransformationEngine}) are not pattern
 *       based. For those we walk every e-class, materialise its
 *       <em>cheapest</em> representative (using {@link
 *       EGraph#extract(EClassId, ToIntFunction)} with a uniform cost),
 *       and run the rule's {@code matches/apply} on that concrete AST.
 *       The rewritten AST is then re-added to the graph via {@link
 *       EGraph#addExpression(Expr)} and unioned with the source class.
 *       This handles the rules we already ship; richer concrete-side
 *       matching is intentionally out of scope for PR 2b.</li>
 * </ul>
 * </p>
 *
 * <p>Only equivalence-preserving rules may merge classes. Concrete rule
 * applications must introduce no unresolved assumptions. Pattern subclasses
 * use concrete matching so their additional guards and apply semantics remain
 * authoritative; the direct e-node path is reserved for the base pattern rule.</p>
 */
public final class EqualitySaturation {

    /** Configuration tuning saturation behaviour. */
    public record Config(int maxIterations, int maxNodes, boolean useDirtyWorklist) {
        public Config {
            if (maxIterations <= 0) {
                throw new IllegalArgumentException("maxIterations must be > 0");
            }
            if (maxNodes <= 0) {
                throw new IllegalArgumentException("maxNodes must be > 0");
            }
        }

        public Config(int maxIterations, int maxNodes) {
            this(maxIterations, maxNodes, true);
        }

        /** Default configuration: 12 iterations, ~10 000 node guard. */
        public static Config defaults() {
            return new Config(12, 10_000, true);
        }
    }

    private final List<RewriteRule> rules;
    private final Config config;

    public EqualitySaturation(Collection<RewriteRule> rules) {
        this(rules, Config.defaults());
    }

    public EqualitySaturation(Collection<RewriteRule> rules, Config config) {
        Objects.requireNonNull(rules, "rules");
        this.rules = List.copyOf(rules);
        this.config = Objects.requireNonNull(config, "config");
    }

    public List<RewriteRule> rules() {
        return rules;
    }

    public Config config() {
        return config;
    }

    /**
     * Saturate {@code eGraph} starting from {@code root}, then extract the
     * lowest-cost representative using {@code costOfNode} and return both
     * the extracted AST and the run statistics.
     */
    public Result saturate(EGraph eGraph, EClassId root, ToIntFunction<ENode> costOfNode) {
        Objects.requireNonNull(eGraph, "eGraph");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(costOfNode, "costOfNode");
        EGraphPatternMatcher matcher = new EGraphPatternMatcher(eGraph);
        EGraphPatternApplier applier = new EGraphPatternApplier(eGraph);
        matcher.resetStats();

        Map<String, Integer> appliedRules = new LinkedHashMap<>();
        int merges = 0;
        int iterations = 0;
        SaturationStats.Reason stopReason = SaturationStats.Reason.FIX_POINT;
        boolean saturated = false;
        Collection<EClassId> dirtyClasses = eGraph.consumeDirtyClasses();
        if (dirtyClasses.isEmpty()) {
            List<EClassId> allClasses = new ArrayList<>();
            for (EClass eclass : eGraph.classes()) {
                allClasses.add(eclass.id());
            }
            dirtyClasses = allClasses;
        }

        for (int i = 0; i < config.maxIterations(); i++) {
            iterations = i + 1;
            if (eGraph.nodeCount() > config.maxNodes()) {
                stopReason = SaturationStats.Reason.NODE_BUDGET;
                break;
            }

            int classesBefore = eGraph.classCount();
            int nodesBefore = eGraph.nodeCount();

            Collection<EClassId> classesToScan = config.useDirtyWorklist()
                ? dirtyClasses
                : allClasses(eGraph);
            int fired = applyAllRules(eGraph, matcher, applier, costOfNode, appliedRules, classesToScan);
            eGraph.rebuild();
            dirtyClasses = eGraph.consumeDirtyClasses();
            int classesAfter = eGraph.classCount();
            merges += Math.max(0, (classesBefore + (eGraph.nodeCount() - nodesBefore)) - classesAfter);

            if (fired == 0 || (classesAfter == classesBefore && eGraph.nodeCount() == nodesBefore)) {
                saturated = true;
                stopReason = SaturationStats.Reason.FIX_POINT;
                break;
            }
            if (i == config.maxIterations() - 1) {
                stopReason = SaturationStats.Reason.ITERATION_BUDGET;
            }
        }

        Expr best = eGraph.extract(root, costOfNode);
        EGraphPatternMatcher.MatcherStats matcherStats = matcher.stats();
        SaturationStats stats = new SaturationStats(
            eGraph.classCount(),
            eGraph.nodeCount(),
            merges,
            iterations,
            appliedRules,
            de.regelsuche.parse.ExpressionFormatter.format(best),
            saturated,
            stopReason,
            matcherStats.classesScanned(),
            matcherStats.nodesScanned(),
            matcherStats.candidateClassesSkipped(),
            matcherStats.matchesFound(),
            matcherStats.matcherCacheHits(),
            matcherStats.matcherCacheMisses(),
            iterations,
            appliedRules.values().stream().mapToInt(Integer::intValue).sum()
        );
        return new Result(best, stats);
    }

    /**
     * Apply every rule once across the entire graph. Pattern matches are
     * collected first and rewrites are applied in a second pass so a
     * rule's effect during iteration {@code i} cannot retroactively
     * spawn new matches for itself within the same iteration — that's
     * what makes saturation deterministic and explosion-free.
     */
    private int applyAllRules(
        EGraph eGraph,
        EGraphPatternMatcher matcher,
        EGraphPatternApplier applier,
        ToIntFunction<ENode> costOfNode,
        Map<String, Integer> appliedRules,
        Collection<EClassId> classSnapshot
    ) {
        int fired = 0;
        List<EClassId> deterministicSnapshot = canonicalSorted(eGraph, classSnapshot);

        for (RewriteRule rule : rules) {
            if (!rule.isEquivalencePreservingByConstruction()) {
                continue;
            }
            // Only the unmodified pattern implementation has no additional
            // matching, assumption or apply semantics to preserve.
            int applications = rule.getClass() == PatternRewriteRule.class
                ? applyPattern(eGraph, matcher, applier, (PatternRewriteRule) rule, deterministicSnapshot)
                : applyConcrete(eGraph, rule, costOfNode, deterministicSnapshot);
            if (applications > 0) {
                appliedRules.merge(rule.id(), applications, Integer::sum);
                fired += applications;
            }
        }
        return fired;
    }

    private int applyPattern(EGraph graph, EGraphPatternMatcher matcher, EGraphPatternApplier applier,
            PatternRewriteRule rule, List<EClassId> snapshot) {
        int fired = 0;
        List<EGraphPatternMatcher.Match> matches = matcher.matchAll(rule.id(), rule.source(), snapshot);
        for (EGraphPatternMatcher.Match match : matches) {
            EClassId rhs = applier.instantiate(rule.target(), match.bindings());
            EClassId lhs = graph.find(match.root());
            if (!graph.areEquivalent(lhs, rhs)) {
                graph.union(lhs, rhs);
                fired++;
            }
        }
        return fired;
    }

    private int applyConcrete(EGraph graph, RewriteRule rule, ToIntFunction<ENode> costOfNode,
            List<EClassId> snapshot) {
        int fired = 0;
        Set<EClassId> seen = new LinkedHashSet<>();
        for (EClassId raw : snapshot) {
            EClassId canonical = graph.find(raw);
            if (!seen.add(canonical)) {
                continue;
            }
            Expr rewritten = rewriteConcrete(graph, canonical, rule, costOfNode);
            if (rewritten == null) {
                continue;
            }
            EClassId rhs = graph.addExpression(rewritten);
            if (!graph.areEquivalent(canonical, rhs)) {
                graph.union(canonical, rhs);
                fired++;
            }
        }
        return fired;
    }

    private Expr rewriteConcrete(EGraph graph, EClassId source, RewriteRule rule,
            ToIntFunction<ENode> costOfNode) {
        Expr representative;
        try {
            representative = graph.extract(source, costOfNode);
        } catch (IllegalStateException ex) {
            return null;
        }
        if (!rule.matches(representative) || !rule.assumptions(representative).isEmpty()) {
            // An unconditional e-class cannot retain an undischarged guard.
            return null;
        }
        try {
            Expr rewritten = rule.apply(representative);
            return representative.equals(rewritten) ? null : rewritten;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Collection<EClassId> allClasses(EGraph eGraph) {
        List<EClassId> classes = new ArrayList<>();
        for (EClass eclass : eGraph.classes()) {
            classes.add(eclass.id());
        }
        return classes;
    }

    private static List<EClassId> canonicalSorted(EGraph eGraph, Collection<EClassId> ids) {
        LinkedHashSet<EClassId> canonical = new LinkedHashSet<>();
        for (EClassId id : ids) {
            canonical.add(eGraph.find(id));
        }
        List<EClassId> sorted = new ArrayList<>(canonical);
        sorted.sort(EClassId::compareTo);
        return sorted;
    }

    /** Result of a saturation run: the extracted best AST plus statistics. */
    public record Result(Expr expression, SaturationStats stats) {
        public Result {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(stats, "stats");
        }
    }
}
