package de.regelsuche.egraph;

import de.regelsuche.ast.Expr;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionEvaluation;
import de.regelsuche.transform.AstRewriteTransformationEngine.CancelDivisionFactorRule;
import de.regelsuche.egraph.proof.ExtractionProofBuilder;
import de.regelsuche.egraph.proof.ExtractionProofChecker;
import de.regelsuche.egraph.proof.ProofLimits;
import de.regelsuche.egraph.proof.ProofOutcome;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.transform.EquivalenceAwarePatternMatcher;
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
import java.util.Optional;
import java.util.function.ToIntFunction;
import static de.regelsuche.egraph.SaturationDiagnostics.Outcome.*;
import static de.regelsuche.egraph.SaturationDiagnostics.Stage.*;

/**
 * Bounded equality saturation for unconditional construction-preserving rules.
 * Exact base patterns match natively; subclasses, guarded and AC-aware rules
 * use concrete representatives. Local witnesses are not an extraction proof.
 */
public final class EqualitySaturation {
    // Existing native pattern matcher/applier helpers are recursive.
    private static final int PROOF_NATIVE_MAX_DEPTH = 256;
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
        if (graph.usesArithmeticAc() && !rules.isEmpty()) {
            throw new IllegalArgumentException("Saturation of native AC graphs requires a native multiset matcher");
        }
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(costOfNode, "costOfNode");
        Execution execution = saturateGraph(graph, root, costOfNode, new Run(null));
        Expr best = graph.extract(root, costOfNode);
        return execution.result(best, de.regelsuche.parse.ExpressionFormatter.format(best), ProofOutcome.notRequested());
    }

    /** A local guard decision in the invocation's fixed scalar context. */
    public record GuardDecision(String ruleId, Expr before, Expr after, List<AssumptionEvaluation> evaluations,
            boolean applied) {
        public GuardDecision {
            evaluations = List.copyOf(evaluations);
        }
    }

    /** The expression in result() belongs to this specific immutable assumption context. */
    public record ConditionalResult(Result result, List<Assumption> assumptions, List<GuardDecision> decisions) {
        public ConditionalResult {
            Objects.requireNonNull(result);
            assumptions = List.copyOf(assumptions);
            decisions = List.copyOf(decisions);
        }
    }

    /** Fresh, isolated graph for the built-in scalar factor-cancellation rule and unconditional rules. */
    public ConditionalResult saturateUnderAssumptions(Expr input, List<Assumption> assumptions,
            ToIntFunction<ENode> costOfNode) {
        var guards = new RationalCancellation(assumptions);
        var graph = new EGraph();
        graph.setInsertionLimit(config.maxNodes());
        EClassId root;
        try {
            root = graph.addExpression(Objects.requireNonNull(input));
        } catch (EGraph.NodeBudgetExceeded exhausted) {
            throw new IllegalArgumentException("Input exceeds the configured e-node budget", exhausted);
        }
        var run = new Run(null, guards);
        Execution execution = saturateGraph(graph, root, costOfNode, run);
        Expr best = graph.extract(root, costOfNode);
        var result = execution.result(best, de.regelsuche.parse.ExpressionFormatter.format(best), ProofOutcome.notRequested());
        return new ConditionalResult(result, guards.assumptions, guards.decisions);
    }

    /** Fresh scalar cancellation lane. A changed result is released only after detached guarded replay. */
    public ProvedResult saturateWithCancellationProof(Expr input, List<Assumption> assumptions,
            ToIntFunction<ENode> costOfNode, ProofOptions options) {
        return de.regelsuche.egraph.proof.CancellationProof.run(this, input, assumptions, costOfNode, nativeProofOptions(options));
    }

    private Execution saturateGraph(EGraph graph, EClassId root,
            ToIntFunction<ENode> costOfNode, Run run) {
        // Validate costs before mutation, but allow rewriting to make the root finite.
        graph.find(root);
        graph.validateNodeCosts(costOfNode);
        var matcher = new EGraphPatternMatcher(graph);
        var applier = new EGraphPatternApplier(graph);
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
                    stopReason = run.noProgressReason();
                    saturated = stopReason == SaturationStats.Reason.FIX_POINT;
                    break;
                }
            }
        } finally {
            graph.setInsertionLimit(previousLimit);
        }
        return new Execution(graph, matcher, run, iterations, merges, saturated, stopReason);
    }

    private record Execution(EGraph graph, EGraphPatternMatcher matcher, Run run,
            int iterations, int merges, boolean saturated, SaturationStats.Reason stopReason) {
        Result result(Expr best, String formatted, ProofOutcome proof) {
            var metrics = matcher.stats();
            var stats = new SaturationStats(graph.classCount(), graph.nodeCount(), merges,
                iterations, run.appliedRules, formatted,
                saturated, stopReason, metrics.classesScanned(), metrics.nodesScanned(),
                metrics.candidateClassesSkipped(), metrics.matchesFound(), metrics.matcherCacheHits(),
                metrics.matcherCacheMisses(), iterations, run.applications());
            return new Result(best, stats, run.diagnostics.snapshot(),
                new SaturationProvenance(run.witnesses, run.droppedWitnesses,
                    run.nativeWitnessExtractions, proof));
        }
    }

    /** Separate owned capture/build and receiving-side replay allowances. */
    public record ProofOptions(ProofLimits captureLimits, ProofLimits replayLimits) {
        public ProofOptions {
            Objects.requireNonNull(captureLimits, "captureLimits");
            Objects.requireNonNull(replayLimits, "replayLimits");
        }
        public static ProofOptions defaults() {
            return new ProofOptions(ProofLimits.defaults(), ProofLimits.defaults());
        }
    }

    /** The returned expression changes only after independent exact-endpoint acceptance. */
    public record ProvedResult(Expr expression, Optional<Result> diagnosticResult, ProofOutcome proof) {
        public ProvedResult {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(diagnosticResult, "diagnosticResult");
            Objects.requireNonNull(proof, "proof");
            requireCheckedOutput(expression, proof);
        }
    }

    /**
     * Fresh-graph exact-native proof mode. Unsupported rules or unavailable proof
     * return the original input; diagnostic candidates, when present, are separate.
     */
    public ProvedResult saturateWithProof(Expr input, ToIntFunction<ENode> costOfNode, ProofOptions options) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(costOfNode, "costOfNode");
        Objects.requireNonNull(options, "options");
        options = nativeProofOptions(options);
        ExtractionProofBuilder builder = null;
        int source;
        try {
            builder = new ExtractionProofBuilder(rules, options.captureLimits());
            source = builder.addExpression(input);
        } catch (ExtractionProofBuilder.UnsupportedRule unsupported) {
            return unavailable(input, ProofOutcome.Status.UNSUPPORTED_RULE, unsupported.getMessage(),
                builder == null ? unsupported.workUsed() : builder.workUsed());
        } catch (ExtractionProofBuilder.LimitExceeded exhausted) {
            return unavailable(input, ProofOutcome.Status.LIMIT_EXCEEDED, "preflight: " + exhausted.getMessage(),
                builder == null ? exhausted.workUsed() : builder.workUsed());
        } catch (ExtractionProofBuilder.InvalidInput invalid) {
            return unavailable(input, ProofOutcome.Status.REPLAY_REJECTED, "unsupported proof input",
                builder == null ? invalid.workUsed() : builder.workUsed());
        } catch (IllegalArgumentException invalid) {
            return unavailable(input, ProofOutcome.Status.REPLAY_REJECTED, "unsupported proof input", builder);
        }
        return runProved(input, source, costOfNode, options, builder);
    }

    private static ProofOptions nativeProofOptions(ProofOptions options) {
        ProofLimits limits = options.captureLimits();
        if (limits.maxDepth() <= PROOF_NATIVE_MAX_DEPTH) return options;
        return new ProofOptions(new ProofLimits(limits.maxTerms(), limits.maxProofSteps(),
            limits.maxReferences(), limits.maxCharacters(), limits.maxRationalBits(),
            PROOF_NATIVE_MAX_DEPTH, limits.maxExpandedNodes(), limits.maxWork()), options.replayLimits());
    }

    private ProvedResult runProved(Expr input, int source, ToIntFunction<ENode> costOfNode,
            ProofOptions options, ExtractionProofBuilder builder) {
        var capture = new EGraphProofCapture(builder, options.captureLimits());
        var graph = new EGraph(capture);
        // Unlike the existing-graph API, even initial source insertion belongs to this cap.
        graph.setInsertionLimit(config.maxNodes());
        EClassId root;
        try {
            root = capture.insertInput(graph, source);
        } catch (EGraph.NodeBudgetExceeded exhausted) {
            return unavailable(input, ProofOutcome.Status.EXTRACTION_UNAVAILABLE,
                "input exceeds the graph node budget", builder);
        } catch (ExtractionProofBuilder.LimitExceeded exhausted) {
            return unavailable(input, ProofOutcome.Status.LIMIT_EXCEEDED,
                "input insertion: " + exhausted.getMessage(), builder);
        }
        if (!capture.active()) return new ProvedResult(input, Optional.empty(), capture.unavailable());
        try {
            Execution execution = saturateGraph(graph, root, costOfNode, new Run(capture));
            if (!capture.active()) return new ProvedResult(input, Optional.empty(), capture.unavailable());
            int target = graph.extractProofTerm(root, costOfNode);
            Expr selected = builder.materialize(target);
            String formatted = diagnosticText(capture, selected);
            return checkedResult(input, source, selected, target, formatted, execution, options, builder);
        } catch (ExtractionProofBuilder.LimitExceeded exhausted) {
            return unavailable(input, ProofOutcome.Status.LIMIT_EXCEEDED,
                "extraction/rendering: " + exhausted.getMessage(), builder);
        } catch (IllegalStateException unavailable) {
            return unavailable(input, ProofOutcome.Status.EXTRACTION_UNAVAILABLE,
                "no finite bounded extraction", builder);
        }
    }

    private static String diagnosticText(EGraphProofCapture capture, Expr selected) {
        try {
            return capture.format(selected);
        } catch (IllegalArgumentException notRepresentableByParser) {
            // Exact AST equality can still be checked when text syntax has a smaller numeric envelope.
            return null;
        }
    }

    private ProvedResult checkedResult(Expr input, int source, Expr selected, int target,
            String formatted, Execution execution, ProofOptions options, ExtractionProofBuilder builder) {
        long captureWork = builder.workUsed();
        var built = builder.build(source, target);
        long buildWork = builder.workUsed() - captureWork;
        ProofOutcome proof;
        if (built.status() != ExtractionProofBuilder.BuildStatus.BUILT) {
            var status = built.status() == ExtractionProofBuilder.BuildStatus.LIMIT_EXCEEDED
                ? ProofOutcome.Status.LIMIT_EXCEEDED : ProofOutcome.Status.REPLAY_REJECTED;
            proof = ProofOutcome.unavailable(status, "build: " + built.detail(), captureWork, buildWork, 0);
        } else {
            var checked = ExtractionProofChecker.check(input, selected, built.certificate().orElseThrow(),
                rules, options.replayLimits());
            proof = checkedOutcome(input, selected, checked, captureWork, buildWork);
        }
        Optional<Result> diagnostic = formatted == null ? Optional.empty()
            : Optional.of(execution.result(selected, formatted, proof));
        return new ProvedResult(proof.complete() ? selected : input, diagnostic, proof);
    }

    private static ProofOutcome checkedOutcome(Expr input, Expr selected,
            ExtractionProofChecker.CheckResult checked, long captureWork, long buildWork) {
        if (checked.status() == ExtractionProofChecker.Status.ACCEPTED) {
            var token = checked.checked().orElseThrow();
            if (token.input() != input || token.output() != selected) {
                throw new IllegalStateException("checker token belongs to different invocation endpoints");
            }
            return ProofOutcome.complete(token, captureWork, buildWork);
        }
        var status = checked.status() == ExtractionProofChecker.Status.LIMIT_EXCEEDED
            ? ProofOutcome.Status.LIMIT_EXCEEDED : ProofOutcome.Status.REPLAY_REJECTED;
        return ProofOutcome.unavailable(status, "replay: " + checked.detail(),
            captureWork, buildWork, checked.workUsed());
    }

    private static ProvedResult unavailable(Expr input, ProofOutcome.Status status, String detail,
            ExtractionProofBuilder builder) {
        return unavailable(input, status, detail, builder == null ? 0 : builder.workUsed());
    }

    private static ProvedResult unavailable(Expr input, ProofOutcome.Status status, String detail,
            long captureWork) {
        return new ProvedResult(input, Optional.empty(), ProofOutcome.unavailable(status, detail,
            captureWork, 0, 0));
    }

    private static void requireCheckedOutput(Expr output, ProofOutcome proof) {
        if (proof.complete() && proof.checked().orElseThrow().output() != output) {
            throw new IllegalArgumentException("checked certificate belongs to another output object");
        }
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
            if (run.guards != null && rule instanceof CancelDivisionFactorRule) {
                run.guards.apply(graph, matcher, rule, snapshot, run);
            } else if (rule.getClass() == PatternRewriteRule.class
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
                // Freeze one finite term per binding before any graph mutation.
                var instance = run.capture == null ? null
                    : run.capture.prepare(graph, rule.id(), match.bindings());
                EClassId rhs = applier.instantiate(rule.target(), match.bindings());
                if (graph.areEquivalent(match.root(), rhs)) continue;
                // Proof mode never calls the legacy recursive witness extractor.
                SaturationProvenance.Witness witness = run.capture == null
                    ? nativeWitness(graph, rule, match, run) : null;
                graph.union(match.root(), rhs);
                if (run.capture != null) run.capture.recorded(instance);
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
        run.diagnostics.recordReferenceBridge(rule.id());
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
                applyConcreteRepresentative(graph, rule, source, before, run);
            }
        }
    }

    private void applyConcreteRepresentative(EGraph graph, RewriteRule rule, EClassId source, Expr before, Run run) {
        var stage = MATCHES;
        try {
            EquivalenceAwarePatternMatcher.MatchAttempt attempt = null;
            boolean matched;
            // Only the unmodified base rule has a matcher contract we can interpret directly.
            if (rule.getClass() == PatternRewriteRule.class) {
                var pattern = (PatternRewriteRule) rule;
                attempt = EquivalenceAwarePatternMatcher.matchDetailed(
                    pattern.source(), before, Map.of(), pattern.recognitionProfile());
                // Returned attempts delegate this work; exceptional exits settle it themselves.
                RetainedOperation.work(attempt.visitedBranches());
                if (attempt.inconclusive()) {
                    run.diagnostics.recordInconclusiveMatch(rule.id(), source, attempt.limitCode());
                    return;
                }
                matched = attempt.matched();
            } else {
                matched = rule.matches(before);
            }
            if (!matched) {
                run.diagnostics.record(rule.id(), source, NON_APPLICABLE, stage, null);
                return;
            }
            stage = ASSUMPTIONS;
            if (!rule.assumptions(before).isEmpty()) {
                run.diagnostics.record(rule.id(), source, UNRESOLVED_CONDITIONS, stage, null);
                return;
            }
            stage = APPLY;
            Expr after = Objects.requireNonNull(attempt == null ? rule.apply(before)
                : ((PatternRewriteRule) rule).target().instantiate(attempt.bindings()), "rule returned null");
            if (before.equals(after)) {
                run.diagnostics.record(rule.id(), source, NON_APPLICABLE, stage, null);
                return;
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

    private static Collection<EClassId> allClasses(EGraph graph) {
        return graph.classes().stream().map(EClass::id).toList();
    }
    private static List<EClassId> canonicalSorted(EGraph graph, Collection<EClassId> ids) {
        return ids.stream().map(graph::find).distinct().sorted().toList();
    }
    static final class Run {
        final RationalCancellation guards;
        final EGraphProofCapture capture;
        Run(EGraphProofCapture capture) { this(capture, null); }
        Run(EGraphProofCapture capture, RationalCancellation guards) {
            this.capture = capture;
            this.guards = guards;
        }
        final Map<String, Integer> appliedRules = new LinkedHashMap<>();
        final SaturationDiagnostics.Collector diagnostics = new SaturationDiagnostics.Collector();
        final List<SaturationProvenance.Witness> witnesses = new ArrayList<>();
        long droppedWitnesses;
        long nativeWitnessExtractions;
        int applications() { return appliedRules.values().stream().mapToInt(Integer::intValue).sum(); }
        SaturationStats.Reason noProgressReason() {
            if (diagnostics.hasFailures()) return SaturationStats.Reason.RULE_FAILURES;
            if (diagnostics.hasInconclusiveMatches()) return SaturationStats.Reason.MATCHER_LIMIT;
            if (guards != null && guards.decisionLimit) return SaturationStats.Reason.GUARD_BUDGET;
            if (guards != null && (guards.undecided || diagnostics.hasUnresolvedConditions())) {
                return SaturationStats.Reason.UNDECIDED_GUARDS;
            }
            return diagnostics.hasReferenceBridge()
                ? SaturationStats.Reason.INCOMPLETE_REFERENCE_BRIDGE : SaturationStats.Reason.FIX_POINT;
        }
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
            requireCheckedOutput(expression, provenance.proofOutcome());
        }
        public Result(Expr expression, SaturationStats stats) {
            this(expression, stats, SaturationDiagnostics.empty(), SaturationProvenance.empty());
        }
    }
}
