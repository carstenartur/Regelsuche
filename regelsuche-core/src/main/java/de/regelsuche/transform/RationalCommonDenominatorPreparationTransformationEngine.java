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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Opt-in engine that adds bounded common-denominator preparation to all earlier
 * preparation paths.
 *
 * <p>The prepared expression is replayed through the existing
 * {@link RationalNormalizationHypothesisOperator}; no combined fraction is
 * emitted solely because the local solver proposed one.</p>
 */
public final class RationalCommonDenominatorPreparationTransformationEngine
        implements TransformationEngine {
    private static final int DEFAULT_MAX_PREPARED_CANDIDATES = 16;
    private static final String PREPARATION_PACK_ID =
        "core-rule-preparation";

    private final TransformationEngine baseEngine;
    private final TransformationEngine principalEngine;
    private final Set<String> visibleOperatorIds;
    private final RationalCommonDenominatorPreparationSolver solver;
    private final int maxPreparedCandidates;
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    public RationalCommonDenominatorPreparationTransformationEngine() {
        this(AstRewriteTransformationEngine.defaultRules());
    }

    public RationalCommonDenominatorPreparationTransformationEngine(
        List<RewriteRule> rules
    ) {
        this(
            new HypothesisTransformationEngine(
                new PerfectSquareStructurePreparationTransformationEngine(
                    copyRules(rules)),
                List.of(new RationalNormalizationHypothesisOperator())),
            new HypothesisTransformationEngine(
                expression -> List.of(),
                List.of(new RationalNormalizationHypothesisOperator()),
                4),
            Set.of(RationalNormalizationHypothesisOperator.RULE_ID),
            new RationalCommonDenominatorPreparationSolver(),
            DEFAULT_MAX_PREPARED_CANDIDATES);
    }

    public RationalCommonDenominatorPreparationTransformationEngine(
        TransformationEngine baseEngine,
        TransformationEngine principalEngine,
        Set<String> visibleOperatorIds,
        RationalCommonDenominatorPreparationSolver solver,
        int maxPreparedCandidates
    ) {
        this.baseEngine = Objects.requireNonNull(baseEngine, "baseEngine");
        this.principalEngine = Objects.requireNonNull(
            principalEngine,
            "principalEngine");
        this.visibleOperatorIds = Set.copyOf(
            Objects.requireNonNull(
                visibleOperatorIds,
                "visibleOperatorIds"));
        this.solver = Objects.requireNonNull(solver, "solver");
        if (maxPreparedCandidates < 0) {
            throw new IllegalArgumentException(
                "maxPreparedCandidates must not be negative");
        }
        this.maxPreparedCandidates = maxPreparedCandidates;
    }

    public static RationalCommonDenominatorPreparationTransformationEngine
            withKnowledgePacks(KnowledgePackSelection selection) {
        List<RewriteRule> rules = new ArrayList<>(
            AstRewriteTransformationEngine.defaultRules(selection));
        rules.addAll(new KnowledgePackRegistry().enabledRules(selection));
        return new RationalCommonDenominatorPreparationTransformationEngine(
            rules);
    }

    public String solverId() {
        return RationalCommonDenominatorPreparationSolver.SOLVER_ID;
    }

    public Set<String> visibleOperatorIds() {
        return visibleOperatorIds;
    }

    @Override
    public List<Transformation> transform(String expression) {
        return transformWithEvidence(expression).transformations();
    }

    public Execution transformWithEvidence(String expression) {
        List<Transformation> base = List.copyOf(
            baseEngine.transform(expression));
        if (!preparationEnabledFor(expression)) {
            return new Execution(base, List.of());
        }
        Expr root = parseRoot(expression);
        if (root == null) {
            return new Execution(base, List.of());
        }

        String formattedRoot = ExpressionFormatter.format(root);
        Set<String> retainedOutputs = outputKeys(base);
        List<Transformation> prepared = new ArrayList<>();
        List<PreparationObservation> observations = new ArrayList<>();
        for (PositionedNode positioned : positionedNodes(root)) {
            if (prepared.size() >= maxPreparedCandidates) {
                break;
            }
            if (!isCandidateRoot(positioned.expression())) {
                continue;
            }
            RationalCommonDenominatorPreparationSolver.PlanAttempt attempt =
                solver.plan(positioned.expression());
            observations.add(new PreparationObservation(
                positionKey(positioned.path()),
                ExpressionFormatter.format(positioned.expression()),
                attempt));
            Transformation candidate = preparedTransformation(
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
            return new Execution(base, observations);
        }
        List<Transformation> result = new ArrayList<>(base);
        result.addAll(prepared);
        return new Execution(result, observations);
    }

    private boolean preparationEnabledFor(String expression) {
        return maxPreparedCandidates > 0
            && visibleOperatorIds.contains(
                RationalCommonDenominatorPreparationSolver.PRINCIPAL_RULE_ID)
            && expression != null
            && !expression.isBlank();
    }

    private Expr parseRoot(String expression) {
        try {
            return parser.parse(new InputRequest(InputType.TERM, expression))
                .terms()
                .getFirst();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Set<String> outputKeys(
        List<Transformation> transformations
    ) {
        Set<String> result = new LinkedHashSet<>();
        for (Transformation transformation : transformations) {
            result.add(outputKey(
                transformation.transformedExpression(),
                transformation.assumptions()));
        }
        return result;
    }

    private Transformation preparedTransformation(
        Expr root,
        String formattedRoot,
        PositionedNode positioned,
        RationalCommonDenominatorPreparationSolver.PlanAttempt attempt,
        Set<String> retainedOutputs
    ) {
        if (attempt.status()
                != RationalCommonDenominatorPreparationSolver.Status.PREPARED) {
            return null;
        }
        RationalCommonDenominatorPreparationSolver.PreparedApplication
            application = attempt.application().orElseThrow();
        if (!verified(application)) {
            return null;
        }
        Transformation principal = replayPrincipal(application);
        if (principal == null) {
            return null;
        }

        Expr rewrittenRoot = replaceAt(
            root,
            positioned.path(),
            0,
            application.resultSubtree());
        String transformed = ExpressionFormatter.format(rewrittenRoot);
        if (transformed.equals(formattedRoot)
                || !retainedOutputs.add(outputKey(
                    transformed,
                    application.assumptions()))) {
            return null;
        }
        return new Transformation(
            application.principalRuleId(),
            transformed,
            principal.kind(),
            principal.mayIncreaseComplexity(),
            principal.estimatedCostDelta(),
            principal.equivalencePreservingByConstruction(),
            "prepared-common-denominator:"
                + application.certificate().contentHash()
                + ":"
                + positionKey(positioned.path()),
            application.assumptions(),
            PREPARATION_PACK_ID,
            "PROJECT",
            application.primitiveRuleIds());
    }

    private boolean verified(
        RationalCommonDenominatorPreparationSolver.PreparedApplication
            application
    ) {
        try {
            return solver.verify(application);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Transformation replayPrincipal(
        RationalCommonDenominatorPreparationSolver.PreparedApplication
            application
    ) {
        String prepared = ExpressionFormatter.format(
            application.preparedSubtree());
        String expectedExpression = canonicalExpressionKey(
            ExpressionFormatter.format(application.resultSubtree()));
        try {
            return principalEngine.transform(prepared).stream()
                .filter(transformation -> application.principalRuleId()
                    .equals(transformation.rule()))
                .filter(transformation -> expectedExpression.equals(
                    canonicalExpressionKey(
                        transformation.transformedExpression())))
                .filter(transformation -> transformation.assumptions()
                    .equals(application.assumptions()))
                .findFirst()
                .orElse(null);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String outputKey(
        String expression,
        List<String> assumptions
    ) {
        return canonicalExpressionKey(expression)
            + "\u0000"
            + String.join("\u0000", assumptions);
    }

    private String canonicalExpressionKey(String expression) {
        try {
            return canonicalizer.stableHash(expression);
        } catch (RuntimeException exception) {
            return expression == null ? "" : expression.trim();
        }
    }

    private static boolean isCandidateRoot(Expr expression) {
        return expression instanceof BinaryExpr binary
            && (binary.operator() == de.regelsuche.ast.BinaryOperator.ADD
                || binary.operator()
                    == de.regelsuche.ast.BinaryOperator.SUB);
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
            throw new IllegalArgumentException(
                "binary path component must be 0 or 1");
        }
        if (current instanceof FunctionExpr function
                && component >= 0
                && component < function.arguments().size()) {
            List<Expr> arguments = new ArrayList<>(
                function.arguments());
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
        if (path.isEmpty()) {
            return "root";
        }
        return path.stream()
            .map(String::valueOf)
            .collect(java.util.stream.Collectors.joining("."));
    }

    private static List<RewriteRule> copyRules(
        List<RewriteRule> rules
    ) {
        return List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    public record PreparationObservation(
        String path,
        String subtree,
        RationalCommonDenominatorPreparationSolver.PlanAttempt attempt
    ) {
        public PreparationObservation {
            if (path == null || path.isBlank()
                    || subtree == null || subtree.isBlank()) {
                throw new IllegalArgumentException(
                    "path and subtree must not be blank");
            }
            attempt = Objects.requireNonNull(attempt, "attempt");
        }

        public Optional<
                RationalCommonDenominatorPreparationSolver.PreparedApplication>
                application() {
            return attempt.application();
        }
    }

    public record Execution(
        List<Transformation> transformations,
        List<PreparationObservation> observations
    ) {
        public Execution {
            transformations = List.copyOf(
                Objects.requireNonNull(
                    transformations,
                    "transformations"));
            observations = List.copyOf(
                Objects.requireNonNull(
                    observations,
                    "observations"));
        }
    }

    private record PositionedNode(
        Expr expression,
        List<Integer> path
    ) {
        private PositionedNode {
            expression = Objects.requireNonNull(
                expression,
                "expression");
            path = List.copyOf(
                Objects.requireNonNull(path, "path"));
        }
    }
}
