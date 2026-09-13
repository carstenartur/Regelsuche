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
    private final java.util.function.LongConsumer workObserver;
    private final java.util.function.LongConsumer operationAllowance;
    ExactResidualPolynomialArithmetic() { this(null); }
    ExactResidualPolynomialArithmetic(java.util.function.LongConsumer workObserver) { this(workObserver, null); }
    ExactResidualPolynomialArithmetic(java.util.function.LongConsumer workObserver,
        java.util.function.LongConsumer operationAllowance) {
        this.workObserver = workObserver;
        this.operationAllowance = operationAllowance;
    }

    ExactParsedTerm exactTerm(String expression) {
        Objects.requireNonNull(expression, "expression");
        if (expression.isBlank() || expression.length() > MAX_SOURCE_CHARS) {
            throw limit("residual source length limit", operationAllowance);
        }
        int structuralTokens = 0;
        for (int index = 0; index < expression.length(); index++) {
            if ("+-*/^(),".indexOf(expression.charAt(index)) >= 0
                    && ++structuralTokens > MAX_STRUCTURAL_TOKENS) {
                throw limit("residual syntax work limit", operationAllowance);
            }
        }
        return parser.parseExactTerm(expression);
    }

    String syntax(String expression) {
        ExactParsedTerm parsed = exactTerm(expression);
        return ExactExpressionFormatter.format(parsed.expression(), parsed);
    }

    Polynomial parse(String expression) {
        if (operationAllowance != null) { operationAllowance.accept(expression.length()); }
        ExactParsedTerm parsed = exactTerm(expression);
        var work = new Work(operationAllowance);
        try { return convert(parsed.expression(), parsed, work); }
        finally { if (workObserver != null) workObserver.accept(Math.addExact(work.nodes, work.terms)); }
    }

    private Polynomial convert(Expr node, ExactParsedTerm parsed, Work work) {
        work.node();
        if (node instanceof NumberExpr number) {
            // ExactParsedTerm validates that the only unbacked numeric node is
            // the zero synthesized by its own unary-minus parser production.
            ExactRational value = parsed.literalFor(number)
                .map(ExactParsedTerm.LiteralOccurrence::exactValue)
                .orElse(ExactRational.ZERO);
            return checked(Polynomial.constant(Rational.fromExact(value)), work);
        }
        if (node instanceof VariableExpr variable) {
            work.spend(1);
            work.terms++;
            return Polynomial.variable(variable.name());
        }
        if (!(node instanceof BinaryExpr binary)) {
            throw new IllegalArgumentException("non-polynomial residual term");
        }
        Polynomial left = convert(binary.left(), parsed, work);
        if (binary.operator() == BinaryOperator.POW) {
            work.node();
            return power(left, exponent(binary.right(), parsed), work);
        }
        Polynomial right = convert(binary.right(), parsed, work);
        return switch (binary.operator()) {
            case ADD -> add(left, right, false, work);
            case SUB -> add(left, right, true, work);
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
        work.spend(1);
        work.terms = Math.addExact(work.terms, result.termCount());
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
        Polynomial left, Polynomial right, boolean subtract, Work work
    ) {
        work.spend((long) left.termCount() + right.termCount());
        requireCoefficientRoom(coefficientBits(left) + coefficientBits(right) + 1L, work);
        return checked(subtract ? left.subtract(right) : left.add(right), work);
    }

    private static Polynomial divide(
        Polynomial numerator, Polynomial denominator, Work work
    ) {
        work.spend(denominator.termCount() + 1L);
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
        work.spend((long) left.termCount() + right.termCount());
        if (products > 4_096L
                || left.totalDegree() + right.totalDegree() > MAX_DEGREE) {
            throw limit("residual expansion size limit", work.operationAllowance);
        }
        // A resulting monomial can receive at most min(m,n) products.
        // Bound rational denominator growth BEFORE convolution allocates it.
        long collisions = Math.min(left.termCount(), right.termCount());
        long bits = (long) coefficientBits(left) + coefficientBits(right);
        requireCoefficientRoom(collisions * (bits + 1L), work);
        return checked(left.multiply(right), work);
    }

    private static Polynomial checked(Polynomial polynomial, Work work) {
        work.spend(polynomial.termCount());
        work.terms = Math.addExact(work.terms, polynomial.termCount());
        if (polynomial.termCount() > MAX_TERMS
                || polynomial.totalDegree() > MAX_DEGREE) {
            throw limit("residual polynomial size limit", work.operationAllowance);
        }
        requireCoefficientRoom(coefficientBits(polynomial), work);
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

    private static void requireCoefficientRoom(long bits, Work work) {
        if (bits > MAX_COEFFICIENT_BITS) {
            throw limit("residual coefficient size limit", work.operationAllowance);
        }
    }

    // Existing consumers retain exactly their old exception class/message. The opt-in bounded caller
    // also needs a typed distinction between resource exhaustion and unsupported mathematics.
    private static IllegalArgumentException limit(String detail, java.util.function.LongConsumer allowance) {
        return allowance == null ? new IllegalArgumentException(detail) : new ProjectionLimitExceeded(detail);
    }

    static final class ProjectionLimitExceeded extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private ProjectionLimitExceeded(String detail) { super(detail); }
    }

    private static final class Work {
        private final java.util.function.LongConsumer operationAllowance;
        private long products;
        private long nodes;
        private long terms;

        private Work(java.util.function.LongConsumer operationAllowance) { this.operationAllowance = operationAllowance; }
        private void spend(long count) { if (operationAllowance != null) { operationAllowance.accept(count); } }
        private void node() { spend(1); nodes++; }

        private void consume(long count) {
            spend(count);
            if (count > MAX_TERM_PRODUCTS - products) {
                throw limit("residual arithmetic work limit", operationAllowance);
            }
            products += count;
        }
    }
}
