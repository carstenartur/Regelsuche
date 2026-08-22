package de.regelsuche.search.reachability;

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
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.EquivalentExpressionProvider;
import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternMatchAnalyzer;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Bounded local search whose only goal is concrete applicability of one visible
 * pattern rule. It receives no desired result expression.
 */
public final class PatternTargetedLocalBridgeSearch {
    public static final String SEARCH_ID =
        "regelsuche.pattern-targeted-local-bridge/v1";
    public static final String CERTIFICATE_SCHEMA =
        "regelsuche.pattern-targeted-local-bridge-certificate/v1";

    private static final Comparator<Candidate> CANDIDATE_ORDER =
        Comparator.comparingInt((Candidate value) ->
                value.analysis().matched() ? 0 : 1)
            .thenComparing(Comparator.comparingInt((Candidate value) ->
                value.analysis().matchedPatternNodes()).reversed())
            .thenComparing(Comparator.comparingInt((Candidate value) ->
                value.analysis().bindings().size()).reversed())
            .thenComparingInt(value ->
                value.analysis().residualObligations().size())
            .thenComparingInt(value -> residualLowerBound(value.analysis()))
            .thenComparingInt(Candidate::astGrowth)
            .thenComparingInt(Candidate::primitivePathWork)
            .thenComparing(Candidate::structuralFingerprint)
            .thenComparing(value -> value.transformation().rule())
            .thenComparing(value ->
                value.transformation().applicationKey());

    private final PatternRewriteRule principalRule;
    private final List<RewriteRule> preparationRules;
    private final AstRewriteTransformationEngine preparationEngine;
    private final String repositoryRevision;
    private final String principalFingerprint;
    private final String preparationInventoryFingerprint;
    private final Budget budget;
    private final PatternMatchAnalyzer matchAnalyzer =
        new PatternMatchAnalyzer();
    private final ExpressionParser parser = new ExpressionParser();

    public PatternTargetedLocalBridgeSearch(
        PatternRewriteRule principalRule,
        List<? extends RewriteRule> preparationRules,
        String repositoryRevision,
        Budget budget
    ) {
        this.principalRule = Objects.requireNonNull(
            principalRule, "principalRule");
        this.preparationRules = validatePreparationRules(
            preparationRules, principalRule.id());
        this.repositoryRevision = requireRevision(repositoryRevision);
        this.budget = Objects.requireNonNull(budget, "budget");
        this.principalFingerprint = RuleInventoryFingerprint
            .ruleContentHash(principalRule);
        this.preparationInventoryFingerprint = RuleInventoryFingerprint
            .contentHash(this.preparationRules);
        this.preparationEngine = new AstRewriteTransformationEngine(
            this.preparationRules,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE);
    }

    /** Runs target-free formation for one local AST subtree. */
    public Attempt analyze(
        String sourceExpression,
        AssumptionSignature initialAssumptions
    ) {
        try {
            return new Run(
                normalize(sourceExpression, "sourceExpression"),
                normalized(initialAssumptions)).execute();
        } catch (RuntimeException exception) {
            return new Attempt(
                Status.TECHNICAL_FAILURE,
                Optional.empty(),
                AnalysisSnapshot.inconclusive("TECHNICAL_FAILURE"),
                Work.empty(),
                Set.of(),
                "TECHNICAL_FAILURE",
                exception.getClass().getName() + ": "
                    + Objects.toString(exception.getMessage(), ""));
        }
    }

    /** Replays all retained preparation steps and the concrete principal rule. */
    public Verification verify(Bridge bridge) {
        if (bridge == null) {
            return new Verification(false, "BRIDGE_MISSING");
        }
        try {
            if (!bridge.configurationMatches(
                    repositoryRevision,
                    principalRule.id(),
                    principalFingerprint,
                    preparationInventoryFingerprint,
                    budget)) {
                return new Verification(
                    false, "CONFIGURATION_IDENTITY_MISMATCH");
            }
            String current = bridge.sourceExpression();
            AssumptionSignature assumptions = bridge.sourceAssumptions();
            for (Step retained : bridge.preparationSteps()) {
                if (!current.equals(retained.expressionBefore())) {
                    return new Verification(
                        false, "PREPARATION_PATH_DISCONNECTED");
                }
                Transformation replay = replayPreparation(current, retained);
                if (replay == null) {
                    return new Verification(
                        false, "PREPARATION_REPLAY_FAILED");
                }
                current = normalize(
                    replay.transformedExpression(), "replayed expression");
                assumptions = merge(assumptions, replay.assumptions());
            }
            if (!current.equals(bridge.terminalExpression())
                    || !assumptions.equals(bridge.terminalAssumptions())) {
                return new Verification(false, "TERMINAL_STATE_MISMATCH");
            }
            PatternMatchAnalyzer.Analysis terminalAnalysis =
                analyzePattern(parser.parseTerm(current));
            if (!terminalAnalysis.matched()
                    || !AnalysisSnapshot.from(terminalAnalysis).equals(
                        bridge.terminalAnalysis())) {
                return new Verification(false, "TERMINAL_MATCH_MISMATCH");
            }
            PrincipalStep principal = replayPrincipal(current);
            if (principal == null
                    || !principal.equals(bridge.principalStep())) {
                return new Verification(false, "PRINCIPAL_REPLAY_FAILED");
            }
            if (!principal.expressionAfter().equals(
                    bridge.resultExpression())
                    || !merge(assumptions, principal.emittedAssumptions())
                        .equals(bridge.resultAssumptions())) {
                return new Verification(false, "RESULT_STATE_MISMATCH");
            }
            return certificateHash(bridge.withCertificateHash(""))
                .equals(bridge.certificateHash())
                ? new Verification(true, "VERIFIED")
                : new Verification(false, "CERTIFICATE_HASH_MISMATCH");
        } catch (RuntimeException exception) {
            return new Verification(
                false,
                "VERIFICATION_FAILURE_"
                    + exception.getClass().getSimpleName());
        }
    }

    private Transformation replayPreparation(
        String expression,
        Step retained
    ) {
        return preparationEngine.transform(expression).stream()
            .filter(value -> retained.ruleId().equals(value.rule()))
            .filter(value -> retained.applicationKey().equals(
                value.applicationKey()))
            .filter(value -> retained.expressionAfter().equals(normalize(
                value.transformedExpression(), "transformedExpression")))
            .filter(value -> retained.emittedAssumptions().equals(
                value.assumptions()))
            .filter(value -> retained.primitiveRuleIds().equals(
                value.primitiveRuleIds()))
            .findFirst()
            .orElse(null);
    }

    private PatternMatchAnalyzer.Analysis analyzePattern(Expr expression) {
        return matchAnalyzer.analyze(
            principalRule.source(),
            expression,
            principalRule.recognitionProfile(),
            new ExprMatcher.MatchOptions(
                EquivalentExpressionProvider.identity(),
                budget.maxMatchResults(),
                budget.maxMatchSteps(),
                budget.maxPatternBranches()));
    }

    private PrincipalStep replayPrincipal(String expression) {
        Expr parsed = parser.parseTerm(expression);
        if (!principalRule.matches(parsed)) {
            return null;
        }
        Expr result = principalRule.apply(parsed);
        if (result.equals(parsed)) {
            return null;
        }
        List<String> assumptions = principalRule.assumptions(parsed).stream()
            .map(Assumption::expression)
            .toList();
        return new PrincipalStep(
            expression,
            ExpressionFormatter.format(result),
            principalRule.id(),
            assumptions,
            "principal:" + principalFingerprint + ":"
                + structuralFingerprint(parsed));
    }

    private Bridge bridge(
        Node source,
        Node terminal,
        Map<String, Node> states,
        PrincipalStep principal,
        Work work
    ) {
        ArrayDeque<Step> reverse = new ArrayDeque<>();
        Node current = terminal;
        while (current.parentKey() != null) {
            reverse.addFirst(current.incomingStep());
            current = Objects.requireNonNull(
                states.get(current.parentKey()),
                "preparation parent state");
        }
        Bridge unsigned = new Bridge(
            SEARCH_ID,
            CERTIFICATE_SCHEMA,
            repositoryRevision,
            principalRule.id(),
            principalFingerprint,
            preparationInventoryFingerprint,
            budget,
            source.expression(),
            source.assumptions(),
            terminal.expression(),
            terminal.assumptions(),
            principal.expressionAfter(),
            merge(terminal.assumptions(),
                principal.emittedAssumptions()),
            AnalysisSnapshot.from(source.analysis()),
            AnalysisSnapshot.from(terminal.analysis()),
            List.copyOf(reverse),
            principal,
            work,
            "");
        return unsigned.withCertificateHash(certificateHash(unsigned));
    }

    private final class Run {
        private final String sourceExpression;
        private final AssumptionSignature initialAssumptions;
        private final Map<String, Node> states = new LinkedHashMap<>();
        private final ArrayDeque<Node> frontier = new ArrayDeque<>();
        private final Set<String> limits = new LinkedHashSet<>();
        private int expandedStates;
        private int generatedTransitions;
        private int retainedTransitions;
        private int duplicateTransitions;
        private int analyzedCandidates;
        private int maxFrontierSize = 1;

        private Run(
            String sourceExpression,
            AssumptionSignature initialAssumptions
        ) {
            this.sourceExpression = sourceExpression;
            this.initialAssumptions = initialAssumptions;
        }

        private Attempt execute() {
            Expr sourceAst = parser.parseTerm(sourceExpression);
            PatternMatchAnalyzer.Analysis initial =
                analyzePattern(sourceAst);
            Node source = node(
                sourceExpression,
                sourceAst,
                initialAssumptions,
                0,
                0,
                null,
                null,
                initial);
            states.put(source.key(), source);
            frontier.add(source);
            if (initial.matched()) {
                return direct(source, initial);
            }
            recordInconclusiveMatch(initial);

            while (!frontier.isEmpty()) {
                int layerDepth = frontier.getFirst().depth();
                List<Node> layer = removeLayer(layerDepth);
                List<Candidate> candidates = new ArrayList<>();
                for (Node current : layer) {
                    expand(current, candidates);
                    if (limits.contains("GENERATED_TRANSITIONS")) {
                        break;
                    }
                }
                candidates.sort(CANDIDATE_ORDER);
                for (Candidate candidate : candidates) {
                    Node retained = retain(candidate);
                    if (retained == null) {
                        continue;
                    }
                    if (retained.analysis().matched()) {
                        PrincipalStep principal = replayPrincipal(
                            retained.expression());
                        if (principal != null) {
                            Bridge result = bridge(
                                source, retained, states,
                                principal, work());
                            Verification verification = verify(result);
                            return verification.valid()
                                ? attempt(
                                    Status.PREPARED,
                                    Optional.of(result),
                                    initial,
                                    "SHORTEST_LOCAL_BRIDGE_REPLAYED",
                                    "")
                                : attempt(
                                    Status.INVALID_CERTIFICATE,
                                    Optional.empty(),
                                    initial,
                                    verification.detailCode(),
                                    "");
                        }
                    }
                }
                maxFrontierSize = Math.max(
                    maxFrontierSize, frontier.size());
                if (limits.contains("GENERATED_TRANSITIONS")
                        || limits.contains("VISITED_STATES")) {
                    break;
                }
            }
            return limits.isEmpty()
                ? attempt(
                    Status.NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
                    Optional.empty(),
                    initial,
                    "COMPLETE_FROZEN_CLOSURE_EXHAUSTED",
                    "")
                : attempt(
                    Status.BUDGET_INCONCLUSIVE,
                    Optional.empty(),
                    initial,
                    "LOCAL_BRIDGE_BUDGET_INCONCLUSIVE",
                    "");
        }

        private List<Node> removeLayer(int depth) {
            List<Node> result = new ArrayList<>();
            while (!frontier.isEmpty()
                    && frontier.getFirst().depth() == depth) {
                result.add(frontier.removeFirst());
            }
            return result;
        }

        private void expand(Node current, List<Candidate> candidates) {
            List<Transformation> generated = Objects.requireNonNull(
                preparationEngine.transform(current.expression()),
                "preparation engine result");
            if (current.depth() >= budget.maxDepth()) {
                if (!generated.isEmpty()) {
                    limits.add("DEPTH");
                }
                return;
            }
            expandedStates++;
            int sourceNodes = expressionNodes(current.ast());
            List<Candidate> local = new ArrayList<>();
            for (Transformation transformation : generated) {
                if (generatedTransitions
                        >= budget.maxGeneratedTransitions()) {
                    limits.add("GENERATED_TRANSITIONS");
                    return;
                }
                generatedTransitions++;
                Candidate candidate = candidate(
                    current, sourceNodes, transformation);
                if (candidate != null) {
                    local.add(candidate);
                }
            }
            local.sort(CANDIDATE_ORDER);
            if (local.size() > budget.maxSuccessorsPerState()) {
                limits.add("SUCCESSORS_PER_STATE");
                local = local.subList(
                    0, budget.maxSuccessorsPerState());
            }
            candidates.addAll(local);
        }

        private Candidate candidate(
            Node parent,
            int sourceNodes,
            Transformation transformation
        ) {
            if (principalRule.id().equals(transformation.rule())) {
                limits.add("PRINCIPAL_PRESENT_IN_PREPARATION_OUTPUT");
                return null;
            }
            if (!transformation.equivalencePreservingByConstruction()) {
                limits.add("UNSAFE_PREPARATION_OUTPUT");
                return null;
            }
            int primitiveWork = parent.primitivePathWork()
                + transformation.primitiveStepCount();
            if (primitiveWork > budget.maxPrimitiveSteps()) {
                limits.add("PRIMITIVE_STEPS");
                return null;
            }
            String expression = normalize(
                transformation.transformedExpression(),
                "transformedExpression");
            Expr ast = parser.parseTerm(expression);
            int nodes = expressionNodes(ast);
            if (nodes > budget.maxExpressionNodes()) {
                limits.add("EXPRESSION_NODES");
                return null;
            }
            PatternMatchAnalyzer.Analysis analysis = analyzePattern(ast);
            analyzedCandidates++;
            recordInconclusiveMatch(analysis);
            AssumptionSignature assumptions = merge(
                parent.assumptions(), transformation.assumptions());
            return new Candidate(
                parent,
                transformation,
                expression,
                ast,
                structuralFingerprint(ast),
                assumptions,
                parent.depth() + 1,
                primitiveWork,
                nodes - sourceNodes,
                analysis);
        }

        private Node retain(Candidate candidate) {
            String key = stateKey(
                candidate.structuralFingerprint(),
                candidate.assumptions());
            if (states.containsKey(key)) {
                duplicateTransitions++;
                return null;
            }
            if (states.size() >= budget.maxVisitedStates()) {
                limits.add("VISITED_STATES");
                return null;
            }
            Step step = new Step(
                candidate.parent().expression(),
                candidate.expression(),
                candidate.transformation().rule(),
                candidate.transformation().assumptions(),
                candidate.transformation().applicationKey(),
                candidate.transformation().primitiveRuleIds());
            Node retained = node(
                candidate.expression(),
                candidate.ast(),
                candidate.assumptions(),
                candidate.depth(),
                candidate.primitivePathWork(),
                candidate.parent().key(),
                step,
                candidate.analysis());
            states.put(key, retained);
            frontier.addLast(retained);
            retainedTransitions++;
            return retained;
        }

        private Attempt direct(
            Node source,
            PatternMatchAnalyzer.Analysis initial
        ) {
            PrincipalStep replay = replayPrincipal(source.expression());
            return replay == null
                ? attempt(
                    Status.TECHNICAL_FAILURE,
                    Optional.empty(),
                    initial,
                    "DIRECT_MATCH_REPLAY_REJECTED",
                    "Pattern matched but concrete principal replay failed")
                : attempt(
                    Status.DIRECT_MATCH_AVAILABLE,
                    Optional.empty(),
                    initial,
                    "DIRECT_MATCH_REPLAYED",
                    "");
        }

        private void recordInconclusiveMatch(
            PatternMatchAnalyzer.Analysis analysis
        ) {
            if (analysis.inconclusive()) {
                limits.add("MATCH_ANALYSIS");
            }
        }

        private Work work() {
            return new Work(
                expandedStates,
                generatedTransitions,
                states.size(),
                retainedTransitions,
                duplicateTransitions,
                analyzedCandidates,
                maxFrontierSize);
        }

        private Attempt attempt(
            Status status,
            Optional<Bridge> bridge,
            PatternMatchAnalyzer.Analysis initial,
            String detailCode,
            String technicalDetail
        ) {
            return new Attempt(
                status,
                bridge,
                AnalysisSnapshot.from(initial),
                work(),
                limits,
                detailCode,
                technicalDetail);
        }
    }

    private static List<RewriteRule> validatePreparationRules(
        List<? extends RewriteRule> supplied,
        String principalId
    ) {
        Objects.requireNonNull(supplied, "preparationRules");
        List<RewriteRule> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (RewriteRule rule : supplied) {
            RewriteRule checked = Objects.requireNonNull(
                rule, "preparation rule");
            if (principalId.equals(checked.id())) {
                throw new IllegalArgumentException(
                    "principal rule must not be in preparation inventory");
            }
            if (!checked.isEquivalencePreservingByConstruction()) {
                throw new IllegalArgumentException(
                    "preparation rules must preserve equivalence");
            }
            if (!ids.add(checked.id())) {
                throw new IllegalArgumentException(
                    "duplicate preparation rule ID: " + checked.id());
            }
            result.add(checked);
        }
        return List.copyOf(result);
    }

    private Node node(
        String expression,
        Expr ast,
        AssumptionSignature assumptions,
        int depth,
        int primitivePathWork,
        String parentKey,
        Step incomingStep,
        PatternMatchAnalyzer.Analysis analysis
    ) {
        return new Node(
            expression,
            ast,
            structuralFingerprint(ast),
            assumptions,
            depth,
            primitivePathWork,
            parentKey,
            incomingStep,
            analysis);
    }

    private static AssumptionSignature normalized(
        AssumptionSignature signature
    ) {
        AssumptionSignature supplied = Objects.requireNonNull(
            signature, "initialAssumptions");
        return AssumptionSignature.ofExpressions(
            supplied.normalizedAssumptions());
    }

    private static AssumptionSignature merge(
        AssumptionSignature current,
        List<String> additions
    ) {
        return AssumptionSignature.merge(
            current,
            AssumptionSignature.ofExpressions(additions));
    }

    private static String normalize(String expression, String field) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        try {
            return ExpressionFormatter.format(
                new ExpressionParser().parseTerm(expression));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                field + " is not a supported term", exception);
        }
    }

    private static int expressionNodes(Expr expression) {
        int count = 0;
        ArrayDeque<Expr> pending = new ArrayDeque<>();
        pending.push(expression);
        while (!pending.isEmpty()) {
            Expr current = pending.pop();
            count++;
            if (current instanceof BinaryExpr binary) {
                pending.push(binary.right());
                pending.push(binary.left());
            } else if (current instanceof FunctionExpr function) {
                for (int index = function.arguments().size() - 1;
                        index >= 0; index--) {
                    pending.push(function.arguments().get(index));
                }
            }
        }
        return count;
    }

    private static int residualLowerBound(
        PatternMatchAnalyzer.Analysis analysis
    ) {
        return analysis.residualObligations().stream()
            .mapToInt(value -> switch (value.kind()) {
                case LITERAL_MISMATCH -> 1;
                case BINDING_CONFLICT -> 2;
                case SHAPE_MISMATCH -> 3;
                case FUNCTION_SHAPE_MISMATCH -> 4;
            })
            .sum();
    }

    private static String structuralFingerprint(Expr expression) {
        StringBuilder descriptor = new StringBuilder();
        appendExpression(descriptor, expression);
        return sha256(descriptor.toString());
    }

    private static void appendExpression(
        StringBuilder target,
        Expr expression
    ) {
        if (expression instanceof NumberExpr number) {
            append(target, "number");
            append(target, Long.toHexString(
                Double.doubleToLongBits(number.value())));
        } else if (expression instanceof VariableExpr variable) {
            append(target, "variable");
            append(target, variable.name());
        } else if (expression instanceof BinaryExpr binary) {
            append(target, "binary");
            append(target, binary.operator().name());
            appendExpression(target, binary.left());
            appendExpression(target, binary.right());
        } else if (expression instanceof FunctionExpr function) {
            append(target, "function");
            append(target, function.name());
            function.arguments().forEach(value ->
                appendExpression(target, value));
        } else {
            throw new IllegalArgumentException(
                "unsupported expression type: "
                    + expression.getClass().getName());
        }
    }

    private static String stateKey(
        String structuralFingerprint,
        AssumptionSignature assumptions
    ) {
        return sha256(structuralFingerprint + "\u0000"
            + assumptions.fingerprint());
    }

    private static String certificateHash(Bridge bridge) {
        StringBuilder value = new StringBuilder();
        append(value, bridge.searchId());
        append(value, bridge.certificateSchema());
        append(value, bridge.repositoryRevision());
        append(value, bridge.principalRuleId());
        append(value, bridge.principalRuleFingerprint());
        append(value, bridge.preparationInventoryFingerprint());
        append(value, bridge.budget().identity());
        append(value, bridge.sourceExpression());
        appendList(value,
            bridge.sourceAssumptions().normalizedAssumptions());
        append(value, bridge.terminalExpression());
        appendList(value,
            bridge.terminalAssumptions().normalizedAssumptions());
        append(value, bridge.resultExpression());
        appendList(value,
            bridge.resultAssumptions().normalizedAssumptions());
        append(value, bridge.initialAnalysis().descriptor());
        append(value, bridge.terminalAnalysis().descriptor());
        bridge.preparationSteps().forEach(step ->
            append(value, step.descriptor()));
        append(value, bridge.principalStep().descriptor());
        append(value, bridge.work().descriptor());
        return sha256(value.toString());
    }

    private static String requireRevision(String value) {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                "repositoryRevision must be a lowercase commit SHA");
        }
        return value;
    }

    private static String requireHash(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                field + " must be a SHA-256 identity");
        }
        return value;
    }

    private static void appendList(
        StringBuilder target,
        List<String> values
    ) {
        append(target, Integer.toString(values.size()));
        values.forEach(value -> append(target, value));
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public enum Status {
        DIRECT_MATCH_AVAILABLE,
        PREPARED,
        NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
        BUDGET_INCONCLUSIVE,
        UNSUPPORTED,
        INVALID_CERTIFICATE,
        TECHNICAL_FAILURE
    }

    public record Budget(
        int maxDepth,
        int maxVisitedStates,
        int maxGeneratedTransitions,
        int maxPrimitiveSteps,
        int maxExpressionNodes,
        int maxSuccessorsPerState,
        int maxMatchResults,
        int maxMatchSteps,
        int maxPatternBranches
    ) {
        public Budget {
            if (maxDepth < 0 || maxVisitedStates < 1
                    || maxGeneratedTransitions < 0
                    || maxPrimitiveSteps < 0
                    || maxExpressionNodes < 1
                    || maxSuccessorsPerState < 1
                    || maxMatchResults < 1 || maxMatchSteps < 1
                    || maxPatternBranches < 1) {
                throw new IllegalArgumentException(
                    "bridge-search limits are invalid");
            }
        }

        public static Budget defaults() {
            return new Budget(
                4, 256, 2_048, 8, 256, 128,
                32, 5_000, 2_500);
        }

        String identity() {
            return maxDepth + ":" + maxVisitedStates + ":"
                + maxGeneratedTransitions + ":" + maxPrimitiveSteps
                + ":" + maxExpressionNodes + ":"
                + maxSuccessorsPerState + ":" + maxMatchResults
                + ":" + maxMatchSteps + ":" + maxPatternBranches;
        }
    }

    public record AnalysisSnapshot(
        PatternMatchAnalyzer.Status status,
        Map<String, String> bindings,
        int residualCount,
        int matchedPatternNodes,
        int totalPatternNodes,
        String detailCode
    ) {
        public AnalysisSnapshot {
            status = Objects.requireNonNull(status, "status");
            bindings = Collections.unmodifiableMap(new LinkedHashMap<>(
                new TreeMap<>(Objects.requireNonNull(
                    bindings, "bindings"))));
            if (residualCount < 0 || matchedPatternNodes < 0
                    || totalPatternNodes < 0
                    || matchedPatternNodes > totalPatternNodes
                    || detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "analysis snapshot is invalid");
            }
        }

        static AnalysisSnapshot from(
            PatternMatchAnalyzer.Analysis analysis
        ) {
            Map<String, String> bindings = new TreeMap<>();
            analysis.bindings().forEach((name, expression) ->
                bindings.put(name, ExpressionFormatter.format(expression)));
            return new AnalysisSnapshot(
                analysis.status(),
                bindings,
                analysis.residualObligations().size(),
                analysis.matchedPatternNodes(),
                analysis.totalPatternNodes(),
                analysis.detailCode());
        }

        static AnalysisSnapshot inconclusive(String detailCode) {
            return new AnalysisSnapshot(
                PatternMatchAnalyzer.Status.INCONCLUSIVE,
                Map.of(), 0, 0, 0, detailCode);
        }

        String descriptor() {
            return status.name() + ":" + bindings + ":"
                + residualCount + ":" + matchedPatternNodes + ":"
                + totalPatternNodes + ":" + detailCode;
        }
    }

    public record Step(
        String expressionBefore,
        String expressionAfter,
        String ruleId,
        List<String> emittedAssumptions,
        String applicationKey,
        List<String> primitiveRuleIds
    ) {
        public Step {
            expressionBefore = normalize(
                expressionBefore, "expressionBefore");
            expressionAfter = normalize(
                expressionAfter, "expressionAfter");
            if (ruleId == null || ruleId.isBlank()
                    || applicationKey == null
                    || applicationKey.isBlank()) {
                throw new IllegalArgumentException(
                    "step rule and application key must not be blank");
            }
            emittedAssumptions = AssumptionSignature.ofExpressions(
                emittedAssumptions).normalizedAssumptions();
            primitiveRuleIds = List.copyOf(Objects.requireNonNull(
                primitiveRuleIds, "primitiveRuleIds"));
            if (primitiveRuleIds.isEmpty()) {
                throw new IllegalArgumentException(
                    "primitive lineage must not be empty");
            }
        }

        String descriptor() {
            return expressionBefore + "\n" + expressionAfter + "\n"
                + ruleId + "\n" + emittedAssumptions + "\n"
                + applicationKey + "\n" + primitiveRuleIds;
        }
    }

    public record PrincipalStep(
        String expressionBefore,
        String expressionAfter,
        String ruleId,
        List<String> emittedAssumptions,
        String applicationKey
    ) {
        public PrincipalStep {
            expressionBefore = normalize(
                expressionBefore, "principal.expressionBefore");
            expressionAfter = normalize(
                expressionAfter, "principal.expressionAfter");
            if (ruleId == null || ruleId.isBlank()
                    || applicationKey == null
                    || applicationKey.isBlank()) {
                throw new IllegalArgumentException(
                    "principal rule and application key must not be blank");
            }
            emittedAssumptions = AssumptionSignature.ofExpressions(
                emittedAssumptions).normalizedAssumptions();
        }

        String descriptor() {
            return expressionBefore + "\n" + expressionAfter + "\n"
                + ruleId + "\n" + emittedAssumptions + "\n"
                + applicationKey;
        }
    }

    public record Work(
        int expandedStates,
        int generatedTransitions,
        int discoveredStates,
        int retainedTransitions,
        int duplicateTransitions,
        int analyzedCandidates,
        int maxFrontierSize
    ) {
        public Work {
            if (expandedStates < 0 || generatedTransitions < 0
                    || discoveredStates < 1 || retainedTransitions < 0
                    || duplicateTransitions < 0
                    || analyzedCandidates < 0 || maxFrontierSize < 1
                    || discoveredStates != retainedTransitions + 1) {
                throw new IllegalArgumentException(
                    "bridge-search work ledger is invalid");
            }
        }

        static Work empty() {
            return new Work(0, 0, 1, 0, 0, 0, 1);
        }

        String descriptor() {
            return expandedStates + ":" + generatedTransitions + ":"
                + discoveredStates + ":" + retainedTransitions + ":"
                + duplicateTransitions + ":" + analyzedCandidates + ":"
                + maxFrontierSize;
        }
    }

    public record Bridge(
        String searchId,
        String certificateSchema,
        String repositoryRevision,
        String principalRuleId,
        String principalRuleFingerprint,
        String preparationInventoryFingerprint,
        Budget budget,
        String sourceExpression,
        AssumptionSignature sourceAssumptions,
        String terminalExpression,
        AssumptionSignature terminalAssumptions,
        String resultExpression,
        AssumptionSignature resultAssumptions,
        AnalysisSnapshot initialAnalysis,
        AnalysisSnapshot terminalAnalysis,
        List<Step> preparationSteps,
        PrincipalStep principalStep,
        Work work,
        String certificateHash
    ) {
        public Bridge {
            if (!SEARCH_ID.equals(searchId)
                    || !CERTIFICATE_SCHEMA.equals(certificateSchema)) {
                throw new IllegalArgumentException(
                    "unexpected bridge evidence schema");
            }
            repositoryRevision = requireRevision(repositoryRevision);
            principalRuleFingerprint = requireHash(
                principalRuleFingerprint,
                "principalRuleFingerprint");
            preparationInventoryFingerprint = requireHash(
                preparationInventoryFingerprint,
                "preparationInventoryFingerprint");
            budget = Objects.requireNonNull(budget, "budget");
            sourceExpression = normalize(
                sourceExpression, "sourceExpression");
            terminalExpression = normalize(
                terminalExpression, "terminalExpression");
            resultExpression = normalize(
                resultExpression, "resultExpression");
            sourceAssumptions = normalized(sourceAssumptions);
            terminalAssumptions = normalized(terminalAssumptions);
            resultAssumptions = normalized(resultAssumptions);
            initialAnalysis = Objects.requireNonNull(
                initialAnalysis, "initialAnalysis");
            terminalAnalysis = Objects.requireNonNull(
                terminalAnalysis, "terminalAnalysis");
            preparationSteps = List.copyOf(Objects.requireNonNull(
                preparationSteps, "preparationSteps"));
            principalStep = Objects.requireNonNull(
                principalStep, "principalStep");
            work = Objects.requireNonNull(work, "work");
            if (principalRuleId == null || principalRuleId.isBlank()
                    || preparationSteps.isEmpty()
                    || terminalAnalysis.status()
                        == PatternMatchAnalyzer.Status.RESIDUAL
                    || terminalAnalysis.status()
                        == PatternMatchAnalyzer.Status.NOT_MATCHED
                    || terminalAnalysis.status()
                        == PatternMatchAnalyzer.Status.INCONCLUSIVE) {
                throw new IllegalArgumentException(
                    "prepared bridge evidence is incomplete");
            }
            if (!certificateHash.isEmpty()) {
                requireHash(certificateHash, "certificateHash");
            }
        }

        boolean configurationMatches(
            String revision,
            String ruleId,
            String ruleFingerprint,
            String inventoryFingerprint,
            Budget expectedBudget
        ) {
            return repositoryRevision.equals(revision)
                && principalRuleId.equals(ruleId)
                && principalRuleFingerprint.equals(ruleFingerprint)
                && preparationInventoryFingerprint.equals(
                    inventoryFingerprint)
                && budget.equals(expectedBudget);
        }

        Bridge withCertificateHash(String value) {
            return new Bridge(
                searchId, certificateSchema, repositoryRevision,
                principalRuleId, principalRuleFingerprint,
                preparationInventoryFingerprint, budget,
                sourceExpression, sourceAssumptions,
                terminalExpression, terminalAssumptions,
                resultExpression, resultAssumptions,
                initialAnalysis, terminalAnalysis,
                preparationSteps, principalStep, work, value);
        }

        public List<String> primitiveRuleIds() {
            List<String> result = new ArrayList<>();
            preparationSteps.forEach(step ->
                result.addAll(step.primitiveRuleIds()));
            result.add(principalRuleId);
            return List.copyOf(result);
        }
    }

    public record Attempt(
        Status status,
        Optional<Bridge> bridge,
        AnalysisSnapshot initialAnalysis,
        Work work,
        Set<String> reachedLimits,
        String detailCode,
        String technicalDetail
    ) {
        public Attempt {
            status = Objects.requireNonNull(status, "status");
            bridge = Objects.requireNonNull(bridge, "bridge");
            initialAnalysis = Objects.requireNonNull(
                initialAnalysis, "initialAnalysis");
            work = Objects.requireNonNull(work, "work");
            reachedLimits = Collections.unmodifiableSet(
                new LinkedHashSet<>(Objects.requireNonNull(
                    reachedLimits, "reachedLimits")));
            if ((status == Status.PREPARED) != bridge.isPresent()
                    || status == Status.BUDGET_INCONCLUSIVE
                        && reachedLimits.isEmpty()
                    || detailCode == null || detailCode.isBlank()
                    || status == Status.TECHNICAL_FAILURE
                        && (technicalDetail == null
                            || technicalDetail.isBlank())) {
                throw new IllegalArgumentException(
                    "attempt evidence is inconsistent");
            }
            technicalDetail = technicalDetail == null
                ? "" : technicalDetail.trim();
        }
    }

    public record Verification(boolean valid, String detailCode) {
        public Verification {
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
        }
    }

    private record Candidate(
        Node parent,
        Transformation transformation,
        String expression,
        Expr ast,
        String structuralFingerprint,
        AssumptionSignature assumptions,
        int depth,
        int primitivePathWork,
        int astGrowth,
        PatternMatchAnalyzer.Analysis analysis
    ) {
    }

    private record Node(
        String expression,
        Expr ast,
        String structuralFingerprint,
        AssumptionSignature assumptions,
        int depth,
        int primitivePathWork,
        String parentKey,
        Step incomingStep,
        PatternMatchAnalyzer.Analysis analysis
    ) {
        String key() {
            return stateKey(structuralFingerprint, assumptions);
        }
    }
}
