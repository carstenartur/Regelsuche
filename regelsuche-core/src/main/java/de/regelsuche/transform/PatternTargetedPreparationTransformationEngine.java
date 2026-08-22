package de.regelsuche.transform;

import de.regelsuche.assumption.AssumptionSignature;
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
import de.regelsuche.transform.PatternPreparationPlan.Attempt;
import de.regelsuche.transform.PatternPreparationPlan.Budget;
import de.regelsuche.transform.PatternPreparationPlan.PreparedApplication;
import de.regelsuche.transform.PatternPreparationPlan.Status;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Adds bounded pattern-targeted preparation after all existing safe exact
 * preparation paths while preserving direct results first.
 */
public final class PatternTargetedPreparationTransformationEngine
        implements TransformationEngine {
    public static final String PYTHAGOREAN_RULE_ID =
        "sympy.trig.pythagorean";
    private static final int DEFAULT_MAX_PREPARED_CANDIDATES = 16;

    private final TransformationEngine directEngine;
    private final PatternTargetedLocalBridgePlanner planner;
    private final AssumptionSignature sourceAssumptions;
    private final int maxPreparedCandidates;
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    public PatternTargetedPreparationTransformationEngine(
        TransformationEngine directEngine,
        PatternTargetedLocalBridgePlanner planner,
        int maxPreparedCandidates
    ) {
        this(
            directEngine,
            planner,
            AssumptionSignature.ofExpressions(List.of()),
            maxPreparedCandidates);
    }

    public PatternTargetedPreparationTransformationEngine(
        TransformationEngine directEngine,
        PatternTargetedLocalBridgePlanner planner,
        AssumptionSignature sourceAssumptions,
        int maxPreparedCandidates
    ) {
        this.directEngine = Objects.requireNonNull(
            directEngine,
            "directEngine");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.sourceAssumptions = AssumptionSignature.ofExpressions(
            Objects.requireNonNull(
                sourceAssumptions,
                "sourceAssumptions").normalizedAssumptions());
        if (maxPreparedCandidates < 0) {
            throw new IllegalArgumentException(
                "maxPreparedCandidates must not be negative");
        }
        this.maxPreparedCandidates = maxPreparedCandidates;
    }

    /**
     * First safe external-rule pilot: expose only the unconditional
     * Pythagorean identity after bounded local preparation.
     */
    public static PatternTargetedPreparationTransformationEngine
            symPyPythagoreanPilot(KnowledgePackSelection selection) {
        return symPyPythagoreanPilot(selection, Budget.safeDefaults());
    }

    public static PatternTargetedPreparationTransformationEngine
            symPyPythagoreanPilot(
                KnowledgePackSelection selection,
                Budget budget
            ) {
        List<RewriteRule> allRules = selectedRules(selection);
        PatternRewriteRule principal = allRules.stream()
            .filter(rule -> PYTHAGOREAN_RULE_ID.equals(rule.id()))
            .filter(PatternRewriteRule.class::isInstance)
            .map(PatternRewriteRule.class::cast)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "selected inventory does not contain "
                    + PYTHAGOREAN_RULE_ID));
        List<RewriteRule> preparationRules = allRules.stream()
            .filter(rule -> !PYTHAGOREAN_RULE_ID.equals(rule.id()))
            .toList();
        TransformationEngine direct = new AstRewriteTransformationEngine(
            allRules);
        TransformationEngine preparation =
            new RationalCommonDenominatorPreparationTransformationEngine(
                preparationRules);
        return new PatternTargetedPreparationTransformationEngine(
            direct,
            new PatternTargetedLocalBridgePlanner(
                principal,
                preparation,
                budget),
            DEFAULT_MAX_PREPARED_CANDIDATES);
    }

    public String principalRuleId() {
        return planner.principalRuleId();
    }

    @Override
    public List<Transformation> transform(String expression) {
        return transformWithEvidence(expression).transformations();
    }

    public Execution transformWithEvidence(String expression) {
        List<Transformation> direct = List.copyOf(
            directEngine.transform(expression));
        if (maxPreparedCandidates == 0
                || expression == null
                || expression.isBlank()) {
            return new Execution(direct, List.of());
        }
        Expr root = parse(expression);
        if (root == null) {
            return new Execution(direct, List.of());
        }
        String formattedRoot = ExpressionFormatter.format(root);
        Set<String> retainedOutputs = outputKeys(direct);
        List<Transformation> prepared = new ArrayList<>();
        List<Observation> observations = new ArrayList<>();
        for (PositionedNode positioned : positionedNodes(root)) {
            if (prepared.size() >= maxPreparedCandidates) {
                break;
            }
            Attempt attempt = planner.plan(
                ExpressionFormatter.format(positioned.expression()),
                sourceAssumptions);
            observations.add(new Observation(
                positionKey(positioned.path()),
                ExpressionFormatter.format(positioned.expression()),
                attempt));
            Transformation candidate = candidate(
                root,
                formattedRoot,
                positioned,
                attempt,
                retainedOutputs);
            if (candidate != null) {
                prepared.add(candidate);
            }
        }
        if (prepared.isEmpty()) {
            return new Execution(direct, observations);
        }
        List<Transformation> all = new ArrayList<>(direct);
        all.addAll(prepared);
        return new Execution(all, observations);
    }

    private Transformation candidate(
        Expr root,
        String formattedRoot,
        PositionedNode positioned,
        Attempt attempt,
        Set<String> retainedOutputs
    ) {
        if (attempt.status() != Status.PREPARED) {
            return null;
        }
        PreparedApplication application = attempt.application().orElseThrow();
        if (!planner.verify(application)) {
            return null;
        }
        Expr resultSubtree = parse(application.resultExpression());
        if (resultSubtree == null) {
            return null;
        }
        Expr rewrittenRoot = replaceAt(
            root,
            positioned.path(),
            0,
            resultSubtree);
        String transformed = ExpressionFormatter.format(rewrittenRoot);
        String outputKey = outputKey(
            transformed,
            application.finalAssumptions());
        if (transformed.equals(formattedRoot)
                || !retainedOutputs.add(outputKey)) {
            return null;
        }
        return new Transformation(
            application.principalRuleId(),
            transformed,
            application.principalKind(),
            application.principalMayIncreaseComplexity(),
            application.principalEstimatedCostDelta(),
            application.principalEquivalencePreserving(),
            "pattern-prepared:"
                + application.certificate().contentHash()
                + ":"
                + positionKey(positioned.path()),
            application.finalAssumptions(),
            application.principalPackId(),
            application.principalLicense(),
            application.primitiveRuleIds());
    }

    private Set<String> outputKeys(List<Transformation> transformations) {
        Set<String> result = new LinkedHashSet<>();
        transformations.forEach(transformation -> result.add(outputKey(
            transformation.transformedExpression(),
            transformation.assumptions())));
        return result;
    }

    private String outputKey(String expression, List<String> assumptions) {
        String expressionKey;
        try {
            expressionKey = canonicalizer.stableHash(expression);
        } catch (RuntimeException exception) {
            expressionKey = expression == null ? "" : expression.trim();
        }
        return expressionKey + "\u0000" + String.join("\u0000", assumptions);
    }

    private Expr parse(String expression) {
        try {
            return parser.parse(new InputRequest(InputType.TERM, expression))
                .terms()
                .getFirst();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static List<RewriteRule> selectedRules(
        KnowledgePackSelection selection
    ) {
        List<RewriteRule> rules = new ArrayList<>(
            AstRewriteTransformationEngine.defaultRules(selection));
        rules.addAll(new KnowledgePackRegistry().enabledRules(selection));
        return List.copyOf(rules);
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

    private static List<Integer> append(List<Integer> path, int component) {
        List<Integer> result = new ArrayList<>(path);
        result.add(component);
        return List.copyOf(result);
    }

    private static Expr replaceAt(
        Expr current,
        List<Integer> path,
        int offset,
        Expr replacement
    ) {
        if (offset == path.size()) {
            return replacement;
        }
        int component = path.get(offset);
        if (current instanceof BinaryExpr binary) {
            if (component == 0) {
                return new BinaryExpr(
                    replaceAt(
                        binary.left(),
                        path,
                        offset + 1,
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
                        offset + 1,
                        replacement));
            }
        } else if (current instanceof FunctionExpr function
                && component >= 0
                && component < function.arguments().size()) {
            List<Expr> arguments = new ArrayList<>(function.arguments());
            arguments.set(
                component,
                replaceAt(
                    arguments.get(component),
                    path,
                    offset + 1,
                    replacement));
            return new FunctionExpr(function.name(), arguments);
        }
        throw new IllegalArgumentException(
            "path does not address the supplied expression");
    }

    private static String positionKey(List<Integer> path) {
        return path.isEmpty()
            ? "root"
            : path.stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining("."));
    }

    public record Observation(
        String path,
        String subtree,
        Attempt attempt
    ) {
        public Observation {
            if (path == null || path.isBlank()
                    || subtree == null || subtree.isBlank()) {
                throw new IllegalArgumentException(
                    "path and subtree must not be blank");
            }
            attempt = Objects.requireNonNull(attempt, "attempt");
        }
    }

    public record Execution(
        List<Transformation> transformations,
        List<Observation> observations
    ) {
        public Execution {
            transformations = List.copyOf(Objects.requireNonNull(
                transformations,
                "transformations"));
            observations = List.copyOf(Objects.requireNonNull(
                observations,
                "observations"));
        }
    }

    private record PositionedNode(Expr expression, List<Integer> path) {
        private PositionedNode {
            expression = Objects.requireNonNull(expression, "expression");
            path = List.copyOf(Objects.requireNonNull(path, "path"));
        }
    }
}
