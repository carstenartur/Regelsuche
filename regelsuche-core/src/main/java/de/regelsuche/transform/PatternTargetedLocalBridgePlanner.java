package de.regelsuche.transform;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.PatternPreparationPlan.Attempt;
import de.regelsuche.transform.PatternPreparationPlan.Budget;
import de.regelsuche.transform.PatternPreparationPlan.Certificate;
import de.regelsuche.transform.PatternPreparationPlan.LimitReason;
import de.regelsuche.transform.PatternPreparationPlan.PreparedApplication;
import de.regelsuche.transform.PatternPreparationPlan.Status;
import de.regelsuche.transform.PatternPreparationPlan.Step;
import de.regelsuche.transform.PatternPreparationPlan.WorkLedger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Finds a shortest bounded local bridge to one visible declarative rule.
 *
 * <p>The terminal predicate is concrete applicability of the supplied
 * principal rule. No target expression, reference form or benchmark label is
 * accepted by this API.</p>
 */
public final class PatternTargetedLocalBridgePlanner {
    public static final String PLANNER_ID =
        "pattern-targeted-local-bridge/v1";
    public static final String CERTIFICATE_SCHEMA =
        "regelsuche.pattern-targeted-local-bridge-certificate/v1";

    private static final Comparator<Transformation> TRANSFORMATION_ORDER =
        Comparator.comparing(Transformation::transformedExpression)
            .thenComparing(Transformation::rule)
            .thenComparing(Transformation::applicationKey)
            .thenComparing(value -> String.join(
                "\u0000",
                value.assumptions()))
            .thenComparing(value -> String.join(
                "\u0000",
                value.primitiveRuleIds()));

    private static final Comparator<Node> NODE_ORDER = Comparator
        .comparingInt((Node node) -> matchRank(node.analysis()))
        .thenComparing(Comparator.comparingInt(
            (Node node) -> node.analysis().matchedPatternNodes()).reversed())
        .thenComparing(Comparator.comparingInt(
            (Node node) -> node.analysis().bindings().size()).reversed())
        .thenComparingInt(node ->
            node.analysis().residualObligations().size())
        .thenComparingInt(node -> ExprStructuralFingerprint.nodeCount(
            node.parsed()))
        .thenComparing(Node::key);

    private final PatternRewriteRule principalRule;
    private final TransformationEngine preparationEngine;
    private final PatternMatchAnalyzer analyzer;
    private final Budget budget;
    private final ExpressionParser parser = new ExpressionParser();

    public PatternTargetedLocalBridgePlanner(
        PatternRewriteRule principalRule,
        TransformationEngine preparationEngine,
        Budget budget
    ) {
        this(
            principalRule,
            preparationEngine,
            new PatternMatchAnalyzer(),
            budget);
    }

    PatternTargetedLocalBridgePlanner(
        PatternRewriteRule principalRule,
        TransformationEngine preparationEngine,
        PatternMatchAnalyzer analyzer,
        Budget budget
    ) {
        this.principalRule = Objects.requireNonNull(
            principalRule,
            "principalRule");
        this.preparationEngine = Objects.requireNonNull(
            preparationEngine,
            "preparationEngine");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public String principalRuleId() {
        return principalRule.id();
    }

    public Budget budget() {
        return budget;
    }

    public Attempt plan(String sourceExpression) {
        return plan(
            sourceExpression,
            AssumptionSignature.ofExpressions(List.of()));
    }

    public Attempt plan(
        String sourceExpression,
        AssumptionSignature sourceAssumptions
    ) {
        SourcePreparation source = prepareSource(
            sourceExpression,
            sourceAssumptions);
        if (source.terminalAttempt() != null) {
            return source.terminalAttempt();
        }
        return search(source);
    }

    private SourcePreparation prepareSource(
        String sourceExpression,
        AssumptionSignature sourceAssumptions
    ) {
        AssumptionSignature trustedAssumptions =
            AssumptionSignature.ofExpressions(
                Objects.requireNonNull(
                    sourceAssumptions,
                    "sourceAssumptions").normalizedAssumptions());
        Expr parsed = parse(sourceExpression);
        if (parsed == null) {
            return SourcePreparation.terminal(
                unsupported("SOURCE_EXPRESSION_UNSUPPORTED"));
        }
        int nodes = ExprStructuralFingerprint.nodeCount(parsed);
        Counters counters = new Counters(nodes);
        PatternMatchAnalyzer.Analysis analysis = analyze(parsed, counters);
        if (nodes > budget.maxExpressionNodes()) {
            counters.limit(LimitReason.EXPRESSION_NODES);
            return SourcePreparation.terminal(
                inconclusive(analysis, counters));
        }
        if (replay(parsed).isPresent()) {
            return SourcePreparation.terminal(direct(analysis, counters));
        }
        String expression = ExpressionFormatter.format(parsed);
        String fingerprint = ExprStructuralFingerprint.sha256(parsed);
        Node source = new Node(
            expression,
            parsed,
            fingerprint,
            stateKey(fingerprint, trustedAssumptions),
            trustedAssumptions,
            0,
            0,
            null,
            null,
            analysis);
        return new SourcePreparation(source, analysis, counters, null);
    }

    private Attempt search(SourcePreparation source) {
        Map<String, Node> visited = new LinkedHashMap<>();
        visited.put(source.source().key(), source.source());
        List<Node> frontier = List.of(source.source());
        while (!frontier.isEmpty() && !source.counters().hardStop()) {
            LayerExpansion expansion = expandLayer(
                frontier,
                visited,
                source.counters());
            if (!expansion.terminals().isEmpty()) {
                return finishPrepared(source, expansion, visited);
            }
            List<Node> ordered = new ArrayList<>(expansion.nextLayer());
            ordered.sort(NODE_ORDER);
            frontier = List.copyOf(ordered);
        }
        return source.counters().limits().isEmpty()
            ? exhausted(source.sourceAnalysis(), source.counters())
            : inconclusive(source.sourceAnalysis(), source.counters());
    }

    private LayerExpansion expandLayer(
        List<Node> frontier,
        Map<String, Node> visited,
        Counters counters
    ) {
        List<Node> nextLayer = new ArrayList<>();
        List<Terminal> terminals = new ArrayList<>();
        for (Node current : frontier) {
            if (counters.hardStop()) {
                break;
            }
            expandNode(current, visited, counters, nextLayer, terminals);
        }
        return new LayerExpansion(
            List.copyOf(nextLayer),
            List.copyOf(terminals));
    }

    private void expandNode(
        Node current,
        Map<String, Node> visited,
        Counters counters,
        List<Node> nextLayer,
        List<Terminal> terminals
    ) {
        List<Transformation> transformations = boundedTransformations(
            current,
            counters);
        if (current.depth() >= budget.maxDepth()) {
            if (!transformations.isEmpty()) {
                counters.limit(LimitReason.DEPTH);
            }
            return;
        }
        for (Transformation transformation : transformations) {
            if (!counters.admitTransition()) {
                return;
            }
            Node next = successor(
                current,
                transformation,
                visited,
                counters);
            if (next == null) {
                if (counters.hardStop()) {
                    return;
                }
                continue;
            }
            Optional<Expr> result = replay(next.parsed());
            if (result.isPresent()) {
                terminals.add(new Terminal(next, result.orElseThrow()));
            } else {
                nextLayer.add(next);
            }
        }
    }

    private List<Transformation> boundedTransformations(
        Node current,
        Counters counters
    ) {
        List<Transformation> transformations = new ArrayList<>(
            preparationEngine.transform(current.expression()));
        transformations.sort(TRANSFORMATION_ORDER);
        if (transformations.size() <= budget.maxSuccessorsPerState()) {
            return transformations;
        }
        counters.limit(LimitReason.SUCCESSORS_PER_STATE);
        return new ArrayList<>(transformations.subList(
            0,
            budget.maxSuccessorsPerState()));
    }

    private Node successor(
        Node current,
        Transformation transformation,
        Map<String, Node> visited,
        Counters counters
    ) {
        int primitiveWork = safeAdd(
            current.primitivePathWork(),
            transformation.primitiveStepCount());
        if (primitiveWork > budget.maxPrimitiveSteps()) {
            counters.limit(LimitReason.PRIMITIVE_STEPS);
            return null;
        }
        Expr parsed = parse(transformation.transformedExpression());
        if (parsed == null) {
            return null;
        }
        int nodes = ExprStructuralFingerprint.nodeCount(parsed);
        if (nodes > budget.maxExpressionNodes()) {
            counters.limit(LimitReason.EXPRESSION_NODES);
            return null;
        }
        AssumptionSignature assumptions = AssumptionSignature.merge(
            current.assumptions(),
            AssumptionSignature.ofExpressions(
                transformation.assumptions()));
        String fingerprint = ExprStructuralFingerprint.sha256(parsed);
        String key = stateKey(fingerprint, assumptions);
        if (visited.containsKey(key)) {
            return null;
        }
        if (visited.size() >= budget.maxVisitedStates()) {
            counters.limit(LimitReason.VISITED_STATES);
            return null;
        }
        PatternMatchAnalyzer.Analysis analysis = analyze(parsed, counters);
        Node next = new Node(
            ExpressionFormatter.format(parsed),
            parsed,
            fingerprint,
            key,
            assumptions,
            current.depth() + 1,
            primitiveWork,
            current.key(),
            new Step(
                current.expression(),
                current.fingerprint(),
                ExpressionFormatter.format(parsed),
                fingerprint,
                transformation.rule(),
                transformation.assumptions(),
                transformation.applicationKey(),
                transformation.primitiveRuleIds()),
            analysis);
        visited.put(key, next);
        counters.visit(next, nodes);
        return next;
    }

    private Attempt finishPrepared(
        SourcePreparation source,
        LayerExpansion expansion,
        Map<String, Node> visited
    ) {
        Terminal terminal = expansion.terminals().stream()
            .min(Comparator
                .comparing((Terminal value) -> value.node(), NODE_ORDER)
                .thenComparing(value -> value.node().key()))
            .orElseThrow();
        PreparedApplication application = application(
            source.source(),
            terminal,
            visited,
            source.counters().snapshot());
        return verify(application)
            ? prepared(
                source.sourceAnalysis(),
                application,
                source.counters())
            : invalid(source.sourceAnalysis(), source.counters());
    }

    public boolean verify(PreparedApplication application) {
        try {
            Objects.requireNonNull(application, "application");
            if (!CERTIFICATE_SCHEMA.equals(
                    application.certificate().schema())
                    || !PLANNER_ID.equals(
                        application.certificate().plannerId())
                    || !principalRule.id().equals(
                        application.principalRuleId())) {
                return false;
            }
            Expr current = parse(application.sourceExpression());
            if (current == null
                    || !ExprStructuralFingerprint.sha256(current).equals(
                        application.sourceFingerprint())) {
                return false;
            }
            AssumptionSignature assumptions =
                AssumptionSignature.ofExpressions(
                    application.sourceAssumptions());
            for (Step retained : application.preparationSteps()) {
                if (!ExprStructuralFingerprint.sha256(current).equals(
                        retained.expressionBeforeFingerprint())) {
                    return false;
                }
                Transformation actual = preparationEngine
                    .transform(ExpressionFormatter.format(current))
                    .stream()
                    .sorted(TRANSFORMATION_ORDER)
                    .filter(candidate -> sameStep(candidate, retained))
                    .findFirst()
                    .orElse(null);
                if (actual == null) {
                    return false;
                }
                current = parse(actual.transformedExpression());
                if (current == null) {
                    return false;
                }
                assumptions = AssumptionSignature.merge(
                    assumptions,
                    AssumptionSignature.ofExpressions(actual.assumptions()));
            }
            if (!ExpressionFormatter.format(current).equals(
                    application.preparedExpression())
                    || !ExprStructuralFingerprint.sha256(current).equals(
                        application.preparedFingerprint())) {
                return false;
            }
            PatternMatchAnalyzer.Analysis terminalAnalysis = analyzer.analyze(
                principalRule.source(),
                current,
                principalRule.recognitionProfile(),
                matchOptions());
            Optional<Expr> result = replay(current);
            if (!terminalAnalysis.matched() || result.isEmpty()) {
                return false;
            }
            Expr replayedResult = result.orElseThrow();
            if (!ExpressionFormatter.format(replayedResult).equals(
                    application.resultExpression())
                    || !ExprStructuralFingerprint.sha256(replayedResult).equals(
                        application.resultFingerprint())) {
                return false;
            }
            AssumptionSignature finalAssumptions =
                AssumptionSignature.merge(
                    assumptions,
                    principalAssumptions(current));
            if (!finalAssumptions.normalizedAssumptions().equals(
                    application.finalAssumptions())) {
                return false;
            }
            List<String> primitiveIds = new ArrayList<>();
            application.preparationSteps().forEach(step ->
                primitiveIds.addAll(step.primitiveRuleIds()));
            primitiveIds.add(principalRule.id());
            return primitiveIds.equals(application.primitiveRuleIds())
                && certificateHash(application).equals(
                    application.certificate().contentHash());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private PatternMatchAnalyzer.Analysis analyze(
        Expr expression,
        Counters counters
    ) {
        PatternMatchAnalyzer.Analysis analysis = analyzer.analyze(
            principalRule.source(),
            expression,
            principalRule.recognitionProfile(),
            matchOptions());
        counters.analyze(analysis);
        return analysis;
    }

    private Optional<Expr> replay(Expr expression) {
        try {
            if (!principalRule.matches(expression)) {
                return Optional.empty();
            }
            Expr result = principalRule.apply(expression);
            return result.equals(expression)
                ? Optional.empty()
                : Optional.of(result);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private AssumptionSignature principalAssumptions(Expr expression) {
        return AssumptionSignature.ofExpressions(
            principalRule.assumptions(expression).stream()
                .map(Assumption::expression)
                .toList());
    }

    private PreparedApplication application(
        Node source,
        Terminal terminal,
        Map<String, Node> visited,
        WorkLedger work
    ) {
        List<Step> steps = reconstruct(terminal.node(), visited);
        List<String> primitiveIds = new ArrayList<>();
        steps.forEach(step -> primitiveIds.addAll(step.primitiveRuleIds()));
        primitiveIds.add(principalRule.id());
        AssumptionSignature finalAssumptions = AssumptionSignature.merge(
            terminal.node().assumptions(),
            principalAssumptions(terminal.node().parsed()));
        PreparedApplication provisional = new PreparedApplication(
            source.expression(),
            source.fingerprint(),
            source.assumptions().normalizedAssumptions(),
            terminal.node().expression(),
            terminal.node().fingerprint(),
            ExpressionFormatter.format(terminal.result()),
            ExprStructuralFingerprint.sha256(terminal.result()),
            principalRule.id(),
            principalRule.kind(),
            principalRule.mayIncreaseComplexity(),
            principalRule.estimatedCostDelta(),
            principalRule.isEquivalencePreservingByConstruction(),
            principalRule.descriptor().packId(),
            principalRule.descriptor().license(),
            finalAssumptions.normalizedAssumptions(),
            steps,
            List.copyOf(primitiveIds),
            work,
            new Certificate(
                CERTIFICATE_SCHEMA,
                PLANNER_ID,
                "0".repeat(64)));
        return provisional.withCertificate(new Certificate(
            CERTIFICATE_SCHEMA,
            PLANNER_ID,
            certificateHash(provisional)));
    }

    private static List<Step> reconstruct(
        Node terminal,
        Map<String, Node> visited
    ) {
        ArrayDeque<Step> reversed = new ArrayDeque<>();
        Node current = terminal;
        while (current.parentKey() != null) {
            reversed.addFirst(current.incomingStep());
            current = visited.get(current.parentKey());
            if (current == null) {
                throw new IllegalStateException(
                    "preparation parent chain is incomplete");
            }
        }
        return List.copyOf(reversed);
    }

    private boolean sameStep(
        Transformation candidate,
        Step retained
    ) {
        Expr after = parse(candidate.transformedExpression());
        return after != null
            && candidate.rule().equals(retained.rule())
            && candidate.applicationKey().equals(retained.applicationKey())
            && candidate.assumptions().equals(retained.assumptions())
            && candidate.primitiveRuleIds().equals(
                retained.primitiveRuleIds())
            && ExprStructuralFingerprint.sha256(after).equals(
                retained.expressionAfterFingerprint());
    }

    private ExprMatcher.MatchOptions matchOptions() {
        ExprMatcher.MatchOptions defaults =
            ExprMatcher.MatchOptions.defaults();
        return new ExprMatcher.MatchOptions(
            defaults.representativeProvider(),
            defaults.maxResults(),
            budget.maxMatchSteps(),
            defaults.maxPatternBranches());
    }

    private Expr parse(String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            return parser.parse(new InputRequest(InputType.TERM, expression))
                .terms()
                .getFirst();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static int matchRank(PatternMatchAnalyzer.Analysis analysis) {
        return switch (analysis.status()) {
            case EXACT_MATCH -> 0;
            case MATCH_MODULO_THEORY -> 1;
            case RESIDUAL -> 2;
            case NOT_MATCHED -> 3;
            case INCONCLUSIVE -> 4;
        };
    }

    private static String stateKey(
        String fingerprint,
        AssumptionSignature assumptions
    ) {
        return fingerprint + "\u0000" + assumptions.fingerprint();
    }

    private static int safeAdd(int left, int right) {
        long result = (long) left + right;
        return result > Integer.MAX_VALUE
            ? Integer.MAX_VALUE
            : (int) result;
    }

    private static String certificateHash(
        PreparedApplication application
    ) {
        StringBuilder descriptor = new StringBuilder();
        token(descriptor, CERTIFICATE_SCHEMA);
        token(descriptor, PLANNER_ID);
        token(descriptor, application.sourceExpression());
        token(descriptor, application.sourceFingerprint());
        application.sourceAssumptions().forEach(value ->
            token(descriptor, value));
        token(descriptor, application.preparedExpression());
        token(descriptor, application.preparedFingerprint());
        token(descriptor, application.resultExpression());
        token(descriptor, application.resultFingerprint());
        token(descriptor, application.principalRuleId());
        token(descriptor, application.principalKind().name());
        token(descriptor, Boolean.toString(
            application.principalMayIncreaseComplexity()));
        token(descriptor, Integer.toString(
            application.principalEstimatedCostDelta()));
        token(descriptor, Boolean.toString(
            application.principalEquivalencePreserving()));
        token(descriptor, application.principalPackId());
        token(descriptor, application.principalLicense());
        application.finalAssumptions().forEach(value ->
            token(descriptor, value));
        for (Step step : application.preparationSteps()) {
            token(descriptor, step.expressionBefore());
            token(descriptor, step.expressionBeforeFingerprint());
            token(descriptor, step.expressionAfter());
            token(descriptor, step.expressionAfterFingerprint());
            token(descriptor, step.rule());
            token(descriptor, step.applicationKey());
            step.assumptions().forEach(value -> token(descriptor, value));
            step.primitiveRuleIds().forEach(value ->
                token(descriptor, value));
        }
        application.primitiveRuleIds().forEach(value ->
            token(descriptor, value));
        token(descriptor, application.work().descriptor());
        return ExprStructuralFingerprint.sha256Text(descriptor.toString());
    }

    private static void token(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private Attempt direct(
        PatternMatchAnalyzer.Analysis analysis,
        Counters counters
    ) {
        return new Attempt(
            Status.DIRECT_MATCH_AVAILABLE,
            "PRINCIPAL_RULE_ALREADY_APPLICABLE",
            principalRule.id(),
            Optional.empty(),
            Optional.of(analysis),
            counters.snapshot(),
            Set.of());
    }

    private Attempt prepared(
        PatternMatchAnalyzer.Analysis analysis,
        PreparedApplication application,
        Counters counters
    ) {
        return new Attempt(
            Status.PREPARED,
            "SHORTEST_BOUNDED_LOCAL_BRIDGE_REPLAYED",
            principalRule.id(),
            Optional.of(application),
            Optional.of(analysis),
            counters.snapshot(),
            Set.of());
    }

    private Attempt exhausted(
        PatternMatchAnalyzer.Analysis analysis,
        Counters counters
    ) {
        return new Attempt(
            Status.NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
            "COMPLETE_FROZEN_LOCAL_CLOSURE_EXHAUSTED",
            principalRule.id(),
            Optional.empty(),
            Optional.of(analysis),
            counters.snapshot(),
            Set.of());
    }

    private Attempt inconclusive(
        PatternMatchAnalyzer.Analysis analysis,
        Counters counters
    ) {
        return new Attempt(
            Status.BUDGET_INCONCLUSIVE,
            "LOCAL_PREPARATION_BUDGET_REACHED",
            principalRule.id(),
            Optional.empty(),
            Optional.of(analysis),
            counters.snapshot(),
            counters.limits());
    }

    private Attempt invalid(
        PatternMatchAnalyzer.Analysis analysis,
        Counters counters
    ) {
        return new Attempt(
            Status.INVALID_CERTIFICATE,
            "PREPARED_APPLICATION_FAILED_INDEPENDENT_REPLAY",
            principalRule.id(),
            Optional.empty(),
            Optional.of(analysis),
            counters.snapshot(),
            Set.of());
    }

    private Attempt unsupported(String detailCode) {
        return new Attempt(
            Status.UNSUPPORTED,
            detailCode,
            principalRule.id(),
            Optional.empty(),
            Optional.empty(),
            new WorkLedger(
                budget,
                0,
                0,
                0,
                0,
                0,
                0,
                Set.of()),
            Set.of());
    }

    private record SourcePreparation(
        Node source,
        PatternMatchAnalyzer.Analysis sourceAnalysis,
        Counters counters,
        Attempt terminalAttempt
    ) {
        private static SourcePreparation terminal(Attempt attempt) {
            return new SourcePreparation(null, null, null, attempt);
        }
    }

    private record LayerExpansion(
        List<Node> nextLayer,
        List<Terminal> terminals
    ) {
    }

    private record Node(
        String expression,
        Expr parsed,
        String fingerprint,
        String key,
        AssumptionSignature assumptions,
        int depth,
        int primitivePathWork,
        String parentKey,
        Step incomingStep,
        PatternMatchAnalyzer.Analysis analysis
    ) {
    }

    private record Terminal(Node node, Expr result) {
    }

    private final class Counters {
        private final Set<LimitReason> reachedLimits =
            new LinkedHashSet<>();
        private int visitedStates = 1;
        private long generatedTransitions;
        private int maximumPrimitivePathWork;
        private int matchAnalyses;
        private int maximumDepthReached;
        private int maximumExpressionNodes;

        private Counters(int sourceNodes) {
            maximumExpressionNodes = sourceNodes;
        }

        private boolean admitTransition() {
            if (generatedTransitions >= budget.maxGeneratedTransitions()) {
                limit(LimitReason.GENERATED_TRANSITIONS);
                return false;
            }
            generatedTransitions++;
            return true;
        }

        private void visit(Node node, int nodes) {
            visitedStates++;
            maximumPrimitivePathWork = Math.max(
                maximumPrimitivePathWork,
                node.primitivePathWork());
            maximumDepthReached = Math.max(
                maximumDepthReached,
                node.depth());
            maximumExpressionNodes = Math.max(
                maximumExpressionNodes,
                nodes);
        }

        private void analyze(PatternMatchAnalyzer.Analysis analysis) {
            matchAnalyses++;
            if (analysis.inconclusive()) {
                limit(LimitReason.MATCH_ANALYSIS);
            }
        }

        private void limit(LimitReason reason) {
            reachedLimits.add(reason);
        }

        private boolean hardStop() {
            return reachedLimits.contains(LimitReason.VISITED_STATES)
                || reachedLimits.contains(
                    LimitReason.GENERATED_TRANSITIONS);
        }

        private Set<LimitReason> limits() {
            return Set.copyOf(reachedLimits);
        }

        private WorkLedger snapshot() {
            return new WorkLedger(
                budget,
                visitedStates,
                generatedTransitions,
                maximumPrimitivePathWork,
                matchAnalyses,
                maximumDepthReached,
                maximumExpressionNodes,
                reachedLimits);
        }
    }
}
