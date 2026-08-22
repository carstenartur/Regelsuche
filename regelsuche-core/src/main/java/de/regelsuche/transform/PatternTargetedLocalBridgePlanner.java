package de.regelsuche.transform;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.PatternTargetedLocalBridgeEvidence.BridgeStep;
import de.regelsuche.transform.PatternTargetedLocalBridgeEvidence.Budget;
import de.regelsuche.transform.PatternTargetedLocalBridgeEvidence.PlanAttempt;
import de.regelsuche.transform.PatternTargetedLocalBridgeEvidence.PreparedBridge;
import de.regelsuche.transform.PatternTargetedLocalBridgeEvidence.Status;
import de.regelsuche.transform.PatternTargetedLocalBridgeEvidence.WorkLedger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Finds a bounded local rewrite bridge whose only terminal predicate is that
 * one declared visible principal rule becomes concretely applicable.
 *
 * <p>The planner never receives a desired result expression. It first asks the
 * ordinary principal implementation for a direct replay. Only after that fails
 * does it enumerate the explicitly supplied, equivalence-preserving
 * preparation inventory breadth first. Every admitted state is distinguished
 * by its exact parsed AST structure and normalized assumptions. A declarative
 * full match is accepted only when the concrete principal implementation
 * replays on the retained terminal AST and produces the retained result.</p>
 *
 * <p>The first revision deliberately supports only principal rules with an
 * explicit {@link PatternRewriteRule} applicability schema. Algorithmic Java
 * rules remain visible as {@link Status#UNSUPPORTED}; no schema is inferred
 * from an ID, implementation class or example.</p>
 */
public final class PatternTargetedLocalBridgePlanner {
    public static final String PLANNER_ID =
        "regelsuche.pattern-targeted-local-bridge/v1";

    private static final Comparator<Transformation> TRANSFORMATION_ORDER =
        Comparator.comparing(Transformation::transformedExpression)
            .thenComparing(Transformation::rule)
            .thenComparing(Transformation::applicationKey)
            .thenComparing(transformation ->
                String.join("\u0000", transformation.assumptions()))
            .thenComparing(transformation ->
                String.join("\u0000", transformation.primitiveRuleIds()));

    private static final Comparator<Candidate> CANDIDATE_ORDER =
        Comparator.comparingInt(PatternTargetedLocalBridgePlanner::matchRank)
            .thenComparing(Comparator.comparingInt(
                (Candidate candidate) ->
                    candidate.analysis().matchedPatternNodes()).reversed())
            .thenComparing(Comparator.comparingInt(
                (Candidate candidate) ->
                    candidate.analysis().bindings().size()).reversed())
            .thenComparingInt(candidate ->
                candidate.analysis().residualObligations().size())
            .thenComparingInt(Candidate::astGrowth)
            .thenComparingInt(Candidate::primitiveWork)
            .thenComparing(candidate -> candidate.successor().structureHash())
            .thenComparing(candidate -> candidate.parent().structureHash())
            .thenComparing(candidate -> candidate.transformation().rule())
            .thenComparing(candidate ->
                candidate.transformation().applicationKey());

    private final RewriteRule declaredPrincipalRule;
    private final PatternRewriteRule principalRule;
    private final TransformationEngine preparationEngine;
    private final String principalRuleHash;
    private final String preparationInventoryHash;
    private final PatternMatchAnalyzer analyzer = new PatternMatchAnalyzer();
    private final ExpressionParser parser = new ExpressionParser();

    /**
     * Creates a planner from concrete rules. The principal rule is always
     * excluded from the preparation inventory, even if supplied a second time.
     */
    public PatternTargetedLocalBridgePlanner(
        RewriteRule principalRule,
        List<? extends RewriteRule> preparationRules
    ) {
        this.declaredPrincipalRule = Objects.requireNonNull(
            principalRule,
            "principalRule");
        this.principalRule = principalRule instanceof PatternRewriteRule pattern
            ? pattern : null;
        List<RewriteRule> safeRules = safePreparationRules(
            principalRule,
            preparationRules);
        this.preparationEngine = new AstRewriteTransformationEngine(
            safeRules,
            1_024,
            1_024);
        this.principalRuleHash = RuleInventoryFingerprint.ruleContentHash(
            principalRule);
        this.preparationInventoryHash =
            RuleInventoryFingerprint.contentHash(safeRules);
    }

    /**
     * Injection boundary for synthetic characterization and future shared
     * coordinators. The identity must bind the exact frozen preparation engine.
     */
    public PatternTargetedLocalBridgePlanner(
        RewriteRule principalRule,
        TransformationEngine preparationEngine,
        String preparationInventoryHash
    ) {
        this.declaredPrincipalRule = Objects.requireNonNull(
            principalRule,
            "principalRule");
        this.principalRule = principalRule instanceof PatternRewriteRule pattern
            ? pattern : null;
        this.preparationEngine = Objects.requireNonNull(
            preparationEngine,
            "preparationEngine");
        this.principalRuleHash = RuleInventoryFingerprint.ruleContentHash(
            principalRule);
        this.preparationInventoryHash =
            PatternTargetedLocalBridgeEvidence.requireHash(
                preparationInventoryHash,
                "preparationInventoryHash");
    }

    public String principalRuleHash() {
        return principalRuleHash;
    }

    public String preparationInventoryHash() {
        return preparationInventoryHash;
    }

    /** Plans a direct or prepared application without receiving a target. */
    public PlanAttempt plan(
        String sourceExpression,
        AssumptionSignature initialAssumptions,
        Budget budget
    ) {
        Source source = parseSource(sourceExpression);
        AssumptionSignature assumptions = normalizeSignature(
            initialAssumptions);
        Budget safeBudget = Objects.requireNonNull(budget, "budget");
        MutableWork work = new MutableWork();
        work.maxFrontierSize = 1;

        if (principalRule == null) {
            return attempt(
                Status.UNSUPPORTED,
                source.expression(),
                assumptions,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                work.snapshot(safeBudget, 1),
                "PRINCIPAL_RULE_HAS_NO_DECLARATIVE_SCHEMA");
        }

        PatternMatchAnalyzer.Analysis initialAnalysis;
        try {
            initialAnalysis = analyze(source.ast(), safeBudget, work);
        } catch (RuntimeException exception) {
            work.technicalFailures++;
            work.technicalFailureReached = true;
            return attempt(
                Status.BUDGET_INCONCLUSIVE,
                source.expression(),
                assumptions,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                work.snapshot(safeBudget, 1),
                "INITIAL_MATCH_ANALYSIS_FAILED");
        }
        if (initialAnalysis.inconclusive()) {
            work.matchInconclusive = true;
            return attempt(
                Status.BUDGET_INCONCLUSIVE,
                source.expression(),
                assumptions,
                Optional.of(initialAnalysis),
                Optional.empty(),
                Optional.empty(),
                work.snapshot(safeBudget, 1),
                "INITIAL_MATCH_ANALYSIS_INCONCLUSIVE");
        }

        if (initialAnalysis.matched()) {
            Transformation replay = replayPrincipal(source.ast());
            if (replay == null) {
                return attempt(
                    Status.INVALID_CERTIFICATE,
                    source.expression(),
                    assumptions,
                    Optional.of(initialAnalysis),
                    Optional.empty(),
                    Optional.empty(),
                    work.snapshot(safeBudget, 1),
                    "DECLARATIVE_DIRECT_MATCH_FAILED_CONCRETE_REPLAY");
            }
            return attempt(
                Status.DIRECT_MATCH_AVAILABLE,
                source.expression(),
                assumptions,
                Optional.of(initialAnalysis),
                Optional.of(replay),
                Optional.empty(),
                work.snapshot(safeBudget, 1),
                "CONCRETE_DIRECT_PRINCIPAL_REPLAY_AVAILABLE");
        }

        Node sourceNode = new Node(
            source.expression(),
            source.ast(),
            source.structureHash(),
            nodeCount(source.ast()),
            assumptions,
            0,
            0,
            null,
            null,
            initialAnalysis);
        Set<StateKey> visited = new LinkedHashSet<>();
        visited.add(sourceNode.stateKey());
        List<Node> layer = List.of(sourceNode);

        while (!layer.isEmpty()) {
            work.maxFrontierSize = Math.max(
                work.maxFrontierSize,
                layer.size());
            List<Candidate> candidates = new ArrayList<>();
            for (Node node : layer) {
                work.expandedStates++;
                collectCandidates(
                    node,
                    safeBudget,
                    work,
                    candidates);
                if (work.generatedTransitionLimitReached) {
                    break;
                }
            }
            candidates.sort(CANDIDATE_ORDER);

            List<Node> nextLayer = new ArrayList<>();
            for (int index = 0; index < candidates.size(); index++) {
                Candidate candidate = candidates.get(index);
                StateKey key = candidate.successor().stateKey();
                if (visited.contains(key)) {
                    work.duplicateTransitions++;
                    continue;
                }
                if (candidate.parent().depth() >= safeBudget.maxDepth()) {
                    work.depthLimitTransitions++;
                    work.depthLimitReached = true;
                    continue;
                }
                if (visited.size() >= safeBudget.maxVisitedStates()) {
                    work.visitedLimitTransitions++;
                    work.visitedStateLimitReached = true;
                    continue;
                }

                Node successor = candidate.successor();
                visited.add(key);
                work.admittedTransitions++;
                if (candidate.analysis().inconclusive()) {
                    work.matchInconclusive = true;
                }

                if (candidate.analysis().matched()) {
                    Transformation principalReplay = replayPrincipal(
                        successor.ast());
                    if (principalReplay == null) {
                        work.terminalSelectionTransitions +=
                            candidates.size() - index - 1;
                        return attempt(
                            Status.INVALID_CERTIFICATE,
                            source.expression(),
                            assumptions,
                            Optional.of(initialAnalysis),
                            Optional.empty(),
                            Optional.empty(),
                            work.snapshot(safeBudget, visited.size()),
                            "TERMINAL_DECLARATIVE_MATCH_FAILED_CONCRETE_REPLAY");
                    }
                    work.terminalSelectionTransitions +=
                        candidates.size() - index - 1;
                    WorkLedger ledger = work.snapshot(
                        safeBudget,
                        visited.size());
                    PreparedBridge bridge = bridge(
                        sourceNode,
                        successor,
                        initialAnalysis,
                        candidate.analysis(),
                        principalReplay,
                        safeBudget,
                        ledger);
                    return attempt(
                        Status.PREPARED,
                        source.expression(),
                        assumptions,
                        Optional.of(initialAnalysis),
                        Optional.empty(),
                        Optional.of(bridge),
                        ledger,
                        "SHORTEST_BOUNDED_PREPARATION_BRIDGE_REPLAYED");
                }
                nextLayer.add(successor);
            }
            layer = List.copyOf(nextLayer);
        }

        WorkLedger ledger = work.snapshot(safeBudget, visited.size());
        if (ledger.inconclusive()) {
            return attempt(
                Status.BUDGET_INCONCLUSIVE,
                source.expression(),
                assumptions,
                Optional.of(initialAnalysis),
                Optional.empty(),
                Optional.empty(),
                ledger,
                ledger.technicalFailures() > 0
                        || ledger.technicalFailureTransitions() > 0
                    ? "PREPARATION_ENGINE_OR_MATCH_ANALYSIS_FAILURE"
                    : "LOCAL_BRIDGE_WORK_BUDGET_INCONCLUSIVE");
        }
        return attempt(
            Status.NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
            source.expression(),
            assumptions,
            Optional.of(initialAnalysis),
            Optional.empty(),
            Optional.empty(),
            ledger,
            "NO_PRINCIPAL_MATCH_IN_COMPLETE_FROZEN_LOCAL_CLOSURE");
    }

    /**
     * Recomputes the deterministic search and rejects changed paths,
     * assumptions, inventories, work or certificates.
     */
    public boolean verify(PreparedBridge bridge) {
        if (bridge == null
                || !PLANNER_ID.equals(bridge.plannerId())
                || !principalRuleHash.equals(bridge.principalRuleHash())
                || !preparationInventoryHash.equals(
                    bridge.preparationInventoryHash())
                || !bridge.certificateHash().equals(certificateHash(bridge))) {
            return false;
        }
        try {
            PlanAttempt recomputed = plan(
                bridge.sourceExpression(),
                bridge.initialAssumptions(),
                bridge.budget());
            return recomputed.status() == Status.PREPARED
                && recomputed.preparedBridge().orElseThrow().equals(bridge);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void collectCandidates(
        Node parent,
        Budget budget,
        MutableWork work,
        List<Candidate> target
    ) {
        List<Transformation> transformations;
        try {
            transformations = new ArrayList<>(
                preparationEngine.transform(parent.expression()));
        } catch (RuntimeException exception) {
            work.technicalFailures++;
            work.technicalFailureReached = true;
            return;
        }
        transformations.sort(TRANSFORMATION_ORDER);
        List<Candidate> local = new ArrayList<>();
        for (Transformation transformation : transformations) {
            if (work.generatedTransitions
                    >= budget.maxGeneratedTransitions()) {
                work.generatedTransitionLimitReached = true;
                break;
            }
            work.generatedTransitions++;
            if (declaredPrincipalRule.id().equals(transformation.rule())) {
                work.principalRuleTransitions++;
                continue;
            }
            if (!transformation.equivalencePreservingByConstruction()) {
                work.unsafeTransitions++;
                continue;
            }

            int primitiveWork;
            try {
                primitiveWork = Math.addExact(
                    parent.primitiveWork(),
                    transformation.primitiveStepCount());
            } catch (ArithmeticException exception) {
                work.primitiveLimitTransitions++;
                work.primitiveWorkLimitReached = true;
                continue;
            }
            if (primitiveWork > budget.maxPrimitiveSteps()) {
                work.primitiveLimitTransitions++;
                work.primitiveWorkLimitReached = true;
                continue;
            }

            Source parsed;
            try {
                parsed = parseSource(
                    transformation.transformedExpression());
            } catch (RuntimeException exception) {
                work.technicalFailureTransitions++;
                work.technicalFailureReached = true;
                continue;
            }
            int nodes = nodeCount(parsed.ast());
            if (nodes > budget.maxExpressionNodes()) {
                work.expressionLimitTransitions++;
                work.expressionNodeLimitReached = true;
                continue;
            }

            AssumptionSignature successorAssumptions =
                AssumptionSignature.merge(
                    parent.assumptions(),
                    AssumptionSignature.ofExpressions(
                        transformation.assumptions()));
            PatternMatchAnalyzer.Analysis analysis;
            try {
                analysis = analyze(parsed.ast(), budget, work);
            } catch (RuntimeException exception) {
                work.technicalFailureTransitions++;
                work.technicalFailureReached = true;
                continue;
            }
            BridgeStep incoming = new BridgeStep(
                parent.expression(),
                parsed.expression(),
                transformation.rule(),
                transformation.applicationKey(),
                transformation.assumptions(),
                successorAssumptions,
                transformation.packId(),
                transformation.license(),
                transformation.primitiveRuleIds());
            Node successor = new Node(
                parsed.expression(),
                parsed.ast(),
                parsed.structureHash(),
                nodes,
                successorAssumptions,
                parent.depth() + 1,
                primitiveWork,
                parent,
                incoming,
                analysis);
            local.add(new Candidate(
                parent,
                successor,
                transformation,
                analysis,
                Math.max(0, nodes - parent.nodeCount()),
                primitiveWork));
        }
        local.sort(CANDIDATE_ORDER);
        int retained = Math.min(
            local.size(),
            budget.maxSuccessorsPerState());
        target.addAll(local.subList(0, retained));
        if (retained < local.size()) {
            work.successorLimitReached = true;
            work.successorLimitTransitions += local.size() - retained;
        }
    }

    private PatternMatchAnalyzer.Analysis analyze(
        Expr expression,
        Budget budget,
        MutableWork work
    ) {
        work.matchAnalyses++;
        return analyzer.analyze(
            principalRule.source(),
            expression,
            principalRule.recognitionProfile(),
            new ExprMatcher.MatchOptions(
                EquivalentExpressionProvider.identity(),
                64,
                budget.maxMatchSteps(),
                budget.maxPatternBranches()));
    }

    private Transformation replayPrincipal(Expr expression) {
        try {
            if (!principalRule.matches(expression)) {
                return null;
            }
            Expr result = principalRule.apply(expression);
            if (result.equals(expression)) {
                return null;
            }
            List<String> assumptions = principalRule.assumptions(expression)
                .stream()
                .map(Assumption::expression)
                .toList();
            return new Transformation(
                principalRule.id(),
                ExpressionFormatter.format(result),
                principalRule.kind(),
                principalRule.mayIncreaseComplexity(),
                principalRule.estimatedCostDelta(),
                principalRule.isEquivalencePreservingByConstruction(),
                principalRule.id() + ":" + structureHash(expression),
                assumptions,
                principalRule.descriptor().packId(),
                principalRule.descriptor().license(),
                List.of(principalRule.id()));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private PreparedBridge bridge(
        Node source,
        Node terminal,
        PatternMatchAnalyzer.Analysis initialAnalysis,
        PatternMatchAnalyzer.Analysis terminalAnalysis,
        Transformation principalReplay,
        Budget budget,
        WorkLedger work
    ) {
        List<BridgeStep> steps = reconstruct(terminal);
        AssumptionSignature finalAssumptions = AssumptionSignature.merge(
            terminal.assumptions(),
            AssumptionSignature.ofExpressions(
                principalReplay.assumptions()));
        PreparedBridge provisional = new PreparedBridge(
            PLANNER_ID,
            source.expression(),
            terminal.expression(),
            principalReplay.transformedExpression(),
            source.assumptions(),
            finalAssumptions,
            declaredPrincipalRule.id(),
            principalRuleHash,
            preparationInventoryHash,
            budget,
            initialAnalysis,
            terminalAnalysis,
            steps,
            principalReplay,
            work,
            "sha256:" + "0".repeat(64));
        return new PreparedBridge(
            provisional.plannerId(),
            provisional.sourceExpression(),
            provisional.terminalPreparedExpression(),
            provisional.resultExpression(),
            provisional.initialAssumptions(),
            provisional.finalAssumptions(),
            provisional.principalRuleId(),
            provisional.principalRuleHash(),
            provisional.preparationInventoryHash(),
            provisional.budget(),
            provisional.initialAnalysis(),
            provisional.terminalAnalysis(),
            provisional.preparationSteps(),
            provisional.principalReplay(),
            provisional.work(),
            certificateHash(provisional));
    }

    private static List<BridgeStep> reconstruct(Node terminal) {
        ArrayDeque<BridgeStep> reversed = new ArrayDeque<>();
        Node current = terminal;
        while (current.parent() != null) {
            reversed.addFirst(current.incoming());
            current = current.parent();
        }
        return List.copyOf(reversed);
    }

    private static int matchRank(Candidate candidate) {
        PatternMatchAnalyzer.Analysis analysis = candidate.analysis();
        if (analysis.matched()) {
            return 0;
        }
        if (analysis.residual()) {
            return 1;
        }
        if (analysis.inconclusive()) {
            return 3;
        }
        return 2;
    }

    private Source parseSource(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException(
                "source expression must not be blank");
        }
        Expr ast = parser.parseTerm(expression.trim());
        return new Source(
            ExpressionFormatter.format(ast),
            ast,
            structureHash(ast));
    }

    private static AssumptionSignature normalizeSignature(
        AssumptionSignature signature
    ) {
        AssumptionSignature supplied = signature == null
            ? AssumptionSignature.ofExpressions(List.of())
            : signature;
        return AssumptionSignature.ofExpressions(
            supplied.normalizedAssumptions());
    }

    private static List<RewriteRule> safePreparationRules(
        RewriteRule principal,
        List<? extends RewriteRule> rules
    ) {
        Objects.requireNonNull(rules, "preparationRules");
        List<RewriteRule> retained = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (RewriteRule rule : rules) {
            RewriteRule safeRule = Objects.requireNonNull(
                rule,
                "preparation rule");
            if (principal.id().equals(safeRule.id())) {
                continue;
            }
            if (!safeRule.isEquivalencePreservingByConstruction()) {
                throw new IllegalArgumentException(
                    "preparation rule is not equivalence preserving: "
                        + safeRule.id());
            }
            if (!ids.add(safeRule.id())) {
                throw new IllegalArgumentException(
                    "duplicate preparation rule id: " + safeRule.id());
            }
            retained.add(safeRule);
        }
        return List.copyOf(retained);
    }

    private static int nodeCount(Expr root) {
        int count = 0;
        ArrayDeque<Expr> pending = new ArrayDeque<>();
        pending.addLast(root);
        while (!pending.isEmpty()) {
            if (count == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("expression is too large");
            }
            Expr current = pending.removeLast();
            count++;
            if (current instanceof BinaryExpr binary) {
                pending.addLast(binary.left());
                pending.addLast(binary.right());
            } else if (current instanceof FunctionExpr function) {
                function.arguments().forEach(pending::addLast);
            }
        }
        return count;
    }

    private static String structureHash(Expr expression) {
        StringBuilder descriptor = new StringBuilder();
        appendExpression(descriptor, expression);
        return sha256(descriptor.toString());
    }

    private static void appendExpression(
        StringBuilder descriptor,
        Expr expression
    ) {
        if (expression instanceof NumberExpr number) {
            appendToken(descriptor, "number");
            appendToken(descriptor, Long.toHexString(
                Double.doubleToLongBits(number.value())));
        } else if (expression instanceof VariableExpr variable) {
            appendToken(descriptor, "variable");
            appendToken(descriptor, variable.name());
        } else if (expression instanceof BinaryExpr binary) {
            appendToken(descriptor, "binary");
            appendToken(descriptor, binary.operator().name());
            appendExpression(descriptor, binary.left());
            appendExpression(descriptor, binary.right());
        } else if (expression instanceof FunctionExpr function) {
            appendToken(descriptor, "function");
            appendToken(descriptor, function.name());
            appendToken(descriptor, Integer.toString(
                function.arguments().size()));
            function.arguments().forEach(argument ->
                appendExpression(descriptor, argument));
        } else {
            throw new IllegalArgumentException(
                "unsupported expression type: "
                    + expression.getClass().getName());
        }
    }

    private static String certificateHash(PreparedBridge bridge) {
        StringBuilder value = new StringBuilder();
        appendToken(value, bridge.plannerId());
        appendToken(value, bridge.sourceExpression());
        appendToken(value, bridge.terminalPreparedExpression());
        appendToken(value, bridge.resultExpression());
        appendToken(value, bridge.initialAssumptions().fingerprint());
        appendToken(value, bridge.finalAssumptions().fingerprint());
        appendToken(value, bridge.principalRuleId());
        appendToken(value, bridge.principalRuleHash());
        appendToken(value, bridge.preparationInventoryHash());
        appendBudget(value, bridge.budget());
        appendAnalysis(value, bridge.initialAnalysis());
        appendAnalysis(value, bridge.terminalAnalysis());
        appendToken(value, Integer.toString(
            bridge.preparationSteps().size()));
        bridge.preparationSteps().forEach(step -> appendStep(value, step));
        appendTransformation(value, bridge.principalReplay());
        appendWork(value, bridge.work());
        return sha256(value.toString());
    }

    private static void appendBudget(StringBuilder value, Budget budget) {
        appendToken(value, Integer.toString(budget.maxDepth()));
        appendToken(value, Integer.toString(budget.maxVisitedStates()));
        appendToken(value, Integer.toString(
            budget.maxGeneratedTransitions()));
        appendToken(value, Integer.toString(budget.maxPrimitiveSteps()));
        appendToken(value, Integer.toString(budget.maxExpressionNodes()));
        appendToken(value, Integer.toString(
            budget.maxSuccessorsPerState()));
        appendToken(value, Integer.toString(budget.maxMatchSteps()));
        appendToken(value, Integer.toString(budget.maxPatternBranches()));
    }

    private static void appendAnalysis(
        StringBuilder value,
        PatternMatchAnalyzer.Analysis analysis
    ) {
        appendToken(value, analysis.status().name());
        appendToken(value, analysis.detailCode());
        appendToken(value, Integer.toString(analysis.evaluatedSteps()));
        appendToken(value, Integer.toString(analysis.patternBranches()));
        appendToken(value, Integer.toString(
            analysis.structuralComparisons()));
        appendToken(value, Integer.toString(
            analysis.matchedPatternNodes()));
        appendToken(value, Integer.toString(analysis.totalPatternNodes()));
        analysis.bindings().forEach((name, expression) -> {
            appendToken(value, name);
            appendToken(value, structureHash(expression));
        });
        analysis.residualObligations().forEach(obligation -> {
            appendToken(value, obligation.kind().name());
            appendToken(value, obligation.path());
            appendToken(value, structureHash(
                obligation.actualExpression()));
        });
    }

    private static void appendStep(StringBuilder value, BridgeStep step) {
        appendToken(value, step.expressionBefore());
        appendToken(value, step.expressionAfter());
        appendToken(value, step.ruleId());
        appendToken(value, step.applicationKey());
        appendToken(value, step.packId());
        appendToken(value, step.license());
        appendToken(value, step.resultingAssumptions().fingerprint());
        step.emittedAssumptions().forEach(assumption ->
            appendToken(value, assumption));
        step.primitiveRuleIds().forEach(id -> appendToken(value, id));
    }

    private static void appendTransformation(
        StringBuilder value,
        Transformation transformation
    ) {
        appendToken(value, transformation.rule());
        appendToken(value, transformation.transformedExpression());
        appendToken(value, transformation.kind().name());
        appendToken(value, transformation.applicationKey());
        appendToken(value, transformation.packId());
        appendToken(value, transformation.license());
        transformation.assumptions().forEach(assumption ->
            appendToken(value, assumption));
        transformation.primitiveRuleIds().forEach(id ->
            appendToken(value, id));
    }

    private static void appendWork(
        StringBuilder value,
        WorkLedger work
    ) {
        appendToken(value, Integer.toString(work.expandedStates()));
        appendToken(value, Integer.toString(work.visitedStates()));
        appendToken(value, Integer.toString(work.generatedTransitions()));
        appendToken(value, Integer.toString(work.admittedTransitions()));
        appendToken(value, Integer.toString(work.duplicateTransitions()));
        appendToken(value, Integer.toString(
            work.principalRuleTransitions()));
        appendToken(value, Integer.toString(work.unsafeTransitions()));
        appendToken(value, Integer.toString(
            work.technicalFailureTransitions()));
        appendToken(value, Integer.toString(
            work.expressionLimitTransitions()));
        appendToken(value, Integer.toString(
            work.primitiveLimitTransitions()));
        appendToken(value, Integer.toString(
            work.successorLimitTransitions()));
        appendToken(value, Integer.toString(work.depthLimitTransitions()));
        appendToken(value, Integer.toString(
            work.visitedLimitTransitions()));
        appendToken(value, Integer.toString(
            work.terminalSelectionTransitions()));
        appendToken(value, Integer.toString(work.technicalFailures()));
        appendToken(value, Integer.toString(work.matchAnalyses()));
        appendToken(value, Integer.toString(work.maxFrontierSize()));
        appendToken(value, Boolean.toString(
            work.generatedTransitionLimitReached()));
        appendToken(value, Boolean.toString(
            work.visitedStateLimitReached()));
        appendToken(value, Boolean.toString(work.depthLimitReached()));
        appendToken(value, Boolean.toString(
            work.primitiveWorkLimitReached()));
        appendToken(value, Boolean.toString(
            work.expressionNodeLimitReached()));
        appendToken(value, Boolean.toString(
            work.successorLimitReached()));
        appendToken(value, Boolean.toString(work.matchInconclusive()));
        appendToken(value, Boolean.toString(work.technicalFailureReached()));
    }

    private static void appendToken(StringBuilder value, String token) {
        String safe = Objects.requireNonNull(token, "token");
        value.append(safe.length()).append(':').append(safe);
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static PlanAttempt attempt(
        Status status,
        String sourceExpression,
        AssumptionSignature initialAssumptions,
        Optional<PatternMatchAnalyzer.Analysis> initialAnalysis,
        Optional<Transformation> directPrincipalReplay,
        Optional<PreparedBridge> preparedBridge,
        WorkLedger work,
        String detailCode
    ) {
        return new PlanAttempt(
            status,
            sourceExpression,
            initialAssumptions,
            initialAnalysis,
            directPrincipalReplay,
            preparedBridge,
            work,
            detailCode);
    }

    private static final class MutableWork {
        private int expandedStates;
        private int generatedTransitions;
        private int admittedTransitions;
        private int duplicateTransitions;
        private int principalRuleTransitions;
        private int unsafeTransitions;
        private int technicalFailureTransitions;
        private int expressionLimitTransitions;
        private int primitiveLimitTransitions;
        private int successorLimitTransitions;
        private int depthLimitTransitions;
        private int visitedLimitTransitions;
        private int terminalSelectionTransitions;
        private int technicalFailures;
        private int matchAnalyses;
        private int maxFrontierSize;
        private boolean generatedTransitionLimitReached;
        private boolean visitedStateLimitReached;
        private boolean depthLimitReached;
        private boolean primitiveWorkLimitReached;
        private boolean expressionNodeLimitReached;
        private boolean successorLimitReached;
        private boolean matchInconclusive;
        private boolean technicalFailureReached;

        private WorkLedger snapshot(Budget budget, int visitedStates) {
            return new WorkLedger(
                budget,
                expandedStates,
                visitedStates,
                generatedTransitions,
                admittedTransitions,
                duplicateTransitions,
                principalRuleTransitions,
                unsafeTransitions,
                technicalFailureTransitions,
                expressionLimitTransitions,
                primitiveLimitTransitions,
                successorLimitTransitions,
                depthLimitTransitions,
                visitedLimitTransitions,
                terminalSelectionTransitions,
                technicalFailures,
                matchAnalyses,
                maxFrontierSize,
                generatedTransitionLimitReached,
                visitedStateLimitReached,
                depthLimitReached,
                primitiveWorkLimitReached,
                expressionNodeLimitReached,
                successorLimitReached,
                matchInconclusive,
                technicalFailureReached);
        }
    }

    private record Source(
        String expression,
        Expr ast,
        String structureHash
    ) {
    }

    private record StateKey(
        String structureHash,
        String assumptionFingerprint
    ) {
    }

    private record Node(
        String expression,
        Expr ast,
        String structureHash,
        int nodeCount,
        AssumptionSignature assumptions,
        int depth,
        int primitiveWork,
        Node parent,
        BridgeStep incoming,
        PatternMatchAnalyzer.Analysis analysis
    ) {
        private StateKey stateKey() {
            return new StateKey(
                structureHash,
                assumptions.fingerprint());
        }
    }

    private record Candidate(
        Node parent,
        Node successor,
        Transformation transformation,
        PatternMatchAnalyzer.Analysis analysis,
        int astGrowth,
        int primitiveWork
    ) {
    }
}
