package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExactExpressionFormatter;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import java.util.Objects;

/**
 * Bounded source-exact projection into the existing Polynomial arithmetic.
 *
 * <p>This is not a second parser or polynomial representation. Numeric values
 * come only from ExactParsedTerm; neither NumberExpr.value() nor fromDouble
 * may authorize a residual certificate. The legacy PolynomialArithmetic path
 * remains unchanged for its existing consumers.</p>
 */
final class ExactResidualPolynomialArithmetic {
    private static final int MAX_SOURCE_CHARS = 16_384;
    private static final int MAX_STRUCTURAL_TOKENS = 256;
    private static final int MAX_EXPONENT = 32;
    private static final int MAX_DEGREE = 128;
    private static final int MAX_TERMS = 512;
    private static final int MAX_COEFFICIENT_BITS = 4_096;
    private static final long MAX_TERM_PRODUCTS = 65_536L;

    private final ExpressionParser parser = new ExpressionParser();

    ExactParsedTerm exactTerm(String expression) {
        Objects.requireNonNull(expression, "expression");
        if (expression.isBlank() || expression.length() > MAX_SOURCE_CHARS) {
            throw new IllegalArgumentException("residual source length limit");
        }
        int structuralTokens = 0;
        for (int index = 0; index < expression.length(); index++) {
            if ("+-*/^(),".indexOf(expression.charAt(index)) >= 0
                    && ++structuralTokens > MAX_STRUCTURAL_TOKENS) {
                throw new IllegalArgumentException("residual syntax work limit");
            }
        }
        return parser.parseExactTerm(expression);
    }

    String syntax(String expression) {
        ExactParsedTerm parsed = exactTerm(expression);
        return ExactExpressionFormatter.format(parsed.expression(), parsed);
    }

    Polynomial parse(String expression) {
        ExactParsedTerm parsed = exactTerm(expression);
        return convert(parsed.expression(), parsed, new Work());
    }

    private Polynomial convert(Expr node, ExactParsedTerm parsed, Work work) {
        if (node instanceof NumberExpr number) {
            // ExactParsedTerm validates that the only unbacked numeric node is
            // the zero synthesized by its own unary-minus parser production.
            ExactRational value = parsed.literalFor(number)
                .map(ExactParsedTerm.LiteralOccurrence::exactValue)
                .orElse(ExactRational.ZERO);
            return checked(Polynomial.constant(Rational.fromExact(value)));
        }
        if (node instanceof VariableExpr variable) {
            return Polynomial.variable(variable.name());
        }
        if (!(node instanceof BinaryExpr binary)) {
            throw new IllegalArgumentException("non-polynomial residual term");
        }
        Polynomial left = convert(binary.left(), parsed, work);
        if (binary.operator() == BinaryOperator.POW) {
            return power(left, exponent(binary.right(), parsed), work);
        }
        Polynomial right = convert(binary.right(), parsed, work);
        return switch (binary.operator()) {
            case ADD -> add(left, right, false);
            case SUB -> add(left, right, true);
            case MUL -> multiply(left, right, work);
            case DIV -> divide(left, right, work);
            case POW -> throw new IllegalStateException("power handled above");
        };
    }

    private static int exponent(Expr node, ExactParsedTerm parsed) {
        if (!(node instanceof NumberExpr number)) {
            throw new IllegalArgumentException("exponent must be a literal");
        }
        ExactRational value = parsed.literalFor(number)
            .orElseThrow(() -> new IllegalArgumentException(
                "exponent lacks exact provenance"))
            .exactValue();
        if (!value.isInteger() || value.numerator().signum() < 0
                || value.numerator().bitLength() > 6
                || value.numerator().intValue() > MAX_EXPONENT) {
            throw new IllegalArgumentException("unsupported exact exponent");
        }
        return value.numerator().intValueExact();
    }

    private static Polynomial power(Polynomial base, int exponent, Work work) {
        Polynomial result = Polynomial.constant(Rational.ONE);
        Polynomial factor = base;
        int remaining = exponent;
        while (remaining != 0) {
            if ((remaining & 1) != 0) {
                result = multiply(result, factor, work);
            }
            remaining >>>= 1;
            if (remaining != 0) {
                factor = multiply(factor, factor, work);
            }
        }
        return result;
    }

    private static Polynomial add(
        Polynomial left, Polynomial right, boolean subtract
    ) {
        requireCoefficientRoom(coefficientBits(left) + coefficientBits(right) + 1L);
        return checked(subtract ? left.subtract(right) : left.add(right));
    }

    private static Polynomial divide(
        Polynomial numerator, Polynomial denominator, Work work
    ) {
        if (denominator.isZero() || denominator.totalDegree() != 0) {
            throw new IllegalArgumentException(
                "residual division requires a nonzero constant denominator");
        }
        Rational constant = denominator.terms().values().iterator().next();
        return multiply(numerator,
            Polynomial.constant(Rational.ONE.divide(constant)), work);
    }

    private static Polynomial multiply(
        Polynomial left, Polynomial right, Work work
    ) {
        long products = (long) left.termCount() * right.termCount();
        work.consume(products);
        if (products > 4_096L
                || left.totalDegree() + right.totalDegree() > MAX_DEGREE) {
            throw new IllegalArgumentException("residual expansion size limit");
        }
        // A resulting monomial can receive at most min(m,n) products.
        // Bound rational denominator growth BEFORE convolution allocates it.
        long collisions = Math.min(left.termCount(), right.termCount());
        long bits = (long) coefficientBits(left) + coefficientBits(right);
        requireCoefficientRoom(collisions * (bits + 1L));
        return checked(left.multiply(right));
    }

    private static Polynomial checked(Polynomial polynomial) {
        if (polynomial.termCount() > MAX_TERMS
                || polynomial.totalDegree() > MAX_DEGREE) {
            throw new IllegalArgumentException("residual polynomial size limit");
        }
        requireCoefficientRoom(coefficientBits(polynomial));
        return polynomial;
    }

    private static int coefficientBits(Polynomial polynomial) {
        int result = 0;
        for (Rational coefficient : polynomial.terms().values()) {
            result = Math.max(result, Math.max(
                coefficient.numerator().abs().bitLength(),
                coefficient.denominator().bitLength()));
        }
        return result;
    }

    private static void requireCoefficientRoom(long bits) {
        if (bits > MAX_COEFFICIENT_BITS) {
            throw new IllegalArgumentException("residual coefficient size limit");
        }
    }

    private static final class Work {
        private long products;

        private void consume(long count) {
            if (count > MAX_TERM_PRODUCTS - products) {
                throw new IllegalArgumentException("residual arithmetic work limit");
            }
            products += count;
        }
    }
}
