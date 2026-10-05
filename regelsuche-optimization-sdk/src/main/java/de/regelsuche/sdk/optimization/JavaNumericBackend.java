package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.*;
import de.regelsuche.search.program.ComputationBackend;
import java.math.BigInteger;
import java.util.*;

/** Java value semantics only; this evaluator never executes consumer source code. */
final class JavaNumericBackend implements ComputationBackend {
    private final Map<String, Type> inputs;
    JavaNumericBackend(Map<String, Type> inputs) { this.inputs = Map.copyOf(inputs); }
    @Override public Type literalType(NumberExpr literal) {
        if (!literal.value().isInteger()) throw new IllegalArgumentException("INTEGER_LITERAL_REQUIRED");
        return NumericKind.BIG_INTEGER.type();
    }
    @Override public Object literal(NumberExpr literal) { literalType(literal); return literal.value().numerator(); }
    @Override public long leafStorage(Type type) { return type.equals(NumericKind.BIG_INTEGER.type()) ? 2 : 1; }
    @Override public Operation operation(Expr expression) {
        if (!(expression instanceof FunctionExpr function)) throw new IllegalArgumentException("TYPED_JAVA_OPERATION_REQUIRED");
        var decoded = JavaExpressions.decode(function);
        var kind = decoded.kind();
        String action = decoded.action();
        if (action.equals("literal")) {
            JavaExpressions.literalValue(expression);
            return new Operation(function.name(), List.of(NumericKind.BIG_INTEGER.type()), kind.type(), 0, 0);
        }
        if (action.startsWith("cast_")) {
            var from = JavaExpressions.castSourceKind(expression).orElseThrow();
            if (from == NumericKind.BIG_INTEGER || kind == NumericKind.BIG_INTEGER)
                throw new IllegalArgumentException("UNSUPPORTED_BIG_INTEGER_CAST");
            return new Operation(function.name(), List.of(from.type()), kind.type(), 1, 1);
        }
        var op = NumericOperation.valueOf(action.toUpperCase(Locale.ROOT));
        if (kind == NumericKind.BYTE || kind == NumericKind.SHORT || kind == NumericKind.CHAR)
            throw new IllegalArgumentException("JAVA_ARITHMETIC_REQUIRES_PROMOTION");
        var argumentTypes = argumentTypes(function, kind, op);
        if (kind.floatingPoint() && !EnumSet.of(NumericOperation.ADD, NumericOperation.SUBTRACT, NumericOperation.MULTIPLY,
                NumericOperation.DIVIDE, NumericOperation.REMAINDER, NumericOperation.NEGATE, NumericOperation.ABS).contains(op))
            throw new IllegalArgumentException("UNSUPPORTED_IEEE_OPERATION");
        if (kind != NumericKind.BIG_INTEGER && EnumSet.of(NumericOperation.MOD, NumericOperation.MOD_POW,
                NumericOperation.MOD_MULTIPLY, NumericOperation.POW).contains(op)) throw new IllegalArgumentException("BIG_INTEGER_OPERATION_REQUIRED");
        if (kind == NumericKind.BIG_INTEGER && (op.name().endsWith("EXACT") || op == NumericOperation.UNSIGNED_SHIFT_RIGHT))
            throw new IllegalArgumentException("UNSUPPORTED_BIG_INTEGER_OPERATION");
        long work = op == NumericOperation.MOD_POW ? 1000 : op == NumericOperation.MOD_MULTIPLY ? 10 : kind == NumericKind.BIG_INTEGER ? 4 : 1;
        if (op == NumericOperation.MOD_POW && function.arguments().size() == 3 && function.arguments().get(1) instanceof NumberExpr n && n.value().signum() >= 0)
            work = Math.max(1, 2L * n.value().numerator().bitLength());
        return new Operation(function.name(), argumentTypes, kind.type(), work, kind == NumericKind.BIG_INTEGER ? 2 : 1);
    }
    private List<Type> argumentTypes(FunctionExpr function, NumericKind kind, NumericOperation op) {
        int arity = switch (op) {
            case NEGATE, ABS, NOT, NEGATE_EXACT -> 1;
            case MOD_POW, MOD_MULTIPLY -> 3;
            default -> 2;
        };
        var argumentTypes = new ArrayList<Type>(Collections.nCopies(arity, kind.type()));
        if (op == NumericOperation.SHIFT_LEFT || op == NumericOperation.SHIFT_RIGHT || op == NumericOperation.UNSIGNED_SHIFT_RIGHT) {
            if (kind.floatingPoint()) throw new IllegalArgumentException("FLOATING_SHIFT_UNSUPPORTED");
            if (function.arguments().size() != 2) throw new IllegalArgumentException("OPERATOR_ARITY");
            var distance = JavaExpressions.kindOf(function.arguments().get(1), inputs);
            if (kind == NumericKind.BIG_INTEGER && distance != NumericKind.INT) throw new IllegalArgumentException("BIG_INTEGER_SHIFT_REQUIRES_INT");
            if (distance != NumericKind.INT && distance != NumericKind.LONG) throw new IllegalArgumentException("SHIFT_DISTANCE_PROMOTION");
            argumentTypes.set(1, distance.type());
        }
        if (op == NumericOperation.POW) {
            if (kind != NumericKind.BIG_INTEGER) throw new IllegalArgumentException("UNSUPPORTED_POWER_OPERATION");
            argumentTypes.set(1, NumericKind.INT.type());
        }
        return argumentTypes;
    }
    @Override public Object apply(Operation operation, List<Object> arguments) {
        var decoded = JavaExpressions.decode(new FunctionExpr(operation.id(), List.of()));
        var kind = decoded.kind(); String action = decoded.action();
        if (action.equals("literal")) {
            var literal = new FunctionExpr(operation.id(), List.of(JavaExpressions.literal((BigInteger)arguments.getFirst())));
            return JavaExpressions.literalValue(literal);
        }
        if (action.startsWith("cast_")) return cast(arguments.getFirst(), kind);
        var op = NumericOperation.valueOf(action.toUpperCase(Locale.ROOT));
        Object x = arguments.getFirst(); Object y = arguments.size() > 1 ? arguments.get(1) : null;
        if (kind == NumericKind.BIG_INTEGER) return big(op, (BigInteger)x, y, arguments.size() > 2 ? (BigInteger) arguments.get(2) : null);
        if (kind == NumericKind.FLOAT) return floating32(op, (Float)x, y == null ? 0 : (Float)y);
        if (kind == NumericKind.DOUBLE) return floating64(op, (Double)x, y == null ? 0 : (Double)y);
        if (kind == NumericKind.INT) return integral32(op, ((Number)x).intValue(), y == null ? 0 : ((Number)y).intValue());
        return integral64(op, ((Number)x).longValue(), y == null ? 0 : ((Number)y).longValue());
    }
    private static Object big(NumericOperation op, BigInteger x, Object other, BigInteger modulus) {
        BigInteger y = other instanceof BigInteger value ? value : null;
        return switch (op) {
            case ADD -> x.add(y); case SUBTRACT -> x.subtract(y); case MULTIPLY -> x.multiply(y);
            case DIVIDE -> x.divide(y); case REMAINDER -> x.remainder(y); case MOD -> x.mod(y);
            case NEGATE -> x.negate(); case ABS -> x.abs(); case AND -> x.and(y); case OR -> x.or(y);
            case XOR -> x.xor(y); case NOT -> x.not(); case SHIFT_LEFT -> x.shiftLeft(((Number)other).intValue());
            case SHIFT_RIGHT -> x.shiftRight(((Number)other).intValue()); case POW -> x.pow((Integer)other);
            case MOD_POW -> x.modPow(y, modulus); case MOD_MULTIPLY -> x.multiply(y).mod(modulus);
            default -> throw new IllegalArgumentException("UNSUPPORTED_BIG_INTEGER_OPERATION");
        };
    }
    private static int integral32(NumericOperation op, int x, int y) {
        return switch (op) {
            case ADD -> x + y; case SUBTRACT -> x - y; case MULTIPLY -> x * y; case DIVIDE -> x / y; case REMAINDER -> x % y;
            case NEGATE -> -x; case ABS -> Math.abs(x); case AND -> x & y; case OR -> x | y; case XOR -> x ^ y;
            case NOT -> ~x; case SHIFT_LEFT -> x << y; case SHIFT_RIGHT -> x >> y; case UNSIGNED_SHIFT_RIGHT -> x >>> y;
            case ADD_EXACT -> Math.addExact(x, y); case SUBTRACT_EXACT -> Math.subtractExact(x, y);
            case MULTIPLY_EXACT -> Math.multiplyExact(x, y); case NEGATE_EXACT -> Math.negateExact(x);
            default -> throw new IllegalArgumentException("UNSUPPORTED_INTEGRAL_OPERATION");
        };
    }
    private static long integral64(NumericOperation op, long x, long y) {
        return switch (op) {
            case ADD -> x + y; case SUBTRACT -> x - y; case MULTIPLY -> x * y; case DIVIDE -> x / y; case REMAINDER -> x % y;
            case NEGATE -> -x; case ABS -> Math.abs(x); case AND -> x & y; case OR -> x | y; case XOR -> x ^ y;
            case NOT -> ~x; case SHIFT_LEFT -> x << y; case SHIFT_RIGHT -> x >> y; case UNSIGNED_SHIFT_RIGHT -> x >>> y;
            case ADD_EXACT -> Math.addExact(x, y); case SUBTRACT_EXACT -> Math.subtractExact(x, y);
            case MULTIPLY_EXACT -> Math.multiplyExact(x, y); case NEGATE_EXACT -> Math.negateExact(x);
            default -> throw new IllegalArgumentException("UNSUPPORTED_INTEGRAL_OPERATION");
        };
    }
    private static float floating32(NumericOperation op, float x, float y) {
        return switch (op) { case ADD -> x+y; case SUBTRACT -> x-y; case MULTIPLY -> x*y; case DIVIDE -> x/y;
            case REMAINDER -> x%y; case NEGATE -> -x; case ABS -> Math.abs(x); default -> throw new IllegalArgumentException("UNSUPPORTED_IEEE_OPERATION"); };
    }
    private static double floating64(NumericOperation op, double x, double y) {
        return switch (op) { case ADD -> x+y; case SUBTRACT -> x-y; case MULTIPLY -> x*y; case DIVIDE -> x/y;
            case REMAINDER -> x%y; case NEGATE -> -x; case ABS -> Math.abs(x); default -> throw new IllegalArgumentException("UNSUPPORTED_IEEE_OPERATION"); };
    }
    static Object cast(Object value, NumericKind to) {
        Number n = value instanceof Character c ? Integer.valueOf(c) : (Number)value;
        // Float/Double.intValue and longValue implement Java saturation/truncation; narrowing then wraps.
        return switch (to) {
            case BYTE -> (byte)n.intValue(); case SHORT -> (short)n.intValue(); case CHAR -> (char)n.intValue();
            case INT -> n.intValue(); case LONG -> n.longValue(); case FLOAT -> n.floatValue(); case DOUBLE -> n.doubleValue();
            default -> throw new IllegalArgumentException("UNSUPPORTED_CAST");
        };
    }
}
