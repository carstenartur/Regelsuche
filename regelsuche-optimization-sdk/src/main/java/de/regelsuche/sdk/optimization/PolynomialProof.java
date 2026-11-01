package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.*;
import java.math.BigInteger;
import java.util.*;

/** Independent bounded ring normal form. Java word arithmetic is reduced modulo 2^width.
 * Non-polynomial typed subexpressions remain opaque atoms; they are never treated as real numbers.
 */
final class PolynomialProof {
    static final int MAX_TERMS = 256, MAX_DEGREE = 64, MAX_COEFFICIENT_BITS = 4096;
    static final class OutsideFragment extends RuntimeException {}
    private record Monomial(Map<Expr, Integer> powers) {
        Monomial { powers = Map.copyOf(powers); }
        Monomial times(Monomial other) {
            var result = new HashMap<>(powers);
            other.powers.forEach((key, power) -> result.merge(key, power, Integer::sum));
            if (result.values().stream().mapToInt(Integer::intValue).sum() > MAX_DEGREE) throw new OutsideFragment();
            return new Monomial(result);
        }
    }
    private final NumericKind kind;
    private final BigInteger modulus;
    private final Expr residueModulus;
    private final boolean exactDivision;
    private final boolean floatingAlgebra;
    private final VerificationWork work;
    PolynomialProof(NumericKind kind, boolean exactDivision, boolean floatingAlgebra, VerificationWork work) {
        this.kind = kind; this.exactDivision = exactDivision; this.floatingAlgebra = floatingAlgebra; this.work = work;
        modulus = kind.integral() && !exactDivision ? BigInteger.ONE.shiftLeft(kind.bits()) : null;
        residueModulus = null;
    }
    PolynomialProof(NumericKind kind, int lowBits, VerificationWork work) {
        if ((kind != NumericKind.INT && kind != NumericKind.LONG) || lowBits < 1 || lowBits > kind.bits())
            throw new IllegalArgumentException("INVALID_LOW_WORD_WIDTH");
        this.kind = kind; this.exactDivision = false; this.floatingAlgebra = false; this.work = work;
        modulus = BigInteger.ONE.shiftLeft(lowBits);
        residueModulus = null;
    }
    /** Caller independently establishes positivity. Only this exact modulus is erased. */
    PolynomialProof(Expr positiveModulus, VerificationWork work) {
        kind = NumericKind.BIG_INTEGER; exactDivision = false; floatingAlgebra = false;
        this.work = work; residueModulus = Objects.requireNonNull(positiveModulus);
        modulus = JavaExpressions.isLiteral(positiveModulus)
            ? SemanticChecker.integer(JavaExpressions.literalValue(positiveModulus)) : null;
        if (modulus != null && modulus.signum() <= 0) throw new OutsideFragment();
    }
    boolean equivalent(Expr source, Expr target) { return normalize(source, 0).equals(normalize(target, 0)); }
    private Map<Monomial, BigInteger> normalize(Expr expression, int depth) {
        work.charge(1);
        if (depth > 128) throw new OutsideFragment();
        if (JavaExpressions.isLiteral(expression)) {
            Object value = JavaExpressions.literalValue(expression);
            BigInteger number;
            if (value instanceof BigInteger b) number = b;
            else if (value instanceof Character c) number = BigInteger.valueOf(c);
            else if (value instanceof Float || value instanceof Double) {
                double d = ((Number)value).doubleValue();
                if (!floatingAlgebra || !Double.isFinite(d) || d != Math.rint(d) || Math.abs(d) > 1e15) throw new OutsideFragment();
                number = BigInteger.valueOf((long)d);
            } else number = BigInteger.valueOf(((Number)value).longValue());
            return constant(number);
        }
        if (expression instanceof VariableExpr) return atom(expression);
        if (JavaExpressions.resultKind(expression) != kind) return atom(expression);
        var operation = JavaExpressions.operationOf(expression);
        if (operation.isEmpty()) return atom(expression);
        var args = JavaExpressions.operands(expression);
        return switch (operation.get()) {
            case MOD -> residueModulus != null && residueModulus.equals(args.get(1))
                ? normalize(args.getFirst(), depth+1) : atom(expression);
            case MOD_MULTIPLY -> residueModulus != null && residueModulus.equals(args.get(2))
                ? multiply(normalize(args.getFirst(), depth+1), normalize(args.get(1), depth+1)) : atom(expression);
            case ADD -> add(normalize(args.getFirst(), depth+1), normalize(args.get(1), depth+1), BigInteger.ONE);
            case SUBTRACT -> add(normalize(args.getFirst(), depth+1), normalize(args.get(1), depth+1), BigInteger.ONE.negate());
            case NEGATE -> multiply(constant(BigInteger.ONE.negate()), normalize(args.getFirst(), depth+1));
            case MULTIPLY -> multiply(normalize(args.getFirst(), depth+1), normalize(args.get(1), depth+1));
            case POW -> {
                if (!JavaExpressions.isLiteral(args.get(1))) yield atom(expression);
                BigInteger exponent = SemanticChecker.integer(JavaExpressions.literalValue(args.get(1)));
                if (exponent.signum() < 0 || exponent.compareTo(BigInteger.valueOf(MAX_DEGREE)) > 0) yield atom(expression);
                var base = normalize(args.getFirst(),depth+1); var result = constant(BigInteger.ONE);
                for(int i=0;i<exponent.intValue();i++) result = multiply(result,base);
                yield result;
            }
            case DIVIDE -> {
                if (!exactDivision || !JavaExpressions.isLiteral(args.get(1))) yield atom(expression);
                Object divisorValue = JavaExpressions.literalValue(args.get(1));
                BigInteger divisor = divisorValue instanceof BigInteger b ? b : BigInteger.valueOf(((Number)divisorValue).longValue());
                if (divisor.signum() == 0) throw new OutsideFragment();
                var numerator = normalize(args.getFirst(), depth+1);
                var result = new HashMap<Monomial, BigInteger>();
                for (var entry : numerator.entrySet()) {
                    var qr = entry.getValue().divideAndRemainder(divisor);
                    if (qr[1].signum() != 0) yield atom(expression);
                    put(result, entry.getKey(), qr[0]);
                }
                yield Map.copyOf(result);
            }
            default -> atom(expression);
        };
    }
    private Map<Monomial, BigInteger> atom(Expr expression) { return Map.of(new Monomial(Map.of(expression, 1)), BigInteger.ONE); }
    private Map<Monomial, BigInteger> constant(BigInteger value) {
        var result = new HashMap<Monomial, BigInteger>(); put(result, new Monomial(Map.of()), value); return Map.copyOf(result);
    }
    private Map<Monomial, BigInteger> add(Map<Monomial, BigInteger> left, Map<Monomial, BigInteger> right, BigInteger sign) {
        var result = new HashMap<>(left);
        for (var entry : right.entrySet()) { work.charge(1); put(result, entry.getKey(), result.getOrDefault(entry.getKey(), BigInteger.ZERO).add(entry.getValue().multiply(sign))); }
        return Map.copyOf(result);
    }
    private Map<Monomial, BigInteger> multiply(Map<Monomial, BigInteger> left, Map<Monomial, BigInteger> right) {
        if ((long)left.size() * right.size() > MAX_TERMS * 4L) throw new OutsideFragment();
        var result = new HashMap<Monomial, BigInteger>();
        for (var l : left.entrySet()) for (var r : right.entrySet()) {
            work.charge(1); var monomial = l.getKey().times(r.getKey());
            put(result, monomial, result.getOrDefault(monomial, BigInteger.ZERO).add(l.getValue().multiply(r.getValue())));
        }
        return Map.copyOf(result);
    }
    private void put(Map<Monomial, BigInteger> target, Monomial monomial, BigInteger value) {
        if (modulus != null) value = value.mod(modulus);
        if (value.bitLength() > MAX_COEFFICIENT_BITS) throw new OutsideFragment();
        if (value.signum() == 0) target.remove(monomial); else target.put(monomial, value);
        if (target.size() > MAX_TERMS) throw new OutsideFragment();
    }
}
