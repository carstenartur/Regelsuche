package de.regelsuche.rules;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.RequiredAssumptionTemplate;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteApplicabilitySchemaProvider;
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
            new ExpOfZeroRule());
    }

    /** {@code exp(log(x)) -> x} with {@code x > 0}. */
    static final class ExpOfLogRule implements RewriteApplicabilitySchemaProvider {
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
            return inner == null
                ? List.of()
                : List.of(Assumption.positive(ExpressionFormatter.format(inner)));
        }

        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            PatternExpr value = PatternExpr.var("X");
            return exactSource(
                PatternExpr.fn("exp", PatternExpr.fn(logName, value)),
                RequiredAssumptionTemplate.positive(value));
        }

        private Expr inner(Expr subtree) {
            if (!(subtree instanceof FunctionExpr exp)
                    || !"exp".equals(exp.name())
                    || exp.arguments().size() != 1) {
                return null;
            }
            if (exp.arguments().getFirst() instanceof FunctionExpr log
                    && log.name().equals(logName)
                    && log.arguments().size() == 1) {
                return log.arguments().getFirst();
            }
            return null;
        }
    }

    /** {@code log(exp(x)) -> x} — unconditional. */
    static final class LogOfExpRule implements RewriteApplicabilitySchemaProvider {
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

        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            PatternExpr value = PatternExpr.var("X");
            return exactSource(
                PatternExpr.fn(logName, PatternExpr.fn("exp", value)));
        }

        private Expr inner(Expr subtree) {
            if (!(subtree instanceof FunctionExpr log)
                    || !log.name().equals(logName)
                    || log.arguments().size() != 1) {
                return null;
            }
            if (log.arguments().getFirst() instanceof FunctionExpr exp
                    && "exp".equals(exp.name())
                    && exp.arguments().size() == 1) {
                return exp.arguments().getFirst();
            }
            return null;
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
