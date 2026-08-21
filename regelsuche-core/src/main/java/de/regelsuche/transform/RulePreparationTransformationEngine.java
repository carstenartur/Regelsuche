package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.RulePreparationPlanner.PreparedRuleApplication;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Opt-in transformation engine that augments direct rewrites with bounded,
 * rule-directed preparation plans.
 *
 * <p>Direct transformations are generated first and returned unchanged. Only
 * when the visible principal rule does not match directly does the planner
 * inspect a subtree. Historical users of {@link AstRewriteTransformationEngine}
 * therefore retain exactly their existing behavior and benchmark identity.</p>
 */
public final class RulePreparationTransformationEngine
        implements TransformationEngine {
    private static final int DEFAULT_MAX_PREPARED_CANDIDATES = 16;
    private static final String PREPARATION_PACK_ID =
        "core-rule-preparation";

    private final TransformationEngine directEngine;
    private final Set<String> visibleRuleIds;
    private final RulePreparationPlanner planner;
    private final int maxPreparedCandidates;
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    public RulePreparationTransformationEngine() {
        this(AstRewriteTransformationEngine.defaultRules());
    }

    public RulePreparationTransformationEngine(List<RewriteRule> rules) {
        this(
            new AstRewriteTransformationEngine(
                List.copyOf(Objects.requireNonNull(rules, "rules"))),
            ruleIds(rules),
            new RulePreparationPlanner(),
            DEFAULT_MAX_PREPARED_CANDIDATES);
    }

    public RulePreparationTransformationEngine(
        TransformationEngine directEngine,
        Set<String> visibleRuleIds,
        RulePreparationPlanner planner,
        int maxPreparedCandidates
    ) {
        this.directEngine = Objects.requireNonNull(
            directEngine,
            "directEngine");
        this.visibleRuleIds = Set.copyOf(
            Objects.requireNonNull(visibleRuleIds, "visibleRuleIds"));
        this.planner = Objects.requireNonNull(planner, "planner");
        if (maxPreparedCandidates < 0) {
            throw new IllegalArgumentException(
                "maxPreparedCandidates must not be negative");
        }
        this.maxPreparedCandidates = maxPreparedCandidates;
    }

    public static RulePreparationTransformationEngine withKnowledgePacks(
        KnowledgePackSelection selection
    ) {
        List<RewriteRule> rules = new ArrayList<>(
            AstRewriteTransformationEngine.defaultRules(selection));
        rules.addAll(new KnowledgePackRegistry().enabledRules(selection));
        return new RulePreparationTransformationEngine(rules);
    }

    public String plannerId() {
        return RulePreparationPlanner.PLANNER_ID;
    }

    public Set<String> visibleRuleIds() {
        return visibleRuleIds;
    }

    @Override
    public List<Transformation> transform(String expression) {
        List<Transformation> direct = List.copyOf(
            directEngine.transform(expression));
        if (maxPreparedCandidates == 0
                || !visibleRuleIds.contains(
                    RulePreparationPlanner.PRINCIPAL_RULE_ID)
                || expression == null
                || expression.isBlank()) {
            return direct;
        }

        Expr root;
        try {
            root = parser.parse(new InputRequest(InputType.TERM, expression))
                .terms()
                .getFirst();
        } catch (IllegalArgumentException exception) {
            return direct;
        }

        String formattedRoot = ExpressionFormatter.format(root);
        Set<String> retainedOutputKeys = new LinkedHashSet<>();
        for (Transformation transformation : direct) {
            retainedOutputKeys.add(outputKey(
                transformation.transformedExpression(),
                transformation.assumptions()));
        }

        List<Transformation> prepared = new ArrayList<>();
        for (PositionedNode positioned : positionedNodes(root)) {
            if (prepared.size() >= maxPreparedCandidates) {
                break;
            }
            RulePreparationPlanner.PlanAttempt attempt =
                planner.plan(positioned.expression());
            if (attempt.status()
                    != RulePreparationPlanner.Status.PREPARED) {
                continue;
            }
            PreparedRuleApplication application =
                attempt.application().orElseThrow();
            if (!planner.verify(application)) {
                // A malformed or stale preparation is not a reason to fail the
                // complete transformation request. Preserve the direct results
                // and continue scanning other AST positions fail-closed.
                continue;
            }
            Transformation principal = replayPrincipal(application);
            if (principal == null) {
                continue;
            }
            Expr rewrittenRoot = replaceAt(
                root,
                positioned.path(),
                0,
                application.resultSubtree());
            String transformed = ExpressionFormatter.format(rewrittenRoot);
            if (transformed.equals(formattedRoot)) {
                continue;
            }
            String outputKey = outputKey(
                transformed,
                application.assumptions());
            if (!retainedOutputKeys.add(outputKey)) {
                continue;
            }
            prepared.add(new Transformation(
                application.principalRuleId(),
                transformed,
                principal.kind(),
                true,
                principal.estimatedCostDelta(),
                principal.equivalencePreservingByConstruction(),
                "prepared:" + application.certificate().contentHash()
                    + ":" + positionKey(positioned.path()),
                application.assumptions(),
                PREPARATION_PACK_ID,
                "PROJECT",
                application.primitiveRuleIds()));
        }
        if (prepared.isEmpty()) {
            return direct;
        }
        List<Transformation> result = new ArrayList<>(direct);
        result.addAll(prepared);
        return List.copyOf(result);
    }

    private Transformation replayPrincipal(
        PreparedRuleApplication application
    ) {
        String prepared = ExpressionFormatter.format(
            application.preparedSubtree());
        String expectedResultKey = outputKey(
            ExpressionFormatter.format(application.resultSubtree()),
            application.assumptions());
        return directEngine.transform(prepared).stream()
            .filter(transformation -> application.principalRuleId()
                .equals(transformation.rule()))
            .filter(transformation -> expectedResultKey.equals(outputKey(
                transformation.transformedExpression(),
                transformation.assumptions())))
            .findFirst()
            .orElse(null);
    }

    private String outputKey(
        String expression,
        List<String> assumptions
    ) {
        String expressionKey;
        try {
            expressionKey = canonicalizer.stableHash(expression);
        } catch (RuntimeException exception) {
            expressionKey = expression == null ? "" : expression.trim();
        }
        return expressionKey + "\u0000" + String.join("\u0000", assumptions);
    }

    private static List<PositionedNode> positionedNodes(Expr root) {
        List<PositionedNode> result = new ArrayList<>();
        collect(root, List.of(), result);
        return List.copyOf(result);
    }

    private static void collect(
        Expr expression,
        List<Integer> path,
        List<PositionedNode> result
    ) {
        result.add(new PositionedNode(expression, path));
        if (expression instanceof BinaryExpr binary) {
            collect(binary.left(), append(path, 0), result);
            collect(binary.right(), append(path, 1), result);
        } else if (expression instanceof FunctionExpr function) {
            for (int index = 0;
                    index < function.arguments().size();
                    index++) {
                collect(
                    function.arguments().get(index),
                    append(path, index),
                    result);
            }
        }
    }

    private static List<Integer> append(
        List<Integer> path,
        int component
    ) {
        List<Integer> appended = new ArrayList<>(path);
        appended.add(component);
        return List.copyOf(appended);
    }

    private static Expr replaceAt(
        Expr current,
        List<Integer> path,
        int pathIndex,
        Expr replacement
    ) {
        if (pathIndex == path.size()) {
            return replacement;
        }
        int component = path.get(pathIndex);
        if (current instanceof BinaryExpr binary) {
            if (component == 0) {
                return new BinaryExpr(
                    replaceAt(
                        binary.left(),
                        path,
                        pathIndex + 1,
                        replacement),
                    binary.operator(),
                    binary.right());
            }
            if (component == 1) {
                return new BinaryExpr(
                    binary.left(),
                    binary.operator(),
                    replaceAt(
                        binary.right(),
                        path,
                        pathIndex + 1,
                        replacement));
            }
            throw new IllegalArgumentException(
                "binary path component must be 0 or 1");
        }
        if (current instanceof FunctionExpr function) {
            if (component < 0
                    || component >= function.arguments().size()) {
                throw new IllegalArgumentException(
                    "function path component out of bounds");
            }
            List<Expr> arguments = new ArrayList<>(function.arguments());
            arguments.set(
                component,
                replaceAt(
                    arguments.get(component),
                    path,
                    pathIndex + 1,
                    replacement));
            return new FunctionExpr(function.name(), arguments);
        }
        throw new IllegalArgumentException(
            "path descends through a leaf expression");
    }

    private static String positionKey(List<Integer> path) {
        if (path.isEmpty()) {
            return "root";
        }
        return path.stream()
            .map(String::valueOf)
            .collect(java.util.stream.Collectors.joining("."));
    }

    private static Set<String> ruleIds(List<RewriteRule> rules) {
        Objects.requireNonNull(rules, "rules");
        Set<String> ids = new LinkedHashSet<>();
        for (RewriteRule rule : rules) {
            ids.add(Objects.requireNonNull(rule, "rule").id());
        }
        return Set.copyOf(ids);
    }

    private record PositionedNode(
        Expr expression,
        List<Integer> path
    ) {
        private PositionedNode {
            Objects.requireNonNull(expression, "expression");
            path = List.copyOf(Objects.requireNonNull(path, "path"));
        }
    }
}
