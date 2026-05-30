package de.regelsuche.mining;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.Transformation;
import java.util.List;
import java.util.Locale;

/** Metadata-driven guard for conservative macro applicability. */
@FunctionalInterface
public interface MacroApplicabilityGuard {
    boolean allows(String sourceExpression, ReusableRule rule, Transformation transformation);

    static MacroApplicabilityGuard metadataRelations() {
        return new RelationGuard();
    }

    final class RelationGuard implements MacroApplicabilityGuard {
        private final ExpressionParser parser = new ExpressionParser();
        private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

        @Override
        public boolean allows(String sourceExpression, ReusableRule rule, Transformation transformation) {
            boolean hasUnitStepRelation = rule.parameterRelations().stream()
                .map(ParameterRelation::parse)
                .flatMap(java.util.Optional::stream)
                .anyMatch(relation -> relation.relationType() == ParameterRelation.RelationType.UNIT_STEP);
            if (!hasUnitStepRelation
                && rule.parameterRelations().stream().map(RelationGuard::compact).noneMatch("B=A+1"::equals)
                && !compact(rule.leftPattern()).contains("(A*(A+1))")
                && !compact(rule.leftPattern()).contains("A*(A+1)")) {
                return true;
            }
            return isUnitStepFraction(sourceExpression, transformation.transformedExpression());
        }

        private boolean isUnitStepFraction(String sourceExpression, String transformedExpression) {
            Expr source = parse(sourceExpression);
            Expr transformed = parse(transformedExpression);
            if (!(source instanceof BinaryExpr division)
                || division.operator() != BinaryOperator.DIV
                || !isOne(division.left())
                || !(transformed instanceof BinaryExpr difference)
                || difference.operator() != BinaryOperator.SUB) {
                return false;
            }
            List<Expr> factors = flattenMultiplication(division.right());
            if (factors.size() != 2) {
                return false;
            }
            Expr leftDenominator = denominatorOfUnitFraction(difference.left());
            Expr rightDenominator = denominatorOfUnitFraction(difference.right());
            if (leftDenominator == null || rightDenominator == null) {
                return false;
            }
            return factorsContain(factors, leftDenominator)
                && factorsContain(factors, rightDenominator)
                && isUnitStepPair(leftDenominator, rightDenominator);
        }

        private Expr parse(String expression) {
            try {
                return parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        private List<Expr> flattenMultiplication(Expr expression) {
            if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL) {
                return java.util.stream.Stream.concat(
                    flattenMultiplication(binary.left()).stream(),
                    flattenMultiplication(binary.right()).stream()
                ).toList();
            }
            return List.of(expression);
        }

        private Expr denominatorOfUnitFraction(Expr expression) {
            if (expression instanceof BinaryExpr division
                && division.operator() == BinaryOperator.DIV
                && isOne(division.left())) {
                return division.right();
            }
            return null;
        }

        private boolean factorsContain(List<Expr> factors, Expr expression) {
            return factors.stream().anyMatch(factor -> same(factor, expression));
        }

        private boolean isUnitStepPair(Expr lower, Expr upper) {
            AdditiveOffset lowerOffset = additiveOffset(lower);
            AdditiveOffset upperOffset = additiveOffset(upper);
            return lowerOffset != null
                && upperOffset != null
                && same(lowerOffset.symbolicPart(), upperOffset.symbolicPart())
                && Double.compare(upperOffset.offset() - lowerOffset.offset(), 1.0) == 0;
        }

        private AdditiveOffset additiveOffset(Expr expression) {
            if (expression instanceof NumberExpr number) {
                return new AdditiveOffset(new NumberExpr(0), number.value());
            }
            if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.ADD) {
                if (binary.right() instanceof NumberExpr right) {
                    return new AdditiveOffset(binary.left(), right.value());
                }
                if (binary.left() instanceof NumberExpr left) {
                    return new AdditiveOffset(binary.right(), left.value());
                }
            }
            return new AdditiveOffset(expression, 0.0);
        }

        private boolean isOne(Expr expression) {
            return expression instanceof NumberExpr number && Double.compare(number.value(), 1.0) == 0;
        }

        private boolean same(Expr left, Expr right) {
            return canonicalizer.stableHash(ExpressionFormatter.format(left))
                .equals(canonicalizer.stableHash(ExpressionFormatter.format(right)));
        }

        private static String compact(String relation) {
            return relation == null ? "" : relation.replace(" ", "").toUpperCase(Locale.ROOT);
        }

        private record AdditiveOffset(Expr symbolicPart, double offset) {
        }
    }
}
