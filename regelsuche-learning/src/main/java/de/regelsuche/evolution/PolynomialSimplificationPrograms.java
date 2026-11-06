package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.canonical.PolynomialNormalizer;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactFactorizationTransformationPipeline;
import de.regelsuche.polynomial.ExactFactorizationExpressionRenderer;
import de.regelsuche.polynomial.ExactParsedFactorizationPipeline;
import de.regelsuche.polynomial.ExactParsedUnivariatePolynomialView;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.retention.RetainedOperation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongConsumer;

/** General native algebra proposals; candidate selection belongs to the existing frontier. */
final class PolynomialSimplificationPrograms {
    private PolynomialSimplificationPrograms() { }

    static List<String> generate(StructuralConstructionPrograms.Operation operation, Expr source, long maximumWork,
            LongConsumer work) {
        var accounting = new ProcedureWork(maximumWork, work);
        if (operation == StructuralConstructionPrograms.Operation.POLYNOMIAL_UNIVARIATE_FACTOR) {
            var results = new LinkedHashSet<Expr>();
            subtrees(source, subtree -> factor(subtree, accounting), results, accounting);
            return render(results, accounting);
        }
        var measured = StructuralExpansionRules.observe(() -> {
            var results = new LinkedHashSet<Expr>();
            var canonicalizer = new ExpressionCanonicalizer();
            Expr canonical = canonicalizer.canonicalize(source);
            switch (operation) {
                case POLYNOMIAL_CANONICAL -> results.add(compact(canonical));
                case POLYNOMIAL_EXPANDED -> {
                    // Canonicalization first resolves exact constant denominators into rational leaves.
                    var normal = new PolynomialNormalizer().normalize(canonical);
                    normal.ifPresent(value -> results.add(compact(canonicalizer.canonicalize(value))));
                }
                case POLYNOMIAL_COMMON_FACTOR -> subtrees(canonical, PolynomialFactorGrouping::candidates, results, RetainedOperation::work);
                default -> throw new IllegalArgumentException("not a polynomial simplification operation");
            }
            return results;
        }, accounting.remaining());
        accounting.accept(measured.work());
        if (measured.exhausted()) return List.of();
        return render(measured.value(), accounting);
    }

    private static List<String> render(Iterable<Expr> candidates, ProcedureWork work) {
        var observed = StructuralExpansionRules.observe(() -> {
            var output = new ArrayList<String>();
            for (Expr candidate : candidates) output.add(ExpressionFormatter.format(compact(candidate)));
            return List.copyOf(output);
        }, work.remaining());
        work.accept(observed.work());
        if (observed.exhausted()) return List.of();
        for (String output : observed.value()) work.accept(output.length());
        return observed.value();
    }

    private static void subtrees(Expr source, Function<Expr, List<Expr>> procedure, LinkedHashSet<Expr> output, LongConsumer work) {
        work.accept(1);
        for (var candidate : procedure.apply(source)) {
            work.accept(1);
            if (!candidate.equals(source)) output.add(candidate);
            if (output.size() >= 16) return;
        }
        if (!(source instanceof BinaryExpr binary)) return;
        var left = new LinkedHashSet<Expr>();
        subtrees(binary.left(), procedure, left, work);
        for (Expr candidate : left) {
            work.accept(1);
            output.add(new BinaryExpr(candidate, binary.operator(), binary.right()));
            if (output.size() >= 16) return;
        }
        var right = new LinkedHashSet<Expr>();
        subtrees(binary.right(), procedure, right, work);
        for (Expr candidate : right) {
            work.accept(1);
            output.add(new BinaryExpr(binary.left(), binary.operator(), candidate));
            if (output.size() >= 16) return;
        }
    }

    private static List<Expr> factor(Expr source, ProcedureWork work) {
        work.accept(1);
        if (!(source instanceof BinaryExpr binary)
                || binary.operator() != BinaryOperator.ADD && binary.operator() != BinaryOperator.SUB) return List.of();
        var formatted = StructuralExpansionRules.observe(() -> ExpressionFormatter.format(source), work.remaining());
        work.accept(formatted.work());
        if (formatted.exhausted()) return List.of();
        String text = formatted.value();
        work.accept(text.length());
        var parsed = new ExpressionParser().parseExactTerm(text);
        var view = new ExactParsedUnivariatePolynomialView(new ExactParsedUnivariatePolynomialView.Budget(16, 256, 512, 10_000));
        var policy = new ExactParsedFactorizationPipeline.Policy(new FactorizationRequest.StructuralLimits(1, 16, 64, 256),
            64, 75_000, FactorizationRequest.EvidenceRequirement.VERIFIED_DECOMPOSITION);
        var factored = new ExactParsedFactorizationPipeline(view, policy)
            .factor(parsed, NativeUnivariateFactorizationEngine.boundedRationals());
        if (!factored.executed() || !factored.report().orElseThrow().successful()) {
            work.accept(factored.totalWork().totalWorkUnits());
            return List.of();
        }
        var renderer = new ExactFactorizationExpressionRenderer(new ExactFactorizationExpressionRenderer.Policy(
            16, 64, 16, 256, 4096, 20_000));
        var transformed = new ExactFactorizationTransformationPipeline(renderer, new ExpressionParser(), view)
            .transformRoot(parsed, factored);
        work.accept(transformed.totalWork().totalWorkUnits());
        if (!transformed.transformed()) return List.of();
        String result = transformed.transformedExpression().orElseThrow();
        work.accept(result.length());
        return List.of(new ExpressionParser().parseExactTerm(result).expression());
    }

    /** Shorter exact scalar placement, without creating any variable denominator. */
    private static Expr compact(Expr expression) {
        RetainedOperation.work(1);
        if (!(expression instanceof BinaryExpr binary)) return expression;
        Expr left = compact(binary.left()), right = compact(binary.right());
        if (binary.operator() == BinaryOperator.MUL) {
            if (left instanceof NumberExpr number && !number.value().isInteger()) return rationalProduct(number, right);
            if (right instanceof NumberExpr number && !number.value().isInteger()) return rationalProduct(number, left);
        }
        if (binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.SUB) {
            var positive = new ArrayList<Expr>();
            var negative = new ArrayList<Expr>();
            signedTerms(new BinaryExpr(left, binary.operator(), right), false, positive, negative);
            Expr result = null;
            for (Expr term : positive) {
                RetainedOperation.work(1);
                result = result == null ? term : new BinaryExpr(result, BinaryOperator.ADD, term);
            }
            if (result == null) result = new NumberExpr(0);
            for (Expr term : negative) {
                RetainedOperation.work(1);
                result = new BinaryExpr(result, BinaryOperator.SUB, term);
            }
            return result;
        }
        return left == binary.left() && right == binary.right() ? expression : new BinaryExpr(left, binary.operator(), right);
    }

    private static void signedTerms(Expr expression, boolean negate, List<Expr> positive, List<Expr> negative) {
        RetainedOperation.work(1);
        if (expression instanceof BinaryExpr binary && (binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.SUB)) {
            signedTerms(binary.left(), negate, positive, negative);
            signedTerms(binary.right(), negate ^ binary.operator() == BinaryOperator.SUB, positive, negative);
        } else if (expression instanceof NumberExpr number) {
            if (!number.value().isZero()) {
                (negate ^ number.value().signum() < 0 ? negative : positive).add(new NumberExpr(number.value().abs()));
            }
        } else (negate ? negative : positive).add(expression);
    }

    private static Expr rationalProduct(NumberExpr number, Expr term) {
        var value = number.value();
        Expr numerator = value.numerator().equals(java.math.BigInteger.ONE) ? term
            : new BinaryExpr(new NumberExpr(de.regelsuche.scalar.ExactRational.integer(value.numerator())), BinaryOperator.MUL, term);
        return new BinaryExpr(numerator, BinaryOperator.DIV,
            new NumberExpr(de.regelsuche.scalar.ExactRational.integer(value.denominator())));
    }

    private static final class ProcedureWork implements LongConsumer {
        private final long maximumWork;
        private final LongConsumer delegate;
        private long consumed;
        private ProcedureWork(long maximumWork, LongConsumer delegate) {
            if (maximumWork < 0) throw new IllegalArgumentException("negative procedure work limit");
            this.maximumWork = maximumWork;
            this.delegate = delegate;
        }
        @Override public void accept(long units) {
            delegate.accept(units);
            consumed = Math.addExact(consumed, units);
        }
        private long remaining() { return maximumWork - consumed; }
    }
}
