package de.regelsuche.egraph;

import de.regelsuche.ast.Expr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;
import static de.regelsuche.egraph.SaturationDiagnostics.Outcome.*;
import static de.regelsuche.egraph.SaturationDiagnostics.Stage.*;

/**
 * Bounded equality saturation for unconditional construction-preserving rules.
 * Exact base patterns match natively; subclasses, guarded and AC-aware rules
 * use concrete representatives. Local witnesses are not an extraction proof.
 */
public final class EqualitySaturation {
    public record Config(int maxIterations, int maxNodes, boolean useDirtyWorklist) {
        public Config {
            if (maxIterations <= 0) throw new IllegalArgumentException("maxIterations must be > 0");
            if (maxNodes <= 0) throw new IllegalArgumentException("maxNodes must be > 0");
        }
        public Config(int maxIterations, int maxNodes) { this(maxIterations, maxNodes, true); }
        public static Config defaults() { return new Config(12, 10_000, true); }
    }

    private final List<RewriteRule> rules;
    private final Config config;
    public EqualitySaturation(Collection<RewriteRule> rules) { this(rules, Config.defaults()); }
    public EqualitySaturation(Collection<RewriteRule> rules, Config config) {
        this.rules = List.copyOf(rules);
        this.config = Objects.requireNonNull(config, "config");
    }
    public List<RewriteRule> rules() { return rules; }
    public Config config() { return config; }

    /** Exact extraction for additive, non-negative per-node costs. */
    public Result saturate(EGraph graph, EClassId root, ToIntFunction<ENode> costOfNode) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(costOfNode, "costOfNode");
        // Validate costs before mutation, but allow rewriting to make the root finite.
        graph.find(root);
        graph.validateNodeCosts(costOfNode);
        var matcher = new EGraphPatternMatcher(graph);
        var applier = new EGraphPatternApplier(graph);
        var run = new Run();
        int iterations = 0;
        int merges = 0;
        boolean saturated = false;
        var stopReason = SaturationStats.Reason.ITERATION_BUDGET;
        Collection<EClassId> dirty = graph.consumeDirtyClasses();
        if (dirty.isEmpty()) dirty = allClasses(graph);
        int previousLimit = graph.setInsertionLimit(config.maxNodes());
        try {
            for (int i = 0; i < config.maxIterations(); i++) {
                iterations = i + 1;
                if (graph.nodeCount() >= config.maxNodes()) {
                    stopReason = SaturationStats.Reason.NODE_BUDGET;
                    break;
                }
                long versionBefore = graph.version();
                int applicationsBefore = run.applications();
                boolean budgetStop = false;
                try {
                    applyAllRules(graph, matcher, applier, run,
                        config.useDirtyWorklist() ? dirty : allClasses(graph));
                } catch (EGraph.NodeBudgetExceeded exhausted) {
                    budgetStop = true;
                } finally {
                    // Even partial passes have pending unions. Repair before returning.
                    graph.rebuild();
                }
                merges += run.applications() - applicationsBefore;
                dirty = graph.consumeDirtyClasses();
                if (budgetStop || graph.nodeCount() >= config.maxNodes()) {
                    stopReason = SaturationStats.Reason.NODE_BUDGET;
                    break;
                }
                if (graph.version() == versionBefore || run.applications() == applicationsBefore) {
                    saturated = !run.diagnostics.hasFailures();
                    stopReason = saturated ? SaturationStats.Reason.FIX_POINT : SaturationStats.Reason.RULE_FAILURES;
                    break;
                }
            }
        } finally {
            graph.setInsertionLimit(previousLimit);
        }
        Expr best = graph.extract(root, costOfNode);
        var metrics = matcher.stats();
        var stats = new SaturationStats(graph.classCount(), graph.nodeCount(), merges,
            iterations, run.appliedRules, de.regelsuche.parse.ExpressionFormatter.format(best),
            saturated, stopReason, metrics.classesScanned(), metrics.nodesScanned(),
            metrics.candidateClassesSkipped(), metrics.matchesFound(), metrics.matcherCacheHits(),
            metrics.matcherCacheMisses(), iterations, run.applications());
        return new Result(best, stats, run.diagnostics.snapshot(),
            new SaturationProvenance(run.witnesses, run.droppedWitnesses, run.nativeWitnessExtractions));
    }

    private void checkBudget(EGraph graph) {
        if (graph.nodeCount() >= config.maxNodes()) throw new EGraph.NodeBudgetExceeded();
    }

    private void applyAllRules(EGraph graph, EGraphPatternMatcher matcher, EGraphPatternApplier applier,
            Run run, Collection<EClassId> candidates) {
        List<EClassId> snapshot = canonicalSorted(graph, candidates);
        for (RewriteRule rule : rules) {
            checkBudget(graph);
            try {
                if (!rule.isEquivalencePreservingByConstruction()) {
                    run.diagnostics.record(rule.id(), null, NOT_EQUIVALENCE_PRESERVING, ELIGIBILITY, null);
                    continue;
                }
            } catch (RuntimeException failure) {
                run.diagnostics.record(rule.id(), null, RULE_FAILURE, ELIGIBILITY, failure);
                continue;
            }
            if (rule.getClass() == PatternRewriteRule.class
                    && ((PatternRewriteRule) rule).recognitionProfile().equals(RecognitionProfile.exact())) {
                applyPattern(graph, matcher, applier, (PatternRewriteRule) rule, snapshot, run);
            } else {
                applyConcrete(graph, rule, snapshot, run);
            }
        }
    }

    private void applyPattern(EGraph graph, EGraphPatternMatcher matcher, EGraphPatternApplier applier,
            PatternRewriteRule rule, List<EClassId> snapshot, Run run) {
        List<EGraphPatternMatcher.Match> matches;
        try {
            matches = matcher.matchAll(rule.id(), rule.source(), snapshot);
        } catch (RuntimeException failure) {
            run.diagnostics.record(rule.id(), null, RULE_FAILURE, MATCHES, failure);
            return;
        }
        if (matches.isEmpty()) run.diagnostics.record(rule.id(), null, NON_APPLICABLE, MATCHES, null);
        for (var match : matches) {
            checkBudget(graph);
            try {
                EClassId rhs = applier.instantiate(rule.target(), match.bindings());
                if (graph.areEquivalent(match.root(), rhs)) continue;
                // Only new equalities need witnesses. Explanation work never gates the union.
                SaturationProvenance.Witness witness = nativeWitness(graph, rule, match, run);
                graph.union(match.root(), rhs);
                run.applied(rule.id(), witness);
            } catch (EGraph.NodeBudgetExceeded exhausted) {
                throw exhausted;
            } catch (RuntimeException failure) {
                run.diagnostics.record(rule.id(), match.root(), RULE_FAILURE, INSERTION, failure);
            }
        }
    }

    private SaturationProvenance.Witness nativeWitness(EGraph graph, PatternRewriteRule rule,
            EGraphPatternMatcher.Match match, Run run) {
        if (run.witnesses.size() >= SaturationProvenance.MAX_WITNESSES) return null;
        try {
            Map<String, Expr> bindings = new LinkedHashMap<>();
            for (var binding : match.bindings().entrySet()) {
                run.nativeWitnessExtractions++;
                // A witness needs finite syntax, independently of the optimization objective.
                bindings.put(binding.getKey(), graph.extract(binding.getValue(), node -> 1));
            }
            return new SaturationProvenance.Witness(rule.id(),
                rule.source().instantiate(bindings), rule.target().instantiate(bindings));
        } catch (RuntimeException failure) {
            run.diagnostics.record(rule.id(), match.root(), RULE_FAILURE, EXTRACTION, failure);
            return null;
        }
    }

    private void applyConcrete(EGraph graph, RewriteRule rule, List<EClassId> snapshot, Run run) {
        var seen = new LinkedHashSet<EClassId>();
        for (EClassId raw : snapshot) {
            checkBudget(graph);
            EClassId source = graph.find(raw);
            if (!seen.add(source)) continue;
            List<Expr> representatives;
            try {
                // The optimization objective ranks results; it must not hide applicable rules.
                representatives = graph.representatives(source, node -> 1);
                if (representatives.isEmpty()) {
                    run.diagnostics.record(rule.id(), source, NON_APPLICABLE, EXTRACTION, null);
                }
            } catch (RuntimeException failure) {
                run.diagnostics.record(rule.id(), source, RULE_FAILURE, EXTRACTION, failure);
                continue;
            }
            for (Expr before : representatives) {
                checkBudget(graph);
                var stage = MATCHES;
                try {
                    if (!rule.matches(before)) {
                        run.diagnostics.record(rule.id(), source, NON_APPLICABLE, stage, null);
                        continue;
                    }
                    stage = ASSUMPTIONS;
                    if (!rule.assumptions(before).isEmpty()) {
                        run.diagnostics.record(rule.id(), source, UNRESOLVED_CONDITIONS, stage, null);
                        continue;
                    }
                    stage = APPLY;
                    Expr after = Objects.requireNonNull(rule.apply(before), "rule returned null");
                    if (before.equals(after)) {
                        run.diagnostics.record(rule.id(), source, NON_APPLICABLE, stage, null);
                        continue;
                    }
                    stage = INSERTION;
                    EClassId rhs = graph.addExpression(after);
                    if (!graph.areEquivalent(source, rhs)) {
                        graph.union(source, rhs);
                        run.applied(rule.id(), new SaturationProvenance.Witness(rule.id(), before, after));
                    }
                } catch (EGraph.NodeBudgetExceeded exhausted) {
                    throw exhausted;
                } catch (RuntimeException failure) {
                    run.diagnostics.record(rule.id(), source, RULE_FAILURE, stage, failure);
                }
            }
        }
    }

    private static Collection<EClassId> allClasses(EGraph graph) {
        return graph.classes().stream().map(EClass::id).toList();
    }
    private static List<EClassId> canonicalSorted(EGraph graph, Collection<EClassId> ids) {
        return ids.stream().map(graph::find).distinct().sorted().toList();
    }
    private static final class Run {
        final Map<String, Integer> appliedRules = new LinkedHashMap<>();
        final SaturationDiagnostics.Collector diagnostics = new SaturationDiagnostics.Collector();
        final List<SaturationProvenance.Witness> witnesses = new ArrayList<>();
        long droppedWitnesses;
        long nativeWitnessExtractions;
        int applications() { return appliedRules.values().stream().mapToInt(Integer::intValue).sum(); }
        void applied(String rule, SaturationProvenance.Witness witness) {
            appliedRules.merge(rule, 1, Integer::sum);
            if (witnesses.size() < SaturationProvenance.MAX_WITNESSES && witness != null) witnesses.add(witness);
            else droppedWitnesses++;
        }
    }

    public record Result(Expr expression, SaturationStats stats,
            SaturationDiagnostics diagnostics, SaturationProvenance provenance) {
        public Result {
            Objects.requireNonNull(expression);
            Objects.requireNonNull(stats);
            Objects.requireNonNull(diagnostics);
            Objects.requireNonNull(provenance);
        }
        public Result(Expr expression, SaturationStats stats) {
            this(expression, stats, SaturationDiagnostics.empty(), SaturationProvenance.empty());
        }
    }
}
