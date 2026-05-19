package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class AstRewriteTransformationEngine implements TransformationEngine {
    private static final int DEFAULT_MAX_AST_SIZE_INCREASE = 12;
    private static final int DEFAULT_MAX_CANDIDATES_PER_STATE = 80;

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final List<RewriteRule> rules;
    private final int maxAstSizeIncreasePerStep;
    private final int maxCandidatesPerState;

    public AstRewriteTransformationEngine() {
        this(defaultRules());
    }

    public AstRewriteTransformationEngine(List<RewriteRule> rules) {
        this(rules, DEFAULT_MAX_AST_SIZE_INCREASE, DEFAULT_MAX_CANDIDATES_PER_STATE);
    }

    public AstRewriteTransformationEngine(List<RewriteRule> rules, int maxAstSizeIncreasePerStep, int maxCandidatesPerState) {
        this.rules = List.copyOf(rules);
        this.maxAstSizeIncreasePerStep = maxAstSizeIncreasePerStep;
        this.maxCandidatesPerState = maxCandidatesPerState;
    }

    public List<RewriteRule> rules() {
        return rules;
    }

    @Override
    public List<Transformation> transform(String expression) {
        Expr root;
        try {
            root = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
        } catch (IllegalArgumentException ex) {
            return List.of();
        }

        String formattedInput = ExpressionFormatter.format(root);
        int originalSize = canonicalizer.astNodeCount(formattedInput);
        Set<Transformation> transformations = new LinkedHashSet<>();
        for (RewriteResult result : rewriteEverywhere(root)) {
            String formatted = ExpressionFormatter.format(result.expression());
            if (formatted.equals(formattedInput)) {
                continue;
            }
            int growth = canonicalizer.astNodeCount(formatted) - originalSize;
            if (growth > maxAstSizeIncreasePerStep) {
                continue;
            }
            RewriteRule rule = result.rule();
            transformations.add(new Transformation(
                rule.id(),
                formatted,
                rule.kind(),
                rule.mayIncreaseComplexity(),
                rule.estimatedCostDelta(),
                rule.isEquivalencePreservingByConstruction(),
                rule.id() + ":" + result.sourceSubtreeHash()
            ));
            if (transformations.size() >= maxCandidatesPerState) {
                break;
            }
        }
        return new ArrayList<>(transformations);
    }

    private List<RewriteResult> rewriteEverywhere(Expr subtree) {
        List<RewriteResult> results = new ArrayList<>();
        String subtreeHash = canonicalizer.stableHash(ExpressionFormatter.format(subtree));
        for (RewriteRule rule : rules) {
            if (rule.matches(subtree)) {
                Expr rewritten = rule.apply(subtree);
                if (!rewritten.equals(subtree)) {
                    results.add(new RewriteResult(rule, rewritten, subtreeHash));
                }
            }
        }

        if (subtree instanceof BinaryExpr binaryExpr) {
            for (RewriteResult leftRewrite : rewriteEverywhere(binaryExpr.left())) {
                results.add(new RewriteResult(
                    leftRewrite.rule(),
                    new BinaryExpr(leftRewrite.expression(), binaryExpr.operator(), binaryExpr.right()),
                    leftRewrite.sourceSubtreeHash()
                ));
            }
            for (RewriteResult rightRewrite : rewriteEverywhere(binaryExpr.right())) {
                results.add(new RewriteResult(
                    rightRewrite.rule(),
                    new BinaryExpr(binaryExpr.left(), binaryExpr.operator(), rightRewrite.expression()),
                    rightRewrite.sourceSubtreeHash()
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
            simplify("ast_add_zero_right", op(BinaryOperator.ADD, a, num(0)), a),
            simplify("ast_add_zero_left", op(BinaryOperator.ADD, num(0), a), a),
            simplify("ast_multiply_one_right", op(BinaryOperator.MUL, a, num(1)), a),
            simplify("ast_multiply_one_left", op(BinaryOperator.MUL, num(1), a), a),
            simplify("ast_multiply_zero_right", op(BinaryOperator.MUL, a, num(0)), num(0)),
            simplify("ast_multiply_zero_left", op(BinaryOperator.MUL, num(0), a), num(0)),
            simplify("ast_subtract_zero", op(BinaryOperator.SUB, a, num(0)), a),
            simplify("ast_divide_one", op(BinaryOperator.DIV, a, num(1)), a),
            new DoubleTermRule(),
            normalize("ast_product_to_power_two", op(BinaryOperator.MUL, a, a), op(BinaryOperator.POW, a, num(2))),
            expand("ast_power_two_to_product", op(BinaryOperator.POW, a, num(2)), op(BinaryOperator.MUL, a, a), 3),
            new CombinePowersRule(),
            new PowerOfPowerRule(),
            expand("ast_distribute_left_add", op(BinaryOperator.MUL, a, op(BinaryOperator.ADD, b, c)),
                op(BinaryOperator.ADD, op(BinaryOperator.MUL, a, b), op(BinaryOperator.MUL, a, c)), 5),
            expand("ast_distribute_right_add", op(BinaryOperator.MUL, op(BinaryOperator.ADD, b, c), a),
                op(BinaryOperator.ADD, op(BinaryOperator.MUL, b, a), op(BinaryOperator.MUL, c, a)), 5),
            expand("ast_distribute_left_subtract", op(BinaryOperator.MUL, a, op(BinaryOperator.SUB, b, c)),
                op(BinaryOperator.SUB, op(BinaryOperator.MUL, a, b), op(BinaryOperator.MUL, a, c)), 5),
            expand("ast_distribute_right_subtract", op(BinaryOperator.MUL, op(BinaryOperator.SUB, b, c), a),
                op(BinaryOperator.SUB, op(BinaryOperator.MUL, b, a), op(BinaryOperator.MUL, c, a)), 5),
            new FactorCommonLeftRule(),
            new FactorCommonRightRule(),
            new CanonicalNormalizeRule()
        );
    }

    private static PatternRewriteRule simplify(String id, PatternExpr source, PatternExpr target) {
        return new PatternRewriteRule(id, source, target, RewriteKind.SIMPLIFY, false, -2, true);
    }

    private static PatternRewriteRule normalize(String id, PatternExpr source, PatternExpr target) {
        return new PatternRewriteRule(id, source, target, RewriteKind.NORMALIZE, false, 0, true);
    }

    private static PatternRewriteRule expand(String id, PatternExpr source, PatternExpr target, int costDelta) {
        return new PatternRewriteRule(id, source, target, RewriteKind.EXPAND, true, costDelta, true);
    }

    private static PatternExpr num(double value) {
        return PatternExpr.num(value);
    }

    private static PatternExpr op(BinaryOperator operator, PatternExpr left, PatternExpr right) {
        return PatternExpr.op(operator, left, right);
    }

    private record RewriteResult(RewriteRule rule, Expr expression, String sourceSubtreeHash) {
    }

    private abstract static class MetadataRule implements RewriteRule {
        private final RewriteKind kind;
        private final boolean mayIncreaseComplexity;
        private final int estimatedCostDelta;

        private MetadataRule(RewriteKind kind, boolean mayIncreaseComplexity, int estimatedCostDelta) {
            this.kind = kind;
            this.mayIncreaseComplexity = mayIncreaseComplexity;
            this.estimatedCostDelta = estimatedCostDelta;
        }

        @Override
        public RewriteKind kind() {
            return kind;
        }

        @Override
        public boolean mayIncreaseComplexity() {
            return mayIncreaseComplexity;
        }

        @Override
        public int estimatedCostDelta() {
            return estimatedCostDelta;
        }

        @Override
        public boolean isEquivalencePreservingByConstruction() {
            return true;
        }
    }

    private static final class CombinePowersRule extends MetadataRule {
        private CombinePowersRule() {
            super(RewriteKind.SIMPLIFY, false, -2);
        }

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

    private static final class PowerOfPowerRule extends MetadataRule {
        private PowerOfPowerRule() {
            super(RewriteKind.SIMPLIFY, false, -2);
        }

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

    private static final class FactorCommonLeftRule extends MetadataRule {
        private FactorCommonLeftRule() {
            super(RewriteKind.FACTOR, false, -3);
        }

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

    private static final class DoubleTermRule extends MetadataRule {
        private DoubleTermRule() {
            super(RewriteKind.NORMALIZE, false, 0);
        }

        @Override
        public String id() {
            return "ast_double_term";
        }

        @Override
        public boolean matches(Expr subtree) {
            return findDuplicateTermIndices(subtree) != null;
        }

        @Override
        public Expr apply(Expr subtree) {
            List<Expr> terms = flattenAddition(subtree);
            int[] indices = findDuplicateTermIndices(terms);
            if (indices == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            Expr duplicate = terms.get(indices[0]);
            List<Expr> rewritten = new ArrayList<>(terms);
            // Remove the later index first so the earlier index stays valid.
            rewritten.remove(indices[1]);
            rewritten.remove(indices[0]);
            rewritten.add(new BinaryExpr(new NumberExpr(2), BinaryOperator.MUL, duplicate));
            return buildAddition(rewritten);
        }

        private int[] findDuplicateTermIndices(Expr subtree) {
            if (!(subtree instanceof BinaryExpr addition) || addition.operator() != BinaryOperator.ADD) {
                return null;
            }
            return findDuplicateTermIndices(flattenAddition(subtree));
        }

        private int[] findDuplicateTermIndices(List<Expr> terms) {
            for (int i = 0; i < terms.size(); i++) {
                for (int j = i + 1; j < terms.size(); j++) {
                    if (terms.get(i).equals(terms.get(j))) {
                        return new int[] { i, j };
                    }
                }
            }
            return null;
        }

        private List<Expr> flattenAddition(Expr expression) {
            if (expression instanceof BinaryExpr binaryExpr && binaryExpr.operator() == BinaryOperator.ADD) {
                List<Expr> terms = new ArrayList<>();
                terms.addAll(flattenAddition(binaryExpr.left()));
                terms.addAll(flattenAddition(binaryExpr.right()));
                return terms;
            }
            return List.of(expression);
        }

        private Expr buildAddition(List<Expr> terms) {
            if (terms.isEmpty()) {
                return new NumberExpr(0);
            }
            Expr expression = terms.getFirst();
            for (int i = 1; i < terms.size(); i++) {
                expression = new BinaryExpr(expression, BinaryOperator.ADD, terms.get(i));
            }
            return expression;
        }
    }

    private static final class FactorCommonRightRule extends MetadataRule {
        private FactorCommonRightRule() {
            super(RewriteKind.FACTOR, false, -3);
        }

        @Override
        public String id() {
            return "ast_factor_common_right";
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
                new BinaryExpr(terms.leftRemainder(), BinaryOperator.ADD, terms.rightRemainder()),
                BinaryOperator.MUL,
                terms.common()
            );
        }

        private CommonTerms commonTerms(Expr subtree) {
            if (!(subtree instanceof BinaryExpr addition) || addition.operator() != BinaryOperator.ADD
                || !(addition.left() instanceof BinaryExpr leftProduct) || leftProduct.operator() != BinaryOperator.MUL
                || !(addition.right() instanceof BinaryExpr rightProduct) || rightProduct.operator() != BinaryOperator.MUL) {
                return null;
            }
            if (leftProduct.right().equals(rightProduct.right())) {
                return new CommonTerms(leftProduct.right(), leftProduct.left(), rightProduct.left());
            }
            return null;
        }
    }

    private static final class CanonicalNormalizeRule extends MetadataRule {
        private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        private final ExpressionParser parser = new ExpressionParser();

        private CanonicalNormalizeRule() {
            super(RewriteKind.NORMALIZE, false, 0);
        }

        @Override
        public String id() {
            return "ast_canonical_normalize";
        }

        @Override
        public boolean matches(Expr subtree) {
            return normalized(subtree) != null;
        }

        @Override
        public Expr apply(Expr subtree) {
            Expr normalized = normalized(subtree);
            if (normalized == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return normalized;
        }

        private Expr normalized(Expr subtree) {
            String formatted = ExpressionFormatter.format(subtree);
            String canonical = canonicalizer.canonicalize(formatted);
            if (canonical.equals(formatted)) {
                return null;
            }
            try {
                return parser.parse(new InputRequest(InputType.TERM, canonical)).terms().getFirst();
            } catch (IllegalArgumentException ex) {
                return null;
            }
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
}
