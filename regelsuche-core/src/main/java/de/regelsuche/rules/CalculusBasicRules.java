package de.regelsuche.rules;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.RequiredAssumptionTemplate;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.RewriteRule.RewriteApplicabilitySchemaProvider;
import java.util.List;

/** Basic real exponential/natural-logarithm rewrite rules. */
public final class CalculusBasicRules {
    private CalculusBasicRules() {
    }

    public static List<RewriteRule> rules() {
        return List.of(new ExpOfLnRule(), new LnOfExpRule(), new ExpOfZeroRule());
    }

    /** {@code exp(ln(x)) -> x} with {@code x > 0}. */
    static final class ExpOfLnRule implements RewriteApplicabilitySchemaProvider {
        @Override
        public String id() {
            return "calculus_exp_of_ln";
        }

        @Override
        public RewriteKind kind() {
            return RewriteKind.SIMPLIFY;
        }

        @Override
        public boolean mayIncreaseComplexity() {
            return false;
        }

        @Override
        public int estimatedCostDelta() {
            return -3;
        }

        @Override
        public boolean isEquivalencePreservingByConstruction() {
            return true;
        }

        @Override
        public boolean matches(Expr subtree) {
            return inner(subtree) != null;
        }

        @Override
        public Expr apply(Expr subtree) {
            Expr inner = inner(subtree);
            if (inner == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return inner;
        }

        @Override
        public boolean mayEmitAssumptions() {
            return true;
        }

        @Override
        public List<Assumption> assumptions(Expr subtree) {
            Expr inner = inner(subtree);
            return inner == null
                ? List.of()
                : List.of(Assumption.positive(ExpressionFormatter.format(inner)));
        }

        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            PatternExpr value = PatternExpr.var("X");
            return exactSource(
                PatternExpr.fn("exp", PatternExpr.fn("ln", value)),
                RequiredAssumptionTemplate.positive(value));
        }

        private static Expr inner(Expr subtree) {
            if (!(subtree instanceof FunctionExpr exp)
                    || !"exp".equals(exp.name())
                    || exp.arguments().size() != 1
                    || !(exp.arguments().getFirst() instanceof FunctionExpr ln)
                    || !"ln".equals(ln.name())
                    || ln.arguments().size() != 1) {
                return null;
            }
            return ln.arguments().getFirst();
        }
    }

    /** {@code ln(exp(x)) -> x} for every real {@code x}. */
    static final class LnOfExpRule implements RewriteApplicabilitySchemaProvider {
        @Override
        public String id() {
            return "calculus_ln_of_exp";
        }

        @Override
        public RewriteKind kind() {
            return RewriteKind.SIMPLIFY;
        }

        @Override
        public boolean mayIncreaseComplexity() {
            return false;
        }

        @Override
        public int estimatedCostDelta() {
            return -3;
        }

        @Override
        public boolean isEquivalencePreservingByConstruction() {
            return true;
        }

        @Override
        public boolean matches(Expr subtree) {
            return inner(subtree) != null;
        }

        @Override
        public Expr apply(Expr subtree) {
            Expr inner = inner(subtree);
            if (inner == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return inner;
        }

        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            PatternExpr value = PatternExpr.var("X");
            return exactSource(PatternExpr.fn(
                "ln", PatternExpr.fn("exp", value)));
        }

        private static Expr inner(Expr subtree) {
            if (!(subtree instanceof FunctionExpr ln)
                    || !"ln".equals(ln.name())
                    || ln.arguments().size() != 1
                    || !(ln.arguments().getFirst() instanceof FunctionExpr exp)
                    || !"exp".equals(exp.name())
                    || exp.arguments().size() != 1) {
                return null;
            }
            return exp.arguments().getFirst();
        }
    }

    /** {@code exp(0) -> 1}. */
    static final class ExpOfZeroRule implements RewriteApplicabilitySchemaProvider {
        @Override
        public String id() {
            return "calculus_exp_of_zero";
        }

        @Override
        public RewriteKind kind() {
            return RewriteKind.SIMPLIFY;
        }

        @Override
        public boolean mayIncreaseComplexity() {
            return false;
        }

        @Override
        public int estimatedCostDelta() {
            return -2;
        }

        @Override
        public boolean isEquivalencePreservingByConstruction() {
            return true;
        }

        @Override
        public boolean matches(Expr subtree) {
            return subtree instanceof FunctionExpr fn
                && "exp".equals(fn.name())
                && fn.arguments().size() == 1
                && fn.arguments().getFirst() instanceof NumberExpr n
                && n.value().equalsInteger(0);
        }

        @Override
        public Expr apply(Expr subtree) {
            if (!matches(subtree)) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new NumberExpr(1);
        }

        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            return exactSource(PatternExpr.fn("exp", PatternExpr.num(0)));
        }
    }
}
