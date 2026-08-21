package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
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
 * Opt-in engine that adds AC-normalized factor exposure to the existing direct
 * and exact-polynomial preparation paths.
 *
 * <p>The solver may only rearrange an already present multiplication tree. A
 * candidate is emitted only after its certificate verifies and the unchanged
 * visible {@code ast_cancel_division_factor} implementation replays on the
 * prepared subtree.</p>
 */
public final class AcNormalizationPreparationTransformationEngine
        implements TransformationEngine {
    private static final int DEFAULT_MAX_PREPARED_CANDIDATES = 16;
    private static final String PREPARATION_PACK_ID =
        "core-rule-preparation";

    private final TransformationEngine baseEngine;
    private final TransformationEngine principalEngine;
    private final Set<String> visibleRuleIds;
    private final AcNormalizationPreparationSolver solver;
    private final int maxPreparedCandidates;
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    public AcNormalizationPreparationTransformationEngine() {
        this(AstRewriteTransformationEngine.defaultRules());
    }

    public AcNormalizationPreparationTransformationEngine(
        List<RewriteRule> rules
    ) {
        this(
            new RulePreparationTransformationEngine(copyRules(rules)),
            new AstRewriteTransformationEngine(copyRules(rules)),
            ruleIds(rules),
            new AcNormalizationPreparationSolver(),
            DEFAULT_MAX_PREPARED_CANDIDATES);
    }

    public AcNormalizationPreparationTransformationEngine(
        TransformationEngine baseEngine,
        TransformationEngine principalEngine,
        Set<String> visibleRuleIds,
        AcNormalizationPreparationSolver solver,
        int maxPreparedCandidates
    ) {
        this.baseEngine = Objects.requireNonNull(
            baseEngine,
            "baseEngine");
        this.principalEngine = Objects.requireNonNull(
            principalEngine,
            "principalEngine");
        this.visibleRuleIds = Set.copyOf(
            Objects.requireNonNull(visibleRuleIds, "visibleRuleIds"));
        this.solver = Objects.requireNonNull(solver, "solver");
        if (maxPreparedCandidates < 0) {
            throw new IllegalArgumentException(
                "maxPreparedCandidates must not be negative");
        }
        this.maxPreparedCandidates = maxPreparedCandidates;
    }

    public static AcNormalizationPreparationTransformationEngine
            withKnowledgePacks(KnowledgePackSelection selection) {
        List<RewriteRule> rules = new ArrayList<>(
            AstRewriteTransformationEngine.defaultRules(selection));
        rules.addAll(new KnowledgePackRegistry().enabledRules(selection));
        return new AcNormalizationPreparationTransformationEngine(rules);
    }

    public String solverId() {
        return AcNormalizationPreparationSolver.SOLVER_ID;
    }

    public Set<String> visibleRuleIds() {
        return visibleRuleIds;
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
            if (!isDivision(positioned.expression())) {
                continue;
            }
            AcNormalizationPreparationSolver.PlanAttempt attempt =
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
            && visibleRuleIds.contains(
                AcNormalizationPreparationSolver.PRINCIPAL_RULE_ID)
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

    private Set<String> outputKeys(List<Transformation> transformations) {
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
        AcNormalizationPreparationSolver.PlanAttempt attempt,
        Set<String> retainedOutputs
    ) {
        if (attempt.status()
                != AcNormalizationPreparationSolver.Status.PREPARED) {
            return null;
        }
        AcNormalizationPreparationSolver.PreparedApplication application =
            attempt.application().orElseThrow();
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
            "prepared-ac:"
                + application.certificate().contentHash()
                + ":"
                + positionKey(positioned.path()),
            application.assumptions(),
            PREPARATION_PACK_ID,
            "PROJECT",
            application.primitiveRuleIds());
    }

    private boolean verified(
        AcNormalizationPreparationSolver.PreparedApplication application
    ) {
        try {
            return solver.verify(application);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Transformation replayPrincipal(
        AcNormalizationPreparationSolver.PreparedApplication application
    ) {
        String prepared = ExpressionFormatter.format(
            application.preparedSubtree());
        String expected = outputKey(
            ExpressionFormatter.format(application.resultSubtree()),
            application.assumptions());
        return principalEngine.transform(prepared).stream()
            .filter(transformation -> application.principalRuleId()
                .equals(transformation.rule()))
            .filter(transformation -> expected.equals(outputKey(
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

    private static boolean isDivision(Expr expression) {
        return expression instanceof BinaryExpr binary
            && binary.operator() == BinaryOperator.DIV;
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
        if (path.isEmpty()) {
            return "root";
        }
        return path.stream()
            .map(String::valueOf)
            .collect(java.util.stream.Collectors.joining("."));
    }

    private static List<RewriteRule> copyRules(List<RewriteRule> rules) {
        return List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    private static Set<String> ruleIds(List<RewriteRule> rules) {
        Set<String> ids = new LinkedHashSet<>();
        for (RewriteRule rule : copyRules(rules)) {
            ids.add(Objects.requireNonNull(rule, "rule").id());
        }
        return Set.copyOf(ids);
    }

    public record PreparationObservation(
        String path,
        String subtree,
        AcNormalizationPreparationSolver.PlanAttempt attempt
    ) {
        public PreparationObservation {
            if (path == null || path.isBlank()
                    || subtree == null || subtree.isBlank()) {
                throw new IllegalArgumentException(
                    "path and subtree must not be blank");
            }
            attempt = Objects.requireNonNull(attempt, "attempt");
        }

        public Optional<AcNormalizationPreparationSolver.PreparedApplication>
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
                Objects.requireNonNull(transformations, "transformations"));
            observations = List.copyOf(
                Objects.requireNonNull(observations, "observations"));
        }
    }

    private record PositionedNode(
        Expr expression,
        List<Integer> path
    ) {
        private PositionedNode {
            expression = Objects.requireNonNull(expression, "expression");
            path = List.copyOf(Objects.requireNonNull(path, "path"));
        }
    }
}
