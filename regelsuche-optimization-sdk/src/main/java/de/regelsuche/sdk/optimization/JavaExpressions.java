package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.*;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.*;

/** Typed constructors over the existing Expr IR. Floating literals store their exact raw bits. */
public final class JavaExpressions {
    private JavaExpressions() {}
    public static Expr literal(BigInteger value) { return new NumberExpr(ExactRational.integer(value)); }
    public static Expr literal(int value) { return encodedLiteral(NumericKind.INT, BigInteger.valueOf(value)); }
    public static Expr literal(long value) { return encodedLiteral(NumericKind.LONG, BigInteger.valueOf(value)); }
    public static Expr literal(float value) { return encodedLiteral(NumericKind.FLOAT, BigInteger.valueOf(Float.floatToRawIntBits(value))); }
    public static Expr literal(double value) { return encodedLiteral(NumericKind.DOUBLE, BigInteger.valueOf(Double.doubleToRawLongBits(value))); }
    public static Expr literal(byte value) { return encodedLiteral(NumericKind.BYTE, BigInteger.valueOf(value)); }
    public static Expr literal(short value) { return encodedLiteral(NumericKind.SHORT, BigInteger.valueOf(value)); }
    public static Expr literal(char value) { return encodedLiteral(NumericKind.CHAR, BigInteger.valueOf(value)); }
    private static Expr encodedLiteral(NumericKind kind, BigInteger value) {
        return new FunctionExpr(prefix(kind) + "literal", List.of(literal(value)));
    }
    public static Expr operation(NumericKind kind, NumericOperation operation, Expr... arguments) {
        return new FunctionExpr(prefix(kind) + operation.name().toLowerCase(Locale.ROOT), List.of(arguments));
    }
    public static Expr cast(NumericKind from, NumericKind to, Expr expression) {
        if (from == NumericKind.BIG_INTEGER || to == NumericKind.BIG_INTEGER) throw new IllegalArgumentException("NO_IMPLICIT_BIG_INTEGER_CONVERSION");
        return new FunctionExpr(prefix(to) + "cast_" + from.name().toLowerCase(Locale.ROOT), List.of(expression));
    }
    public static boolean isLiteral(Expr expression) { return expression instanceof NumberExpr || expression instanceof FunctionExpr f && f.name().endsWith("_literal"); }
    public static Optional<NumericOperation> operationOf(Expr expression) {
        if (!(expression instanceof FunctionExpr function)) return Optional.empty();
        var decoded = decode(function);
        if (decoded.action().equals("literal") || decoded.action().startsWith("cast_")) return Optional.empty();
        return Optional.of(NumericOperation.valueOf(decoded.action().toUpperCase(Locale.ROOT)));
    }
    public static NumericKind resultKind(Expr expression) {
        if (expression instanceof NumberExpr) return NumericKind.BIG_INTEGER;
        if (expression instanceof FunctionExpr function) return decode(function).kind();
        throw new IllegalArgumentException("VARIABLE_KIND_REQUIRES_PLAN");
    }
    public static NumericKind kindOf(Expr expression, Map<String, de.regelsuche.search.program.ComputationBackend.Type> inputs) {
        if (expression instanceof VariableExpr variable) {
            var type = inputs.get(variable.name());
            if (type == null) throw new IllegalArgumentException("UNBOUND_NUMERIC_INPUT");
            return NumericKind.fromType(type);
        }
        return resultKind(expression);
    }
    public static Object literalValue(Expr expression) {
        if (expression instanceof NumberExpr number) {
            if (!number.value().isInteger()) throw new IllegalArgumentException("INTEGER_LITERAL_REQUIRED");
            return number.value().numerator();
        }
        if (!(expression instanceof FunctionExpr f) || !decode(f).action().equals("literal")
                || f.arguments().size() != 1 || !(f.arguments().getFirst() instanceof NumberExpr number)
                || !number.value().isInteger()) throw new IllegalArgumentException("TYPED_LITERAL_REQUIRED");
        BigInteger value = number.value().numerator();
        return switch (decode(f).kind()) {
            case BIG_INTEGER -> value;
            case BYTE -> { if (value.bitLength() > 7) throw new IllegalArgumentException("BYTE_LITERAL_RANGE"); yield value.byteValue(); }
            case SHORT -> { if (value.bitLength() > 15) throw new IllegalArgumentException("SHORT_LITERAL_RANGE"); yield value.shortValue(); }
            case CHAR -> { if (value.signum() < 0 || value.bitLength() > 16) throw new IllegalArgumentException("CHAR_LITERAL_RANGE"); yield (char)value.intValue(); }
            case INT -> value.intValueExact();
            case LONG -> value.longValueExact();
            case FLOAT -> Float.intBitsToFloat(value.intValueExact());
            case DOUBLE -> Double.longBitsToDouble(value.longValueExact());
        };
    }
    public static Optional<NumericKind> castSourceKind(Expr expression) {
        if (!(expression instanceof FunctionExpr function)) return Optional.empty();
        String action = decode(function).action();
        return action.startsWith("cast_") ? Optional.of(NumericKind.valueOf(action.substring(5).toUpperCase(Locale.ROOT))) : Optional.empty();
    }
    public static List<Expr> operands(Expr expression) {
        return expression instanceof FunctionExpr function ? function.arguments() : List.of();
    }
    static String prefix(NumericKind kind) { return "j_" + kind.name().toLowerCase(Locale.ROOT) + "_"; }
    record Decoded(NumericKind kind, String action) {}
    static Decoded decode(FunctionExpr function) {
        for (var kind : NumericKind.values()) if (function.name().startsWith(prefix(kind)))
            return new Decoded(kind, function.name().substring(prefix(kind).length()));
        throw new IllegalArgumentException("UNKNOWN_JAVA_OPERATION");
    }
}
