package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.math.algorithms.equivalence.AlgebraicConstruction;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialResidualComposer;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.ExactMonomialSquareExposureOperator;
import de.regelsuche.transform.HypothesisOperator;
import de.regelsuche.transform.SquareBaseSignSymmetryOperator;
import de.regelsuche.transform.SumOfSquaresCompletionOperator;
import de.regelsuche.transform.Transformation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Declared finite procedures, not a learned strategy or a second search algorithm. */
final class StructuralConstructionPrograms {
    enum Operation { EXPOSE_SQUARE, COMPLETE_SQUARE, FACTOR_DIFFERENCE, FACTOR_PARTITION, RESIDUAL_BALANCE }
    record Report(List<String> candidates, long work) { Report { candidates = List.copyOf(candidates); } }
    private static final long LIMIT = 100_000;
    private StructuralConstructionPrograms() { }

    static Report generate(Operation operation, Expr source) {
        var work = new Work();
        try {
            var domain = new CheckedSchemaSupport.Work();
            try {
                CheckedSchemaSupport.domain(source, CheckedLearnedSchemaModel.Bounds.defaults(), domain);
            } finally {
                work.add(domain.units);
            }
            String text = ExpressionFormatter.format(source); work.add(text.length());
            var candidates = switch (operation) {
                case EXPOSE_SQUARE -> apply(new ExactMonomialSquareExposureOperator(8), text, work);
                case COMPLETE_SQUARE -> apply(new SumOfSquaresCompletionOperator(), text, work);
                case FACTOR_DIFFERENCE -> factorDifference(text, work);
                case FACTOR_PARTITION -> partitionFactors(source, text, work);
                case RESIDUAL_BALANCE -> residualBalance(source, text, work);
            };
            return new Report(candidates, work.units);
        } catch (Limit | IllegalArgumentException unsupported) {
            return new Report(List.of(), work.units);
        }
    }

    private static List<String> apply(HypothesisOperator operator, String source, Work work) {
        work.add(source.length() + 1L);
        var candidates = operator.generateCandidates(source);
        var result = new ArrayList<String>();
        for (var candidate : candidates) {
            work.add(candidate.transformedExpression().length() + 1L);
            if (candidate.assumptions().isEmpty()) result.add(candidate.transformedExpression());
        }
        return List.copyOf(result);
    }
    private static List<String> factorDifference(String source, Work work) {
        var rules = AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(rule -> rule.id().equals("ast_square_difference_factor")).toList();
        return apply(new AstRewriteTransformationEngine(rules)::transform, source, work);
    }

    /** Construct exact monomial factors instead of introducing a potentially zero denominator. */
    private static List<String> partitionFactors(Expr expression, String source, Work work) {
        if (!(expression instanceof BinaryExpr power) || power.operator() != BinaryOperator.POW
                || !(power.right() instanceof NumberExpr exponent)) return List.of();
        var value = exponent.value();
        if (!value.denominator().equals(java.math.BigInteger.ONE) || value.numerator().signum() < 1
                || value.numerator().compareTo(java.math.BigInteger.valueOf(12)) > 0) return List.of();
        int n = value.numerator().intValueExact();
        if (n < 2) return List.of();
        String auxiliary = "(" + ExpressionFormatter.format(power.left()) + ")^" + (n / 2);
        var result = new AlgebraicConstruction().construct(new AlgebraicConstruction.Request(
            AlgebraicConstruction.Operation.EXACT_FACTOR_SPLIT, source, auxiliary, List.of(), LIMIT - work.units));
        work.add(result.work());
        return result.expression().stream().toList();
    }

    private static List<String> residualBalance(Expr expression, String source, Work work) {
        if (!(expression instanceof BinaryExpr root) || root.operator() != BinaryOperator.MUL) return List.of();
        var exact = new ExactPolynomialAnalysis(work::add);
        var polynomial = exact.normalForm(source);
        int count = polynomial.termCount();
        if (count < 4 || count > 6 || count % 2 != 0) return List.of();
        String prepared = exposeAll(polynomial.toCanonicalString(), work);
        var composer = new ExactPolynomialResidualComposer(work::add);
        var parts = composer.additiveComponents(prepared);
        if (parts.size() != count || !allSquares(parts, work)) return List.of();
        var effects = pairedEffects(parts, composer, work);
        var results = composer.compose(prepared, parts, effects, count / 2, 8);
        work.add(results.size());
        return results.stream().map(ExactPolynomialResidualComposer.Composition::candidateExpression).toList();
    }
    private static String exposeAll(String source, Work work) {
        String current = source;
        var exposure = new ExactMonomialSquareExposureOperator(1);
        var exact = new ExactPolynomialAnalysis(work::add);
        for (int step = 0; step < 12; step++) {
            var candidates = apply(exposure, current, work);
            if (candidates.isEmpty()) return current;
            String next = candidates.getFirst(); exact.requireEquivalent(current, next); current = next;
        }
        throw new IllegalArgumentException("square preparation did not converge within its bound");
    }
    private static boolean allSquares(List<ExactPolynomialResidualComposer.SourceComponent> parts, Work work) {
        var parser = new ExpressionParser();
        for (var part : parts) {
            work.add(part.expression().length());
            if (!StructuralTaskObjective.isSquare(parser.parseExactTerm(part.expression()).expression())) return false;
        }
        return true;
    }
    private static List<ExactPolynomialResidualComposer.Effect> pairedEffects(
            List<ExactPolynomialResidualComposer.SourceComponent> parts, ExactPolynomialResidualComposer composer, Work work) {
        var effects = new ArrayList<ExactPolynomialResidualComposer.Effect>();
        for (int left = 0; left < parts.size(); left++) {
            for (int right = left + 1; right < parts.size(); right++) {
                var pair = List.of(parts.get(left), parts.get(right));
                effects.add(effect(pair, false, left + "-" + right, composer, work));
                effects.add(effect(pair, true, left + "-" + right, composer, work));
            }
        }
        return List.copyOf(effects);
    }
    private static ExactPolynomialResidualComposer.Effect effect(
            List<ExactPolynomialResidualComposer.SourceComponent> pair, boolean reflect, String id,
            ExactPolynomialResidualComposer composer, Work work) {
        String right = pair.get(1).expression();
        var rules = new ArrayList<String>(); var applications = new ArrayList<String>();
        if (reflect) {
            var symmetry = only(new SquareBaseSignSymmetryOperator().generateCandidates(right));
            work.add(right.length() + symmetry.transformedExpression().length());
            right = symmetry.transformedExpression(); rules.addAll(symmetry.primitiveRuleIds()); applications.add(symmetry.applicationKey());
        }
        String input = "(" + pair.get(0).expression() + ")+(" + right + ")";
        var completion = only(new SumOfSquaresCompletionOperator().generateCandidates(input));
        work.add(input.length() + completion.transformedExpression().length());
        rules.addAll(completion.primitiveRuleIds()); applications.add(completion.applicationKey());
        return composer.effect((reflect ? "reflected-" : "plus-") + id, pair, completion.transformedExpression(),
            completedSquare(completion.transformedExpression(), work), List.of(), rules, applications);
    }
    private static String completedSquare(String source, Work work) {
        var pending = new ArrayDeque<Expr>(); pending.push(new ExpressionParser().parseExactTerm(source).expression());
        while (!pending.isEmpty()) {
            Expr node = pending.pop(); work.add(1);
            if (!(node instanceof BinaryExpr binary)) continue;
            if (StructuralTaskObjective.isSquare(binary) && binary.left() instanceof BinaryExpr base
                    && (base.operator() == BinaryOperator.ADD || base.operator() == BinaryOperator.SUB))
                return ExpressionFormatter.format(binary);
            pending.push(binary.right()); pending.push(binary.left());
        }
        throw new IllegalArgumentException("completion did not retain a structured square");
    }
    private static Transformation only(List<Transformation> results) {
        if (results.size() != 1) throw new IllegalArgumentException("one bound completion required");
        return results.getFirst();
    }
    private static final class Work {
        private long units;
        private void add(long count) { units = Math.addExact(units, count); if (units > LIMIT) throw new Limit(); }
    }
    private static final class Limit extends RuntimeException { }
}
