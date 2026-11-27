package de.regelsuche.solver.portfolio;

import static de.regelsuche.solver.portfolio.Z3ProofSyntax.*;

import de.regelsuche.solver.ir.SolverIr.Binary;
import de.regelsuche.solver.ir.SolverIr.BinaryOperator;
import de.regelsuche.solver.ir.SolverIr.Expression;
import de.regelsuche.solver.ir.SolverIr.Literal;
import de.regelsuche.solver.ir.SolverIr.Symbol;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Typed real IR plus private Boolean proof syntax, with admission before expansion. */
final class Z3ProofValues {
    static final int MAX_EXPANDED_SIZE = 4096;
    private final Set<String> symbols;
    private final Map<String, Value> bindings = new HashMap<>();
    private int admittedSize;

    Z3ProofValues(Set<String> symbols) { this.symbols = Set.copyOf(symbols); }

    sealed interface Value permits Real, Formula { int size(); }
    sealed interface Formula extends Value permits Equality, Negation, Truth { }

    /** Arguments retain native operator positions; mathematical identity is the existing IR. */
    record Real(Expression expression, BinaryOperator operator, List<Real> arguments,
                int size, int monomials, int degree, CoefficientBits coefficients) implements Value {
        @Override public boolean equals(Object other) {
            return other instanceof Real real && expression.equals(real.expression);
        }
        @Override public int hashCode() { return expression.hashCode(); }
    }
    record Equality(Value left, Value right, int size) implements Formula {
        Equality reverse() { return new Equality(right, left, size); }
    }
    record Negation(Formula operand, int size) implements Formula { }
    enum Truth implements Formula {
        TRUE, FALSE;
        @Override public int size() { return 1; }
    }

    boolean bound(String name) { return bindings.containsKey(name); }
    void bindAll(Map<String, Value> values) { bindings.putAll(values); }

    Formula formula(Node node) { return asFormula(value(node)); }
    static Formula asFormula(Value value) {
        if (!(value instanceof Formula formula)) throw new Invalid();
        return formula;
    }
    static Real asReal(Value value) {
        if (!(value instanceof Real real)) throw new Invalid();
        return real;
    }

    Value value(Node node) {
        if (node.token() != null) {
            String token = node.atom();
            if (token.equals("true")) return Truth.TRUE;
            if (token.equals("false")) return Truth.FALSE;
            Value bound = bindings.get(token);
            if (bound != null) return bound;
            if (symbols.contains(token)) return scalar(new Symbol(token));
            if (token.matches("-?[0-9]+(?:\\.[0-9]+)?")) return literal(token);
            throw new Invalid();
        }
        return switch (node.first()) {
            case "=" -> {
                var args = node.items(3);
                yield equality(value(args.get(1)), value(args.get(2)));
            }
            case "not" -> negate(formula(node.items(2).get(1)));
            case "to_real" -> integerCoercion(node);
            case "/" -> {
                var args = node.items(3);
                yield quotient(asReal(value(args.get(1))), asReal(value(args.get(2))));
            }
            case "+", "-", "*", "^" -> operation(node);
            default -> throw new Invalid();
        };
    }

    Real expression(Expression expression) { return expression(expression, 0); }
    private Real expression(Expression expression, int depth) {
        if (depth > 128) throw new Invalid();
        if (expression instanceof Symbol symbol) {
            if (!symbols.contains(symbol.name())) throw new Invalid();
            return scalar(symbol);
        }
        if (expression instanceof Literal literal) return literal(literal.value());
        if (!(expression instanceof Binary binary)) throw new Invalid();
        Real left = expression(binary.left(), depth + 1), right = expression(binary.right(), depth + 1);
        if (binary.operator() == BinaryOperator.DIVIDE) return quotient(left, right);
        return binary(binary.operator(), left, right);
    }

    Equality equality(Value left, Value right) {
        if ((left instanceof Real) != (right instanceof Real)) throw new Invalid();
        int size = admit(1L + left.size() + right.size());
        return new Equality(left, right, size);
    }
    Negation negate(Formula value) { return new Negation(value, admit(1L + value.size())); }

    private Real integerCoercion(Node node) {
        Real value = asReal(value(node.items(2).get(1)));
        if (!(value.expression() instanceof Literal literal)
                || new BigDecimal(literal.value()).scale() > 0) throw new Invalid();
        return value;
    }

    private Real operation(Node node) {
        var nodes = node.children();
        if (nodes.size() < 2 || nodes.size() > 129) throw new Invalid();
        var arguments = new ArrayList<Real>();
        for (int i = 1; i < nodes.size(); i++) arguments.add(asReal(value(nodes.get(i))));
        BinaryOperator operator = switch (node.first()) {
            case "+" -> BinaryOperator.ADD;
            case "-" -> BinaryOperator.SUBTRACT;
            case "*" -> BinaryOperator.MULTIPLY;
            case "^" -> BinaryOperator.POWER;
            default -> throw new Invalid();
        };
        if (arguments.size() == 1 && operator == BinaryOperator.SUBTRACT) {
            Real argument = arguments.getFirst();
            if (argument.expression() instanceof Literal literal) {
                return literal(new BigDecimal(literal.value()).negate().toPlainString());
            }
            Real result = binary(operator, literal("0"), argument);
            return new Real(result.expression(), operator, List.copyOf(arguments),
                result.size(), result.monomials(), result.degree(), result.coefficients());
        }
        if (arguments.size() < 2 || operator == BinaryOperator.POWER && arguments.size() != 2) throw new Invalid();
        Real result = arguments.getFirst();
        for (int i = 1; i < arguments.size(); i++) result = binary(operator, result, arguments.get(i));
        return new Real(result.expression(), operator, List.copyOf(arguments),
            result.size(), result.monomials(), result.degree(), result.coefficients());
    }

    Real difference(Real left, Real right) {
        return binary(BinaryOperator.SUBTRACT, left, right);
    }

    private Real binary(BinaryOperator operator, Real left, Real right) {
        int size = admit(1L + left.size() + right.size());
        int degree = Math.max(left.degree(), right.degree());
        long monomials;
        CoefficientBits coefficients;
        switch (operator) {
            case ADD, SUBTRACT -> {
                monomials = (long) left.monomials() + right.monomials();
                coefficients = left.coefficients().add(right.coefficients());
            }
            case MULTIPLY -> {
                monomials = (long) left.monomials() * right.monomials();
                degree = left.degree() + right.degree();
                coefficients = left.coefficients().multiply(right.coefficients(), (int) monomials);
            }
            case POWER -> {
                if (!(right.expression() instanceof Literal literal)) throw new Invalid();
                int exponent;
                try { exponent = new BigDecimal(literal.value()).intValueExact(); }
                catch (ArithmeticException invalid) { throw new Invalid(); }
                // Native Z3 power does not justify x^0 = 1 when x may be zero.
                if (exponent < 1 || exponent > 20) throw new Invalid();
                degree = left.degree() * exponent;
                monomials = 1;
                for (int i = 0; i < exponent; i++) {
                    monomials *= left.monomials();
                    if (monomials > 256) throw new Invalid();
                }
                coefficients = left.coefficients().power(exponent, (int) monomials);
            }
            default -> throw new Invalid();
        }
        if (monomials > 256 || degree > 32) throw new Invalid();
        return new Real(new Binary(operator, left.expression(), right.expression()), operator,
            List.of(left, right), size, (int) monomials, degree, coefficients);
    }

    private Real quotient(Real numerator, Real denominator) {
        if (!(numerator.expression() instanceof Literal a) || !(denominator.expression() instanceof Literal b)) throw new Invalid();
        try { return literal(new BigDecimal(a.value()).divide(new BigDecimal(b.value())).toPlainString()); }
        catch (ArithmeticException invalid) { throw new Invalid(); }
    }

    private Real literal(String value) {
        if (value.length() > 256 || !value.matches("-?[0-9]+(?:\\.[0-9]+)?")) throw new Invalid();
        return scalar(new Literal(value));
    }

    private Real scalar(Expression expression) {
        CoefficientBits coefficients = expression instanceof Literal literal
            ? CoefficientBits.literal(literal) : new CoefficientBits(1, 1);
        return new Real(expression, null, List.of(), admit(1), 1, expression instanceof Symbol ? 1 : 0, coefficients);
    }

    /** Conservative bounds for all coefficient numerators over one common denominator. */
    private record CoefficientBits(int numerator, int denominator) {
        CoefficientBits {
            if (numerator < 1 || denominator < 1 || numerator > 4096 || denominator > 4096) throw new Invalid();
        }

        static CoefficientBits literal(Literal literal) {
            // Literal admission already limits the decimal to 256 characters.
            var decimal = new BigDecimal(literal.value());
            return new CoefficientBits(Math.max(1, decimal.unscaledValue().abs().bitLength()),
                BigInteger.TEN.pow(decimal.scale()).bitLength());
        }

        CoefficientBits add(CoefficientBits other) {
            return new CoefficientBits(Math.max(numerator + other.denominator, other.numerator + denominator) + 1,
                denominator + other.denominator);
        }

        CoefficientBits multiply(CoefficientBits other, int expandedTerms) {
            return new CoefficientBits(numerator + other.numerator + ceilLog2(expandedTerms),
                denominator + other.denominator);
        }

        CoefficientBits power(int exponent, int expandedTerms) {
            return new CoefficientBits(exponent * numerator + ceilLog2(expandedTerms), exponent * denominator);
        }

        private static int ceilLog2(int count) { return Integer.SIZE - Integer.numberOfLeadingZeros(count - 1); }
    }

    private int admit(long size) {
        if (size > MAX_EXPANDED_SIZE || (admittedSize += (int) size) > 65_536) throw new Invalid();
        return (int) size;
    }

    static List<? extends Value> arguments(Value value) {
        return switch (value) {
            case Real real -> real.arguments();
            case Equality equality -> List.of(equality.left(), equality.right());
            case Negation negation -> List.of(negation.operand());
            case Truth ignored -> List.of();
        };
    }

    static boolean sameOperator(Value left, Value right) {
        if (left instanceof Real a && right instanceof Real b) {
            return a.operator() != null && a.operator() == b.operator();
        }
        return left instanceof Equality && right instanceof Equality
            || left instanceof Negation && right instanceof Negation;
    }
}
