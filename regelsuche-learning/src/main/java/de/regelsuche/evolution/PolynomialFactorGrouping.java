package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.retention.RetainedOperation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Finite common-factor proposals across an entire associative sum, including nonadjacent terms. */
final class PolynomialFactorGrouping {
    private PolynomialFactorGrouping() { }

    static List<Expr> candidates(Expr source) {
        RetainedOperation.work(1);
        if (!(source instanceof BinaryExpr binary)
                || binary.operator() != BinaryOperator.ADD && binary.operator() != BinaryOperator.SUB) return List.of();
        var terms = new ArrayList<Expr>();
        collectSum(source, false, terms);
        if (terms.size() > 32) return List.of();
        var products = new ArrayList<List<Expr>>();
        var occurrences = new LinkedHashMap<Expr, List<Integer>>();
        for (int index = 0; index < terms.size(); index++) {
            var factors = new ArrayList<Expr>();
            collectProduct(terms.get(index), factors);
            products.add(factors);
            for (Expr factor : new java.util.LinkedHashSet<>(factors)) {
                RetainedOperation.work(1);
                if (factor instanceof NumberExpr number && number.value().abs().isOne()) continue;
                occurrences.computeIfAbsent(factor, ignored -> new ArrayList<>()).add(index);
            }
        }
        var results = new ArrayList<Expr>();
        for (var occurrence : occurrences.entrySet()) {
            RetainedOperation.work(1);
            if (occurrence.getValue().size() < 2) continue;
            var remainders = new ArrayList<Expr>();
            var untouched = new ArrayList<Expr>();
            for (int index = 0; index < terms.size(); index++) {
                RetainedOperation.work(1);
                if (occurrence.getValue().contains(index)) {
                    var factors = new ArrayList<>(products.get(index));
                    RetainedOperation.work(factors.size() + 1L);
                    factors.remove(occurrence.getKey());
                    remainders.add(join(factors, BinaryOperator.MUL));
                } else untouched.add(terms.get(index));
            }
            untouched.add(new BinaryExpr(occurrence.getKey(), BinaryOperator.MUL, join(remainders, BinaryOperator.ADD)));
            results.add(new ExpressionCanonicalizer().canonicalize(join(untouched, BinaryOperator.ADD)));
            if (results.size() == 16) break;
        }
        return List.copyOf(results);
    }

    private static void collectSum(Expr source, boolean negative, List<Expr> terms) {
        RetainedOperation.work(1);
        if (source instanceof BinaryExpr binary && (binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.SUB)) {
            collectSum(binary.left(), negative, terms);
            collectSum(binary.right(), negative ^ binary.operator() == BinaryOperator.SUB, terms);
        } else terms.add(negative ? new BinaryExpr(new NumberExpr(-1), BinaryOperator.MUL, source) : source);
    }

    private static void collectProduct(Expr source, List<Expr> factors) {
        RetainedOperation.work(1);
        if (source instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL) {
            collectProduct(binary.left(), factors);
            collectProduct(binary.right(), factors);
        } else if (source instanceof BinaryExpr binary && binary.operator() == BinaryOperator.POW
                && binary.right() instanceof NumberExpr exponent && exponent.value().isInteger()
                && exponent.value().signum() > 0 && exponent.value().compareTo(de.regelsuche.scalar.ExactRational.integer(32)) <= 0) {
            for (int index = 0; index < exponent.value().intValueExact(); index++) {
                RetainedOperation.work(1);
                factors.add(binary.left());
            }
        } else factors.add(source);
    }

    private static Expr join(List<Expr> expressions, BinaryOperator operator) {
        RetainedOperation.work(expressions.size() + 1L);
        if (expressions.isEmpty()) return new NumberExpr(operator == BinaryOperator.MUL ? 1 : 0);
        Expr result = expressions.getFirst();
        for (int index = 1; index < expressions.size(); index++) result = new BinaryExpr(result, operator, expressions.get(index));
        return result;
    }
}
