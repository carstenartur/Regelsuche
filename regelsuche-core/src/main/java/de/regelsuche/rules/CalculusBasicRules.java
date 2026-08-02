package de.regelsuche.rules;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.List;

/**
 * Curated calculus / exponential rewrite rules.
 *
 * <p>Currently focused on the exponential/logarithm interplay; derivative
 * and integral operators are reserved for a follow-up once the AST grows
 * dedicated operator nodes.</p>
 */
public final class CalculusBasicRules {
    private CalculusBasicRules() {
    }

    public static List<RewriteRule> rules() {
        return List.of(
            new ExpOfLogRule("log"),
            new ExpOfLogRule("ln"),
            new LogOfExpRule("log"),
            new LogOfExpRule("ln"),
            new ExpOfZeroRule()
        );
    }

    /** {@code exp(log(x)) -> x} with {@code x > 0}. */
    static final class ExpOfLogRule implements RewriteRule {
        private final String logName;

        ExpOfLogRule(String logName) {
            this.logName = logName;
        }

        @Override
        public String id() {
            return "calculus_exp_of_" + logName;
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
            if (inner == null) {
                return List.of();
            }
            return List.of(Assumption.positive(ExpressionFormatter.format(inner)));
        }

        private Expr inner(Expr subtree) {
            if (!(subtree instanceof FunctionExpr exp) || !"exp".equals(exp.name())
                || exp.arguments().size() != 1) {
                return null;
            }
            if (exp.arguments().get(0) instanceof FunctionExpr log
                && log.name().equals(logName)
                && log.arguments().size() == 1) {
                return log.arguments().get(0);
            }
            return null;
        }
    }

    /** {@code log(exp(x)) -> x} — unconditional. */
    static final class LogOfExpRule implements RewriteRule {
        private final String logName;

        LogOfExpRule(String logName) {
            this.logName = logName;
        }

        @Override
        public String id() {
            return "calculus_" + logName + "_of_exp";
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

        private Expr inner(Expr subtree) {
            if (!(subtree instanceof FunctionExpr log) || !log.name().equals(logName)
                || log.arguments().size() != 1) {
                return null;
            }
            if (log.arguments().get(0) instanceof FunctionExpr exp
                && "exp".equals(exp.name())
                && exp.arguments().size() == 1) {
                return exp.arguments().get(0);
            }
            return null;
        }
    }

    /** {@code exp(0) -> 1}. */
    static final class ExpOfZeroRule implements RewriteRule {
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
                && fn.arguments().get(0) instanceof NumberExpr n && n.value() == 0.0;
        }

        @Override
        public Expr apply(Expr subtree) {
            if (!matches(subtree)) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new NumberExpr(1);
        }
    }
}
