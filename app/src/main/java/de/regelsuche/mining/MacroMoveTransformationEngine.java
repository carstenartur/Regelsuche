package de.regelsuche.mining;

import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adds executable, goal-aware MacroMoves to the normal search path.
 *
 * <p>The search strategies already consume a {@link TransformationEngine}; this
 * wrapper appends transformations produced from selected {@link ReusableRule}
 * entries to the base engine output. Each selected macro is applied as a single
 * transformation edge while this wrapper stores the original atomic replay
 * path (or supporting path reconstruction references) for the graph/replay
 * layer.</p>
 */
public class MacroMoveTransformationEngine implements TransformationEngine {
    private final TransformationEngine baseEngine;
    private final GoalAwareMacroMoveSelector selector;
    private final String goalExpression;
    private final List<String> carriedAssumptions;
    private final Map<String, List<TransformationStep>> atomicStepsByRuleId;
    private final boolean macroMovesEnabled;
    private final MacroApplicabilityGuard applicabilityGuard;
    private final ExpressionParser expressionParser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final Map<String, MacroMoveExpansion> expansionsByEdge = new HashMap<>();
    private final Map<String, MacroMoveStatistics> statisticsByRuleId = new HashMap<>();

    public MacroMoveTransformationEngine(
        TransformationEngine baseEngine,
        GoalAwareMacroMoveSelector selector
    ) {
        this(baseEngine, selector, null, Map.of(), List.of(), true);
    }

    public MacroMoveTransformationEngine(
        TransformationEngine baseEngine,
        GoalAwareMacroMoveSelector selector,
        String goalExpression,
        Map<String, List<TransformationStep>> atomicStepsByRuleId
    ) {
        this(baseEngine, selector, goalExpression, atomicStepsByRuleId, List.of(), true);
    }

    public MacroMoveTransformationEngine(
        TransformationEngine baseEngine,
        GoalAwareMacroMoveSelector selector,
        String goalExpression,
        Map<String, List<TransformationStep>> atomicStepsByRuleId,
        List<String> carriedAssumptions
    ) {
        this(baseEngine, selector, goalExpression, atomicStepsByRuleId, carriedAssumptions, true);
    }

    public MacroMoveTransformationEngine(
        TransformationEngine baseEngine,
        GoalAwareMacroMoveSelector selector,
        String goalExpression,
        Map<String, List<TransformationStep>> atomicStepsByRuleId,
        List<String> carriedAssumptions,
        boolean macroMovesEnabled
    ) {
        this(baseEngine, selector, goalExpression, atomicStepsByRuleId, carriedAssumptions,
            macroMovesEnabled, MacroApplicabilityGuard.metadataRelations());
    }

    public MacroMoveTransformationEngine(
        TransformationEngine baseEngine,
        GoalAwareMacroMoveSelector selector,
        String goalExpression,
        Map<String, List<TransformationStep>> atomicStepsByRuleId,
        List<String> carriedAssumptions,
        boolean macroMovesEnabled,
        MacroApplicabilityGuard applicabilityGuard
    ) {
        if (baseEngine == null || selector == null) {
            throw new IllegalArgumentException("baseEngine and selector are required");
        }
        this.baseEngine = baseEngine;
        this.selector = selector;
        this.goalExpression = goalExpression;
        this.carriedAssumptions = carriedAssumptions == null ? List.of() : List.copyOf(carriedAssumptions);
        this.atomicStepsByRuleId = atomicStepsByRuleId == null ? Map.of() : Map.copyOf(atomicStepsByRuleId);
        this.macroMovesEnabled = macroMovesEnabled;
        this.applicabilityGuard = applicabilityGuard == null ? MacroApplicabilityGuard.metadataRelations() : applicabilityGuard;
    }

    @Override
    public List<Transformation> transform(String expression) {
        List<Transformation> result = new ArrayList<>(baseEngine.transform(expression));
        if (!macroMovesEnabled) {
            return result;
        }
        for (ReusableRule rule : selector.selectFor(expression, goalExpression, carriedAssumptions)) {
            result.addAll(applyMacro(expression, rule));
        }
        return result;
    }

    public Optional<MacroMoveExpansion> expansionFor(String fromExpression, String toExpression, String ruleId) {
        return Optional.ofNullable(expansionsByEdge.get(edgeKey(fromExpression, toExpression, ruleId)));
    }

    public Map<String, MacroMoveStatistics> statisticsByRuleId() {
        return Map.copyOf(statisticsByRuleId);
    }

    private List<Transformation> applyMacro(String expression, ReusableRule rule) {
        RewriteRule rewriteRule = new PatternRewriteRule(
            macroRuleId(rule),
            toPatternExpr(new RulePatternParser().parse(rule.leftPattern())),
            toPatternExpr(new RulePatternParser().parse(rule.rightPattern())),
            RewriteKind.NORMALIZE,
            false,
            -Math.max(1, (int) Math.round(Math.max(1.0, rule.averageImprovement()))),
            true
        );
        AstRewriteTransformationEngine macroEngine = new AstRewriteTransformationEngine(List.of(rewriteRule), Integer.MAX_VALUE, 80);
        MacroMoveStatistics before = statisticsByRuleId.getOrDefault(rule.id(), MacroMoveStatistics.empty());
        List<Transformation> transformations = macroEngine.transform(expression);
        specialUnitStepTransformation(expression, rule).ifPresent(transformations::add);
        transformations = transformations.stream()
            .filter(transformation -> applicabilityGuard.allows(expression, rule, transformation))
            .toList();
        int improved = (int) transformations.stream().filter(t -> t.estimatedCostDelta() < 0).count();
        double averageReduction = transformations.isEmpty()
            ? before.averageCostReduction()
            : transformations.stream().mapToInt(t -> Math.max(0, -t.estimatedCostDelta())).average().orElse(0.0);
        MacroMoveStatistics stats = new MacroMoveStatistics(
            before.timesConsidered() + 1,
            before.timesApplied() + transformations.size(),
            before.timesImprovedScore() + improved,
            averageReduction,
            goalExpression == null || goalExpression.isBlank() ? before.usefulForGoals() : List.of(goalExpression)
        );
        statisticsByRuleId.put(rule.id(), stats);
        for (Transformation transformation : transformations) {
            MacroMoveExpansion expansion = new MacroMoveExpansion(
                transformation.rule(),
                expression,
                transformation.transformedExpression(),
                atomicStepsByRuleId.getOrDefault(rule.id(), List.of()),
                rule.supportingPathIds(),
                rule.assumptions(),
                Math.max(1.0, rule.supportingPathIds().isEmpty() ? 1.0 : rule.supportingPathIds().size()),
                false,
                stats
            );
            expansionsByEdge.put(edgeKey(expression, transformation.transformedExpression(), transformation.rule()), expansion);
        }
        return transformations;
    }

    private Optional<Transformation> specialUnitStepTransformation(String expression, ReusableRule rule) {
        String compactPattern = rule.leftPattern().replace(" ", "");
        if (!compactPattern.contains("A*(A+1)") && !compactPattern.contains("(A*(A+1))")) {
            return Optional.empty();
        }
        Expr parsed;
        try {
            parsed = expressionParser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        if (!(parsed instanceof BinaryExpr division)
            || division.operator() != BinaryOperator.DIV
            || !isOne(division.left())) {
            return Optional.empty();
        }
        List<Expr> factors = flattenMultiplication(division.right());
        if (factors.size() != 2) {
            return Optional.empty();
        }
        Expr lower = factors.get(0);
        Expr upper = factors.get(1);
        if (!isUnitStepPair(lower, upper)) {
            lower = factors.get(1);
            upper = factors.get(0);
            if (!isUnitStepPair(lower, upper)) {
                return Optional.empty();
            }
        }
        String transformed = "1 / (" + ExpressionFormatter.format(lower) + ") - 1 / (" + ExpressionFormatter.format(upper) + ")";
        return Optional.of(new Transformation(
            macroRuleId(rule),
            transformed,
            RewriteKind.NORMALIZE,
            false,
            -Math.max(1, (int) Math.round(Math.max(1.0, rule.averageImprovement()))),
            true,
            macroRuleId(rule) + ":" + transformed,
            rule.assumptions()
        ));
    }

    private List<Expr> flattenMultiplication(Expr expression) {
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL) {
            return java.util.stream.Stream.concat(
                flattenMultiplication(binary.left()).stream(),
                flattenMultiplication(binary.right()).stream()
            ).toList();
        }
        return List.of(expression);
    }

    private boolean isUnitStepPair(Expr lower, Expr upper) {
        AdditiveOffset lowerOffset = additiveOffset(lower);
        AdditiveOffset upperOffset = additiveOffset(upper);
        return lowerOffset != null
            && upperOffset != null
            && same(lowerOffset.symbolicPart(), upperOffset.symbolicPart())
            && Double.compare(upperOffset.offset() - lowerOffset.offset(), 1.0) == 0;
    }

    private AdditiveOffset additiveOffset(Expr expression) {
        if (expression instanceof NumberExpr number) {
            return new AdditiveOffset(new NumberExpr(0), number.value());
        }
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.ADD) {
            if (binary.right() instanceof NumberExpr right) {
                return new AdditiveOffset(binary.left(), right.value());
            }
            if (binary.left() instanceof NumberExpr left) {
                return new AdditiveOffset(binary.right(), left.value());
            }
        }
        return new AdditiveOffset(expression, 0.0);
    }

    private boolean isOne(Expr expression) {
        return expression instanceof NumberExpr number && Double.compare(number.value(), 1.0) == 0;
    }

    private boolean same(Expr left, Expr right) {
        return canonicalizer.stableHash(ExpressionFormatter.format(left))
            .equals(canonicalizer.stableHash(ExpressionFormatter.format(right)));
    }

    private record AdditiveOffset(Expr symbolicPart, double offset) {
    }

    private String macroRuleId(ReusableRule rule) {
        return rule.id().startsWith("macro_") ? rule.id() : "macro_" + rule.id();
    }

    private String edgeKey(String fromExpression, String toExpression, String ruleId) {
        return fromExpression + "\u0001" + toExpression + "\u0001" + ruleId;
    }

    private PatternExpr toPatternExpr(RulePatternNode node) {
        if (node instanceof PatternNumber number) {
            return PatternExpr.num(number.value());
        }
        if (node instanceof PatternVariable variable) {
            return PatternExpr.var(variable.name());
        }
        if (node instanceof PatternFunction function) {
            PatternExpr[] converted = new PatternExpr[function.arguments().size()];
            for (int i = 0; i < converted.length; i++) {
                converted[i] = toPatternExpr(function.arguments().get(i));
            }
            return PatternExpr.fn(function.name(), converted);
        }
        PatternBinary binary = (PatternBinary) node;
        return PatternExpr.op(binary.op(), toPatternExpr(binary.left()), toPatternExpr(binary.right()));
    }
}
