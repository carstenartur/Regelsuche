package de.regelsuche.canonical;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExpressionCanonicalizer {
    private final ExpressionParser parser = new ExpressionParser();

    public String canonicalize(String expression) {
        try {
            Expr parsed = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
            return ExpressionFormatter.format(canonicalize(parsed));
        } catch (IllegalArgumentException ex) {
            return expression.trim().replaceAll("\\s+", " ");
        }
    }

    public String stableHash(String expression) {
        return sha256(canonicalize(expression));
    }

    public int astNodeCount(String expression) {
        try {
            Expr parsed = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
            return count(canonicalize(parsed));
        } catch (IllegalArgumentException ex) {
            return Math.max(1, expression.replaceAll("\\s+", "").length());
        }
    }

    public Expr canonicalize(Expr expression) {
        if (expression instanceof BinaryExpr binaryExpr) {
            return switch (binaryExpr.operator()) {
                case ADD, SUB -> canonicalizeAddition(binaryExpr);
                case MUL -> canonicalizeMultiplication(binaryExpr);
                case DIV -> new BinaryExpr(canonicalize(binaryExpr.left()), BinaryOperator.DIV, canonicalize(binaryExpr.right()));
                case POW -> canonicalizePower(binaryExpr);
            };
        }
        return expression;
    }

    private Expr canonicalizeAddition(BinaryExpr expression) {
        List<SignedTerm> terms = new ArrayList<>();
        collectTerms(expression, 1, terms);
        Map<String, TermBucket> buckets = new LinkedHashMap<>();
        for (SignedTerm signedTerm : terms) {
            Expr normalized = canonicalize(signedTerm.term());
            Coefficient coefficient = coefficientOf(normalized);
            int value = signedTerm.sign() * coefficient.value();
            if (value == 0) {
                continue;
            }
            String key = ExpressionFormatter.format(coefficient.term());
            buckets.computeIfAbsent(key, ignored -> new TermBucket(coefficient.term())).add(value);
        }

        List<TermBucket> ordered = buckets.values().stream()
            .filter(bucket -> bucket.coefficient() != 0)
            .sorted((left, right) -> ExpressionFormatter.format(left.term()).compareTo(ExpressionFormatter.format(right.term())))
            .toList();
        if (ordered.isEmpty()) {
            return new NumberExpr(0);
        }

        Expr result = null;
        for (TermBucket bucket : ordered) {
            Expr term = withCoefficient(bucket.term(), Math.abs(bucket.coefficient()));
            if (result == null) {
                result = bucket.coefficient() < 0 ? new BinaryExpr(new NumberExpr(0), BinaryOperator.SUB, term) : term;
            } else if (bucket.coefficient() < 0) {
                result = new BinaryExpr(result, BinaryOperator.SUB, term);
            } else {
                result = new BinaryExpr(result, BinaryOperator.ADD, term);
            }
        }
        return result;
    }

    private Expr canonicalizeMultiplication(BinaryExpr expression) {
        List<Expr> factors = new ArrayList<>();
        collectFactors(expression, factors);
        int numeric = 1;
        Map<String, FactorBucket> buckets = new LinkedHashMap<>();
        for (Expr factor : factors) {
            Expr normalized = canonicalize(factor);
            if (isNumber(normalized, 0)) {
                return new NumberExpr(0);
            }
            if (normalized instanceof NumberExpr numberExpr) {
                numeric *= (int) numberExpr.value();
                continue;
            }
            Power power = asPower(normalized);
            String key = ExpressionFormatter.format(power.base());
            buckets.computeIfAbsent(key, ignored -> new FactorBucket(power.base())).add(power.exponent());
        }
        if (numeric == 0) {
            return new NumberExpr(0);
        }

        List<Expr> ordered = new ArrayList<>();
        if (numeric != 1 || buckets.isEmpty()) {
            ordered.add(new NumberExpr(numeric));
        }
        buckets.values().stream()
            .filter(bucket -> bucket.exponent() != 0)
            .sorted((left, right) -> ExpressionFormatter.format(left.base()).compareTo(ExpressionFormatter.format(right.base())))
            .forEach(bucket -> ordered.add(bucket.exponent() == 1
                ? bucket.base()
                : new BinaryExpr(bucket.base(), BinaryOperator.POW, new NumberExpr(bucket.exponent()))));
        if (ordered.isEmpty()) {
            return new NumberExpr(1);
        }
        return leftAssociate(ordered, BinaryOperator.MUL);
    }

    private Expr canonicalizePower(BinaryExpr expression) {
        Expr base = canonicalize(expression.left());
        Expr exponent = canonicalize(expression.right());
        if (isNumber(exponent, 0)) {
            return new NumberExpr(1);
        }
        if (isNumber(exponent, 1)) {
            return base;
        }
        return new BinaryExpr(base, BinaryOperator.POW, exponent);
    }

    private void collectTerms(Expr expression, int sign, List<SignedTerm> terms) {
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.operator() == BinaryOperator.ADD) {
            collectTerms(binaryExpr.left(), sign, terms);
            collectTerms(binaryExpr.right(), sign, terms);
        } else if (expression instanceof BinaryExpr binaryExpr && binaryExpr.operator() == BinaryOperator.SUB) {
            collectTerms(binaryExpr.left(), sign, terms);
            collectTerms(binaryExpr.right(), -sign, terms);
        } else if (!isNumber(expression, 0)) {
            terms.add(new SignedTerm(sign, expression));
        }
    }

    private void collectFactors(Expr expression, List<Expr> factors) {
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.operator() == BinaryOperator.MUL) {
            collectFactors(binaryExpr.left(), factors);
            collectFactors(binaryExpr.right(), factors);
        } else if (!isNumber(expression, 1)) {
            factors.add(expression);
        }
    }

    private Coefficient coefficientOf(Expr expression) {
        if (expression instanceof BinaryExpr product && product.operator() == BinaryOperator.MUL
            && product.left() instanceof NumberExpr numberExpr) {
            return new Coefficient((int) numberExpr.value(), product.right());
        }
        if (expression instanceof NumberExpr numberExpr) {
            return new Coefficient((int) numberExpr.value(), new NumberExpr(1));
        }
        return new Coefficient(1, expression);
    }

    private Expr withCoefficient(Expr term, int coefficient) {
        if (isNumber(term, 1)) {
            return new NumberExpr(coefficient);
        }
        if (coefficient == 1) {
            return term;
        }
        return new BinaryExpr(new NumberExpr(coefficient), BinaryOperator.MUL, term);
    }

    private Expr leftAssociate(List<Expr> expressions, BinaryOperator operator) {
        Expr result = expressions.getFirst();
        for (int i = 1; i < expressions.size(); i++) {
            result = new BinaryExpr(result, operator, expressions.get(i));
        }
        return result;
    }

    private Power asPower(Expr expression) {
        if (expression instanceof BinaryExpr power && power.operator() == BinaryOperator.POW
            && power.right() instanceof NumberExpr exponent) {
            return new Power(power.left(), (int) exponent.value());
        }
        return new Power(expression, 1);
    }

    private boolean isNumber(Expr expression, int value) {
        return expression instanceof NumberExpr numberExpr && numberExpr.value() == value;
    }

    private int count(Expr expression) {
        if (expression instanceof BinaryExpr binaryExpr) {
            return 1 + count(binaryExpr.left()) + count(binaryExpr.right());
        }
        return 1;
    }

    private String sha256(String expression) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(expression.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record SignedTerm(int sign, Expr term) {
    }

    private record Coefficient(int value, Expr term) {
    }

    private record Power(Expr base, int exponent) {
    }

    private static final class TermBucket {
        private final Expr term;
        private int coefficient;

        private TermBucket(Expr term) {
            this.term = term;
        }

        private void add(int value) {
            coefficient += value;
        }

        private Expr term() {
            return term;
        }

        private int coefficient() {
            return coefficient;
        }
    }

    private static final class FactorBucket {
        private final Expr base;
        private int exponent;

        private FactorBucket(Expr base) {
            this.base = base;
        }

        private void add(int value) {
            exponent += value;
        }

        private Expr base() {
            return base;
        }

        private int exponent() {
            return exponent;
        }
    }
}
