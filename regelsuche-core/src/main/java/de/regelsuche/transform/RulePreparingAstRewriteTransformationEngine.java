package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Opt-in AST rewrite engine that augments direct rewrites with bounded,
 * rule-directed preparation plans.
 *
 * <p>The ordinary prepared backend remains the direct semantic baseline. This
 * engine first retains all direct results unchanged, then invokes
 * {@link RulePreparationPlanner} only for rules that did not directly match a
 * concrete AST occurrence. Historical configurations therefore stay unchanged
 * unless callers explicitly select this engine.</p>
 */
public final class RulePreparingAstRewriteTransformationEngine
        implements TransformationEngine {
    private static final int DEFAULT_MAX_AST_SIZE_INCREASE = 12;
    private static final int DEFAULT_MAX_CANDIDATES_PER_STATE = 80;

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final List<RewriteRule> rules;
    private final RulePreparationPlanner planner;
    private final RulePreparationPlanner.Budget preparationBudget;
    private final int maxAstSizeIncreasePerStep;
    private final int maxCandidatesPerState;
    private final PreparedAstRewriteTransformationEngine directEngine;
    private final RulePreparationPlanner.Context plannerContext;

    public RulePreparingAstRewriteTransformationEngine() {
        this(AstRewriteTransformationEngine.defaultRules());
    }

    public RulePreparingAstRewriteTransformationEngine(
        List<RewriteRule> rules
    ) {
        this(
            rules,
            RulePreparationPlanner.standard(),
            RulePreparationPlanner.Budget.defaults(),
            DEFAULT_MAX_AST_SIZE_INCREASE,
            DEFAULT_MAX_CANDIDATES_PER_STATE
        );
    }

    public RulePreparingAstRewriteTransformationEngine(
        List<RewriteRule> rules,
        RulePreparationPlanner planner
    ) {
        this(
            rules,
            planner,
            planner.enabled()
                ? RulePreparationPlanner.Budget.defaults()
                : RulePreparationPlanner.Budget.disabled(),
            DEFAULT_MAX_AST_SIZE_INCREASE,
            DEFAULT_MAX_CANDIDATES_PER_STATE
        );
    }

    public RulePreparingAstRewriteTransformationEngine(
        List<RewriteRule> rules,
        RulePreparationPlanner planner,
        RulePreparationPlanner.Budget preparationBudget,
        int maxAstSizeIncreasePerStep,
        int maxCandidatesPerState
    ) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        this.planner = Objects.requireNonNull(planner, "planner");
        this.preparationBudget = Objects.requireNonNull(
            preparationBudget,
            "preparationBudget"
        );
        if (maxAstSizeIncreasePerStep < 0 || maxCandidatesPerState < 1) {
            throw new IllegalArgumentException(
                "AST growth must not be negative and candidate limit must be positive");
        }
        this.maxAstSizeIncreasePerStep = maxAstSizeIncreasePerStep;
        this.maxCandidatesPerState = maxCandidatesPerState;
        this.directEngine = new PreparedAstRewriteTransformationEngine(
            this.rules,
            maxAstSizeIncreasePerStep,
            maxCandidatesPerState
        );
        this.plannerContext = RulePreparationPlanner.Context.unqualified(
            inventoryHash(this.rules)
        );
    }

    public List<RewriteRule> rules() {
        return rules;
    }

    @Override
    public List<Transformation> transform(String expression) {
        return transformWithEvidence(expression).transformations();
    }

    public Execution transformWithEvidence(String expression) {
        List<Transformation> direct = directEngine.transform(expression);
        try (RulePreparationPlanner.Session session =
                planner.openSession(preparationBudget)) {
            if (!planner.enabled() || direct.size() >= maxCandidatesPerState) {
                return new Execution(direct, List.of(), session.work());
            }

            Expr root;
            try {
                root = parser.parse(new InputRequest(InputType.TERM, expression))
                    .terms()
                    .getFirst();
            } catch (IllegalArgumentException exception) {
                return new Execution(direct, List.of(), session.work());
            }

            String formattedInput = ExpressionFormatter.format(root);
            int originalSize = canonicalizer.astNodeCount(formattedInput);
            Set<Transformation> transformations = new LinkedHashSet<>(direct);
            List<PreparedOccurrence> evidence = new ArrayList<>();
            for (PositionedNode positioned : positionedNodes(root)) {
                for (RewriteRule rule : rules) {
                    if (transformations.size() >= maxCandidatesPerState) {
                        return new Execution(
                            new ArrayList<>(transformations),
                            evidence,
                            session.work()
                        );
                    }
                    if (directlyMatches(rule, positioned.expression())) {
                        continue;
                    }
                    RulePreparationPlanner.PlanningResult planned =
                        session.plan(rule, positioned.expression(), plannerContext);
                    for (RulePreparationPlanner.PreparedRuleApplication application
                            : planned.applications()) {
                        Expr expressionAfter = replaceAt(
                            root,
                            positioned.path(),
                            0,
                            application.resultSubtree()
                        );
                        String formattedAfter = ExpressionFormatter.format(
                            expressionAfter
                        );
                        if (formattedAfter.equals(formattedInput)) {
                            continue;
                        }
                        int growth = canonicalizer.astNodeCount(formattedAfter)
                            - originalSize;
                        if (growth > maxAstSizeIncreasePerStep) {
                            continue;
                        }
                        int preparationGrowth = canonicalizer.astNodeCount(
                            ExpressionFormatter.format(
                                application.preparedSubtree()
                            )
                        ) - canonicalizer.astNodeCount(
                            ExpressionFormatter.format(
                                application.originalSubtree()
                            )
                        );
                        if (preparationGrowth > maxAstSizeIncreasePerStep) {
                            continue;
                        }
                        Transformation transformation = new Transformation(
                            rule.id(),
                            formattedAfter,
                            rule.kind(),
                            rule.mayIncreaseComplexity()
                                || preparationGrowth > 0,
                            rule.estimatedCostDelta()
                                + Math.max(0, preparationGrowth),
                            rule.isEquivalencePreservingByConstruction()
                                && application.certificate().verify(),
                            "prepared:"
                                + positioned.pathKey()
                                + ":"
                                + application.solutionHash(),
                            application.assumptions().stream()
                                .map(assumption -> assumption.expression())
                                .toList(),
                            rule.descriptor().packId(),
                            rule.descriptor().license(),
                            application.primitiveRuleIds()
                        );
                        if (transformations.add(transformation)) {
                            evidence.add(new PreparedOccurrence(
                                positioned.pathKey(),
                                formattedInput,
                                formattedAfter,
                                application
                            ));
                        }
                    }
                }
            }
            return new Execution(
                new ArrayList<>(transformations),
                evidence,
                session.work()
            );
        }
    }

    private boolean directlyMatches(RewriteRule rule, Expr subtree) {
        try {
            return rule.matches(subtree);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private List<PositionedNode> positionedNodes(Expr root) {
        List<PositionedNode> result = new ArrayList<>();
        collect(root, List.of(), "$", result);
        return List.copyOf(result);
    }

    private void collect(
        Expr expression,
        List<Integer> path,
        String pathKey,
        List<PositionedNode> target
    ) {
        target.add(new PositionedNode(pathKey, path, expression));
        if (expression instanceof BinaryExpr binary) {
            collect(
                binary.left(),
                append(path, -1),
                pathKey + ".left",
                target
            );
            collect(
                binary.right(),
                append(path, -2),
                pathKey + ".right",
                target
            );
        } else if (expression instanceof FunctionExpr function) {
            for (int index = 0; index < function.arguments().size(); index++) {
                collect(
                    function.arguments().get(index),
                    append(path, index),
                    pathKey + ".args[" + index + "]",
                    target
                );
            }
        }
    }

    private static List<Integer> append(List<Integer> path, int step) {
        List<Integer> result = new ArrayList<>(path);
        result.add(step);
        return List.copyOf(result);
    }

    private Expr replaceAt(
        Expr expression,
        List<Integer> path,
        int offset,
        Expr replacement
    ) {
        if (offset == path.size()) {
            return replacement;
        }
        int step = path.get(offset);
        if (expression instanceof BinaryExpr binary) {
            if (step == -1) {
                return new BinaryExpr(
                    replaceAt(binary.left(), path, offset + 1, replacement),
                    binary.operator(),
                    binary.right()
                );
            }
            if (step == -2) {
                return new BinaryExpr(
                    binary.left(),
                    binary.operator(),
                    replaceAt(binary.right(), path, offset + 1, replacement)
                );
            }
        }
        if (expression instanceof FunctionExpr function
                && step >= 0
                && step < function.arguments().size()) {
            List<Expr> arguments = new ArrayList<>(function.arguments());
            arguments.set(
                step,
                replaceAt(arguments.get(step), path, offset + 1, replacement)
            );
            return new FunctionExpr(function.name(), arguments);
        }
        throw new IllegalArgumentException(
            "path does not address the supplied expression");
    }

    private static String inventoryHash(List<RewriteRule> rules) {
        String payload = rules.stream()
            .map(rule -> rule.id() + "@" + rule.descriptor().packId())
            .sorted()
            .toList()
            .toString();
        return RulePreparationPlanner.sha256(payload);
    }

    public record PreparedOccurrence(
        String pathKey,
        String expressionBefore,
        String expressionAfter,
        RulePreparationPlanner.PreparedRuleApplication application
    ) {
        public PreparedOccurrence {
            if (pathKey == null || pathKey.isBlank()
                    || expressionBefore == null || expressionBefore.isBlank()
                    || expressionAfter == null || expressionAfter.isBlank()) {
                throw new IllegalArgumentException(
                    "prepared occurrence identity and expressions are required");
            }
            application = Objects.requireNonNull(application, "application");
        }
    }

    public record Execution(
        List<Transformation> transformations,
        List<PreparedOccurrence> preparedOccurrences,
        RulePreparationPlanner.Work preparationWork
    ) {
        public Execution {
            transformations = List.copyOf(
                Objects.requireNonNull(transformations, "transformations"));
            preparedOccurrences = List.copyOf(
                Objects.requireNonNull(
                    preparedOccurrences,
                    "preparedOccurrences"
                )
            );
            preparationWork = Objects.requireNonNull(
                preparationWork,
                "preparationWork"
            );
        }
    }

    private record PositionedNode(
        String pathKey,
        List<Integer> path,
        Expr expression
    ) {
        private PositionedNode {
            path = List.copyOf(path);
        }
    }
}
