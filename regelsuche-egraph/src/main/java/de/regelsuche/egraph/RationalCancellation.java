package de.regelsuche.egraph;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionEvaluation;
import de.regelsuche.assumption.AssumptionEvaluationEvidence;
import de.regelsuche.assumption.AssumptionEvaluator;
import de.regelsuche.assumption.AssumptionEvaluatorPortfolio;
import de.regelsuche.assumption.AssumptionEvaluationDisposition;
import de.regelsuche.assumption.AssumptionTruthValue;
import de.regelsuche.assumption.KnownAssumptionEvaluator;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.egraph.EqualitySaturation.GuardDecision;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Both structural orientations of the built-in scalar (x*y)/x cancellation. */
final class RationalCancellation {
    static final int MAX_DECISIONS = 256;
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}][\\p{L}\\p{N}_]*");
    private static final PatternExpr FACTOR = PatternExpr.var("factor");
    private static final PatternExpr REST = PatternExpr.var("rest");
    private static final List<PatternExpr> SOURCES = List.of(
        source(FACTOR, REST), source(REST, FACTOR));
    private static final AssumptionEvaluatorPortfolio LOCAL = AssumptionEvaluatorPortfolio.localOnly();
    private static final AssumptionEvaluatorPortfolio UNSUPPORTED = new AssumptionEvaluatorPortfolio(
        List.of(new UnsupportedFactorEvaluator()));
    private static final AssumptionEvaluatorPortfolio LITERAL = new AssumptionEvaluatorPortfolio(
        List.of(KnownAssumptionEvaluator.INSTANCE, new LiteralNonZeroEvaluator()));

    final List<Assumption> assumptions;
    final List<GuardDecision> decisions = new ArrayList<>();
    boolean undecided;
    boolean decisionLimit;

    RationalCancellation(List<Assumption> assumptions) {
        this.assumptions = List.copyOf(assumptions);
    }

    private static PatternExpr source(PatternExpr left, PatternExpr right) {
        return PatternExpr.op(BinaryOperator.DIV, PatternExpr.op(BinaryOperator.MUL, left, right), FACTOR);
    }

    void apply(EGraph graph, EGraphPatternMatcher matcher, RewriteRule rule, List<EClassId> candidates,
            EqualitySaturation.Run run) {
        for (int i = 0; i < SOURCES.size(); i++) {
            var pattern = SOURCES.get(i);
            List<EGraphPatternMatcher.Match> matches;
            try {
                matches = matcher.matchAll(rule.id(), pattern, candidates);
            } catch (RuntimeException failure) {
                run.diagnostics.record(rule.id(), null, SaturationDiagnostics.Outcome.RULE_FAILURE,
                    SaturationDiagnostics.Stage.MATCHES, failure);
                continue;
            }
            for (var match : matches) {
                var rest = match.bindings().get("rest");
                if (graph.areEquivalent(match.root(), rest)) continue;
                if (decisions.size() == MAX_DECISIONS) {
                    decisionLimit = true;
                    return;
                }
                try {
                    consider(graph, rule, match, rest, i == 0, run);
                } catch (RuntimeException failure) {
                    run.diagnostics.record(rule.id(), match.root(), SaturationDiagnostics.Outcome.RULE_FAILURE,
                        SaturationDiagnostics.Stage.ASSUMPTIONS, failure);
                }
            }
        }
    }

    private void consider(EGraph graph, RewriteRule rule, EGraphPatternMatcher.Match match,
            EClassId rest, boolean factorOnLeft, EqualitySaturation.Run run) {
        Expr factor = graph.extract(match.bindings().get("factor"), node -> 1);
        Expr after = graph.extract(rest, node -> 1);
        Expr product = factorOnLeft ? new BinaryExpr(factor, BinaryOperator.MUL, after)
            : new BinaryExpr(after, BinaryOperator.MUL, factor);
        Expr before = new BinaryExpr(product, BinaryOperator.DIV, factor);
        var evaluation = evaluate(factor);
        if (evaluation.isSatisfied()) {
            graph.union(match.root(), rest);
            run.applied(rule.id(), new SaturationProvenance.Witness(rule.id(), before, after));
        }
        undecided |= evaluation.isInconclusive();
        decisions.add(new GuardDecision(rule.id(), before, after, evaluation, evaluation.isSatisfied()));
    }

    private AssumptionEvaluation evaluate(Expr factor) {
        if (factor instanceof NumberExpr number) {
            return LITERAL.evaluate(Assumption.nonZero(number.value().canonicalText()), assumptions);
        }
        if (factor instanceof VariableExpr variable && IDENTIFIER.matcher(variable.name()).matches()) {
            return LOCAL.evaluate(Assumption.nonZero(variable.name()), assumptions);
        }
        // A formatted compound could collide with a free symbol. Only exact atomic
        // identities are currently authorized; the concrete factor is retained in before.
        var requirement = Assumption.nonZero(ExpressionFormatter.format(factor));
        return UNSUPPORTED.evaluate(requirement, assumptions);
    }

    private static final class UnsupportedFactorEvaluator implements AssumptionEvaluator {
        public String id() { return "egraph.compound-nonzero"; }
        public String revision() { return "1"; }

        public AssumptionEvaluationEvidence evaluate(Assumption required, List<Assumption> known) {
            return AssumptionEvaluationEvidence.inconclusive(id(), revision(), AssumptionEvaluationDisposition.UNSUPPORTED,
                "The guard requires an exact rational literal or a variable identifier.", "");
        }
    }

    private static final class LiteralNonZeroEvaluator implements AssumptionEvaluator {
        public String id() { return "egraph.exact-rational-nonzero"; }
        public String revision() { return "1"; }

        public AssumptionEvaluationEvidence evaluate(Assumption required, List<Assumption> known) {
            ExactRational value = ExactRational.fromCanonicalText(required.symbols().getFirst());
            return AssumptionEvaluationEvidence.evaluated(id(), revision(), value.isZero()
                ? AssumptionTruthValue.FALSE : AssumptionTruthValue.TRUE, "Exact rational literal", value.canonicalText());
        }
    }
}
