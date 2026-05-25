package de.regelsuche.mining;

import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.inventory.ReusableRule;
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
    private final Map<String, MacroMoveExpansion> expansionsByEdge = new HashMap<>();

    public MacroMoveTransformationEngine(
        TransformationEngine baseEngine,
        GoalAwareMacroMoveSelector selector
    ) {
        this(baseEngine, selector, null, Map.of(), List.of());
    }

    public MacroMoveTransformationEngine(
        TransformationEngine baseEngine,
        GoalAwareMacroMoveSelector selector,
        String goalExpression,
        Map<String, List<TransformationStep>> atomicStepsByRuleId
    ) {
        this(baseEngine, selector, goalExpression, atomicStepsByRuleId, List.of());
    }

    public MacroMoveTransformationEngine(
        TransformationEngine baseEngine,
        GoalAwareMacroMoveSelector selector,
        String goalExpression,
        Map<String, List<TransformationStep>> atomicStepsByRuleId,
        List<String> carriedAssumptions
    ) {
        if (baseEngine == null || selector == null) {
            throw new IllegalArgumentException("baseEngine and selector are required");
        }
        this.baseEngine = baseEngine;
        this.selector = selector;
        this.goalExpression = goalExpression;
        this.carriedAssumptions = carriedAssumptions == null ? List.of() : List.copyOf(carriedAssumptions);
        this.atomicStepsByRuleId = atomicStepsByRuleId == null ? Map.of() : Map.copyOf(atomicStepsByRuleId);
    }

    @Override
    public List<Transformation> transform(String expression) {
        List<Transformation> result = new ArrayList<>(baseEngine.transform(expression));
        for (ReusableRule rule : selector.selectFor(expression, goalExpression, carriedAssumptions)) {
            result.addAll(applyMacro(expression, rule));
        }
        return result;
    }

    public Optional<MacroMoveExpansion> expansionFor(String fromExpression, String toExpression, String ruleId) {
        return Optional.ofNullable(expansionsByEdge.get(edgeKey(fromExpression, toExpression, ruleId)));
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
        AstRewriteTransformationEngine macroEngine = new AstRewriteTransformationEngine(List.of(rewriteRule));
        List<Transformation> transformations = macroEngine.transform(expression);
        for (Transformation transformation : transformations) {
            MacroMoveExpansion expansion = new MacroMoveExpansion(
                transformation.rule(),
                expression,
                transformation.transformedExpression(),
                atomicStepsByRuleId.getOrDefault(rule.id(), List.of()),
                rule.supportingPathIds(),
                Math.max(1.0, rule.supportingPathIds().isEmpty() ? 1.0 : rule.supportingPathIds().size()),
                false
            );
            expansionsByEdge.put(edgeKey(expression, transformation.transformedExpression(), transformation.rule()), expansion);
        }
        return transformations;
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
