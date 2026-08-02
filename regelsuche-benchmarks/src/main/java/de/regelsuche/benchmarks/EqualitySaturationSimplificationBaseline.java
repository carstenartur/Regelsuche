package de.regelsuche.benchmarks;

import de.regelsuche.ast.Expr;
import de.regelsuche.egraph.EClassId;
import de.regelsuche.egraph.EGraph;
import de.regelsuche.egraph.ENode;
import de.regelsuche.egraph.EqualitySaturation;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Equality-saturation competitor of the target-free simplification track.
 *
 * <p>The track previously compared exactly one rewriting paradigm against an
 * external CAS: a path-based best-first search. That leaves open whether an
 * observed miss is a property of the rewrite inventory or of the search
 * procedure over it. This competitor removes that ambiguity by running the
 * <em>same</em> default inventory through equality saturation: all matches of
 * all rules are applied into an e-graph until a fix point, and the cheapest
 * representative is extracted afterwards. A form the inventory can reach at all
 * is therefore reachable here regardless of any frontier ordering.</p>
 *
 * <p>Like every competitor of this track it receives the input expression and
 * the declared case assumptions only — never the reference simplest form.</p>
 *
 * <p><b>Side conditions.</b> Saturation merges many rewrites into one e-class
 * and does not retain which rewrite produced the extracted representative, so
 * exact per-step side conditions are not observable. The competitor therefore
 * discharges conservatively: if any rule that <em>can</em> emit a side
 * condition fired, the extracted expression only counts when the case declares
 * a matching assumption context. This over-approximation is declared as the
 * configuration limitation
 * {@code SATURATION_SIDE_CONDITIONS_APPROXIMATED_BY_RULE_IDENTITY}.</p>
 */
final class EqualitySaturationSimplificationBaseline {
    static final String BACKEND_ID = "regelsuche-equality-saturation";
    static final String BACKEND_VERSION = "1";

    private static final int MAX_ITERATIONS = 12;
    private static final int MAX_NODES = 10_000;

    private final ExpressionParser parser = new ExpressionParser();
    private final List<RewriteRule> rules;
    private final Set<String> sideConditionRuleIds;
    private final String configurationHash;

    EqualitySaturationSimplificationBaseline() {
        this(AstRewriteTransformationEngine.defaultRules());
    }

    EqualitySaturationSimplificationBaseline(List<RewriteRule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        this.sideConditionRuleIds = Set.copyOf(new TreeSet<>(this.rules.stream()
            .filter(RewriteRule::mayEmitAssumptions)
            .map(RewriteRule::id)
            .toList()));
        this.configurationHash = SolverIr.sha256(
            "role=TARGET_FREE_SIMPLIFICATION"
                + "\nengine=equality-saturation"
                + "\nmaxIterations=" + MAX_ITERATIONS
                + "\nmaxNodes=" + MAX_NODES
                + "\ninventory="
                    + this.rules.stream().map(RewriteRule::id).sorted().toList());
    }

    String backendId() {
        return BACKEND_ID;
    }

    String backendVersion() {
        return BACKEND_VERSION;
    }

    String configurationHash() {
        return configurationHash;
    }

    String environmentIdentity() {
        return "java=21\nsaturation-kernel=regelsuche-egraph/v1";
    }

    /**
     * Saturates {@code inputExpression} over the shared rewrite inventory.
     *
     * @param inputExpression the only expression handed to the competitor
     * @return the extracted representative plus the observable side-condition
     *     evidence, never {@code null}
     */
    Saturation simplify(String inputExpression) {
        Objects.requireNonNull(inputExpression, "inputExpression");
        Expr root;
        try {
            root = parser.parse(
                new InputRequest(InputType.TERM, inputExpression))
                .terms().getFirst();
        } catch (RuntimeException exception) {
            return new Saturation("", 0, 0, false, List.of());
        }
        EGraph graph = new EGraph();
        EClassId rootClass = graph.addExpression(root);
        EqualitySaturation saturation = new EqualitySaturation(
            rules,
            new EqualitySaturation.Config(MAX_ITERATIONS, MAX_NODES, true));
        EqualitySaturation.Result result = saturation.saturate(
            graph, rootClass, EqualitySaturationSimplificationBaseline::cost);
        Map<String, Integer> applied = result.stats().appliedRules();
        List<String> conditionalRules = applied.keySet().stream()
            .filter(sideConditionRuleIds::contains)
            .sorted()
            .toList();
        return new Saturation(
            ExpressionFormatter.format(result.expression()),
            result.stats().enodes(),
            result.stats().rulesFired(),
            result.stats().saturated(),
            conditionalRules);
    }

    private static int cost(ENode node) {
        return node.isLeaf() ? 0 : 1;
    }

    /**
     * One saturation outcome.
     *
     * @param producedExpression the extracted cheapest representative
     * @param exploredNodes e-nodes retained in the saturated graph
     * @param firedRewrites total rule applications
     * @param saturated whether a fix point was reached inside the budget
     * @param conditionalRuleIds fired rules that can emit a side condition
     */
    record Saturation(
        String producedExpression,
        int exploredNodes,
        int firedRewrites,
        boolean saturated,
        List<String> conditionalRuleIds
    ) {
        Saturation {
            producedExpression = producedExpression == null
                ? "" : producedExpression.trim();
            conditionalRuleIds = conditionalRuleIds == null
                ? List.of() : List.copyOf(conditionalRuleIds);
        }

        boolean produced() {
            return !producedExpression.isEmpty();
        }
    }
}
