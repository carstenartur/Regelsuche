package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class AstRewriteTransformationEngine implements TransformationEngine {
    private final ExpressionParser parser = new ExpressionParser();
    private final List<RewriteRule> rules;

    public AstRewriteTransformationEngine() {
        this(defaultRules());
    }

    public AstRewriteTransformationEngine(List<RewriteRule> rules) {
        this.rules = List.copyOf(rules);
    }

    @Override
    public List<Transformation> transform(String expression) {
        Expr root;
        try {
            root = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
        } catch (IllegalArgumentException ex) {
            return List.of();
        }

        Set<Transformation> transformations = new LinkedHashSet<>();
        for (RewriteResult result : rewriteEverywhere(root)) {
            String formatted = ExpressionFormatter.format(result.expression());
            if (!formatted.equals(expression)) {
                transformations.add(new Transformation(result.ruleId(), formatted));
            }
        }
        return new ArrayList<>(transformations);
    }

    private List<RewriteResult> rewriteEverywhere(Expr subtree) {
        List<RewriteResult> results = new ArrayList<>();
        for (RewriteRule rule : rules) {
            if (rule.matches(subtree)) {
                Expr rewritten = rule.apply(subtree);
                if (!rewritten.equals(subtree)) {
                    results.add(new RewriteResult(rule.id(), rewritten));
                }
            }
        }

        if (subtree instanceof BinaryExpr binaryExpr) {
            for (RewriteResult leftRewrite : rewriteEverywhere(binaryExpr.left())) {
                results.add(new RewriteResult(
                    leftRewrite.ruleId(),
                    new BinaryExpr(leftRewrite.expression(), binaryExpr.operator(), binaryExpr.right())
                ));
            }
            for (RewriteResult rightRewrite : rewriteEverywhere(binaryExpr.right())) {
                results.add(new RewriteResult(
                    rightRewrite.ruleId(),
                    new BinaryExpr(binaryExpr.left(), binaryExpr.operator(), rightRewrite.expression())
                ));
            }
        }
        return results;
    }

    public static List<RewriteRule> defaultRules() {
        PatternExpr a = PatternExpr.var("A");
        PatternExpr b = PatternExpr.var("B");
        PatternExpr c = PatternExpr.var("C");
        return List.of(
            new PatternRewriteRule("ast_add_zero_right", op(BinaryOperator.ADD, a, num(0)), a),
            new PatternRewriteRule("ast_add_zero_left", op(BinaryOperator.ADD, num(0), a), a),
            new PatternRewriteRule("ast_multiply_one_right", op(BinaryOperator.MUL, a, num(1)), a),
            new PatternRewriteRule("ast_multiply_one_left", op(BinaryOperator.MUL, num(1), a), a),
            new PatternRewriteRule("ast_multiply_zero_right", op(BinaryOperator.MUL, a, num(0)), num(0)),
            new PatternRewriteRule("ast_multiply_zero_left", op(BinaryOperator.MUL, num(0), a), num(0)),
            new PatternRewriteRule("ast_subtract_zero", op(BinaryOperator.SUB, a, num(0)), a),
            new PatternRewriteRule("ast_divide_one", op(BinaryOperator.DIV, a, num(1)), a),
            new PatternRewriteRule("ast_double_term", op(BinaryOperator.ADD, a, a), op(BinaryOperator.MUL, num(2), a)),
            new PatternRewriteRule("ast_square_product", op(BinaryOperator.MUL, a, a), op(BinaryOperator.POW, a, num(2))),
            new CombinePowersRule(),
            new PowerOfPowerRule(),
            new PatternRewriteRule(
                "ast_distribute_left",
                op(BinaryOperator.MUL, a, op(BinaryOperator.ADD, b, c)),
                op(BinaryOperator.ADD, op(BinaryOperator.MUL, a, b), op(BinaryOperator.MUL, a, c))
            ),
            new PatternRewriteRule(
                "ast_distribute_right",
                op(BinaryOperator.MUL, op(BinaryOperator.ADD, b, c), a),
                op(BinaryOperator.ADD, op(BinaryOperator.MUL, b, a), op(BinaryOperator.MUL, c, a))
            ),
            new FactorCommonLeftRule(),
            new SquareSumToProductRule(),
            new BinomialSquareExpansionRule(),
            new DifferenceOfSquaresExpansionRule()
        );
    }

    private static PatternExpr num(double value) {
        return PatternExpr.num(value);
    }

    private static PatternExpr op(BinaryOperator operator, PatternExpr left, PatternExpr right) {
        return PatternExpr.op(operator, left, right);
    }

    private record RewriteResult(String ruleId, Expr expression) {
    }

    private static final class CombinePowersRule implements RewriteRule {
        @Override
        public String id() {
            return "ast_combine_powers";
        }

        @Override
        public boolean matches(Expr subtree) {
            return exponents(subtree) != null;
        }

        @Override
        public Expr apply(Expr subtree) {
            PowerPair pair = exponents(subtree);
            if (pair == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new BinaryExpr(pair.base(), BinaryOperator.POW, new NumberExpr(pair.leftExponent() + pair.rightExponent()));
        }

        private PowerPair exponents(Expr subtree) {
            if (!(subtree instanceof BinaryExpr multiplication) || multiplication.operator() != BinaryOperator.MUL) {
                return null;
            }
            Power left = asPower(multiplication.left());
            Power right = asPower(multiplication.right());
            if (left != null && right != null && left.base().equals(right.base())) {
                return new PowerPair(left.base(), left.exponent(), right.exponent());
            }
            return null;
        }
    }

    private static final class PowerOfPowerRule implements RewriteRule {
        @Override
        public String id() {
            return "ast_power_of_power";
        }

        @Override
        public boolean matches(Expr subtree) {
            return powers(subtree) != null;
        }

        @Override
        public Expr apply(Expr subtree) {
            PowerPair pair = powers(subtree);
            if (pair == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new BinaryExpr(pair.base(), BinaryOperator.POW, new NumberExpr(pair.leftExponent() * pair.rightExponent()));
        }

        private PowerPair powers(Expr subtree) {
            if (!(subtree instanceof BinaryExpr outerPower) || outerPower.operator() != BinaryOperator.POW
                || !(outerPower.right() instanceof NumberExpr outerExponent)) {
                return null;
            }
            Power innerPower = asPower(outerPower.left());
            if (innerPower == null) {
                return null;
            }
            return new PowerPair(innerPower.base(), innerPower.exponent(), outerExponent.value());
        }
    }

    private static final class FactorCommonLeftRule implements RewriteRule {
        @Override
        public String id() {
            return "ast_factor_common_left";
        }

        @Override
        public boolean matches(Expr subtree) {
            return commonTerms(subtree) != null;
        }

        @Override
        public Expr apply(Expr subtree) {
            CommonTerms terms = commonTerms(subtree);
            if (terms == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new BinaryExpr(
                terms.common(),
                BinaryOperator.MUL,
                new BinaryExpr(terms.leftRemainder(), BinaryOperator.ADD, terms.rightRemainder())
            );
        }

        private CommonTerms commonTerms(Expr subtree) {
            if (!(subtree instanceof BinaryExpr addition) || addition.operator() != BinaryOperator.ADD
                || !(addition.left() instanceof BinaryExpr leftProduct) || leftProduct.operator() != BinaryOperator.MUL
                || !(addition.right() instanceof BinaryExpr rightProduct) || rightProduct.operator() != BinaryOperator.MUL) {
                return null;
            }
            if (leftProduct.left().equals(rightProduct.left())) {
                return new CommonTerms(leftProduct.left(), leftProduct.right(), rightProduct.right());
            }
            return null;
        }
    }

    private static final class SquareSumToProductRule implements RewriteRule {
        @Override
        public String id() {
            return "ast_square_sum_to_product";
        }

        @Override
        public boolean matches(Expr subtree) {
            return sumSquared(subtree) != null;
        }

        @Override
        public Expr apply(Expr subtree) {
            Expr sum = sumSquared(subtree);
            if (sum == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new BinaryExpr(sum, BinaryOperator.MUL, sum);
        }

        private Expr sumSquared(Expr subtree) {
            if (subtree instanceof BinaryExpr power && power.operator() == BinaryOperator.POW
                && power.left() instanceof BinaryExpr sum && sum.operator() == BinaryOperator.ADD
                && power.right() instanceof NumberExpr exponent && exponent.value() == 2) {
                return power.left();
            }
            return null;
        }
    }

    private static final class BinomialSquareExpansionRule implements RewriteRule {
        @Override
        public String id() {
            return "ast_expand_binomial_square_product";
        }

        @Override
        public boolean matches(Expr subtree) {
            return summands(subtree) != null;
        }

        @Override
        public Expr apply(Expr subtree) {
            Summands summands = summands(subtree);
            if (summands == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            Expr a = summands.left();
            Expr b = summands.right();
            Expr first = new BinaryExpr(a, BinaryOperator.POW, new NumberExpr(2));
            Expr middle = new BinaryExpr(new BinaryExpr(new NumberExpr(2), BinaryOperator.MUL, a), BinaryOperator.MUL, b);
            Expr last = new BinaryExpr(b, BinaryOperator.POW, new NumberExpr(2));
            BinaryOperator middleOperator = summands.operator() == BinaryOperator.SUB ? BinaryOperator.SUB : BinaryOperator.ADD;
            return new BinaryExpr(new BinaryExpr(first, middleOperator, middle), BinaryOperator.ADD, last);
        }

        private Summands summands(Expr subtree) {
            if (!(subtree instanceof BinaryExpr product) || product.operator() != BinaryOperator.MUL
                || !product.left().equals(product.right())
                || !(product.left() instanceof BinaryExpr sum)
                || (sum.operator() != BinaryOperator.ADD && sum.operator() != BinaryOperator.SUB)) {
                return null;
            }
            return new Summands(sum.left(), sum.right(), sum.operator());
        }
    }

    private static final class DifferenceOfSquaresExpansionRule implements RewriteRule {
        @Override
        public String id() {
            return "ast_expand_difference_of_squares_product";
        }

        @Override
        public boolean matches(Expr subtree) {
            return factors(subtree) != null;
        }

        @Override
        public Expr apply(Expr subtree) {
            Summands summands = factors(subtree);
            if (summands == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new BinaryExpr(
                new BinaryExpr(summands.left(), BinaryOperator.POW, new NumberExpr(2)),
                BinaryOperator.SUB,
                new BinaryExpr(summands.right(), BinaryOperator.POW, new NumberExpr(2))
            );
        }

        private Summands factors(Expr subtree) {
            if (!(subtree instanceof BinaryExpr product) || product.operator() != BinaryOperator.MUL
                || !(product.left() instanceof BinaryExpr plus) || plus.operator() != BinaryOperator.ADD
                || !(product.right() instanceof BinaryExpr minus) || minus.operator() != BinaryOperator.SUB) {
                return null;
            }
            if (plus.left().equals(minus.left()) && plus.right().equals(minus.right())) {
                return new Summands(plus.left(), plus.right(), BinaryOperator.SUB);
            }
            return null;
        }
    }

    private static Power asPower(Expr expression) {
        if (expression instanceof BinaryExpr power && power.operator() == BinaryOperator.POW
            && power.right() instanceof NumberExpr exponent) {
            return new Power(power.left(), exponent.value());
        }
        return null;
    }

    private record Power(Expr base, double exponent) {
    }

    private record PowerPair(Expr base, double leftExponent, double rightExponent) {
    }

    private record CommonTerms(Expr common, Expr leftRemainder, Expr rightRemainder) {
    }

    private record Summands(Expr left, Expr right, BinaryOperator operator) {
    }
}
