package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.ComputationBackend;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Bounded sign facts about actual typed Java values, derived only from the caller's contract.
 * Primitive arithmetic and casts stay unknown: mathematical signs need not survive wraparound.
 */
final class EstablishedNumericFacts {
    private static final int NEGATIVE = 1, ZERO = 2, POSITIVE = 4, UNKNOWN = NEGATIVE | ZERO | POSITIVE;
    private final Map<String, ComputationBackend.Type> inputs;
    private final Set<SemanticAssumption> assumptions;
    private final VerificationWork work;

    EstablishedNumericFacts(VerificationWork work) {
        this(Map.of(), Set.of(), work);
    }
    EstablishedNumericFacts(OptimizationRequest request, VerificationWork work) {
        this(request.plan().inputs(), request.assumptions(), work);
    }
    private EstablishedNumericFacts(Map<String, ComputationBackend.Type> inputs,
            Set<SemanticAssumption> assumptions, VerificationWork work) {
        this.inputs = inputs;
        this.assumptions = assumptions;
        this.work = work;
    }
    // A query owns its memo: neither retained candidates nor proof work depend on search history.
    boolean nonzero(Expr expression) { return contained(sign(expression, 0, new IdentityHashMap<>()), NEGATIVE | POSITIVE); }
    boolean positive(Expr expression) { return contained(sign(expression, 0, new IdentityHashMap<>()), POSITIVE); }
    boolean nonnegative(Expr expression) { return contained(sign(expression, 0, new IdentityHashMap<>()), ZERO | POSITIVE); }

    private static boolean contained(int actual, int required) { return actual != 0 && (actual & ~required) == 0; }

    private int sign(Expr expression, int depth, Map<Expr, Integer> signs) {
        work.charge(1);
        if (depth > 64) return UNKNOWN;
        var known = signs.get(expression);
        if (known != null) return known;
        int result;
        if (expression instanceof VariableExpr variable) {
            var type = inputs.get(variable.name());
            if (type == null || NumericKind.fromType(type).floatingPoint()) return UNKNOWN;
            result = NumericKind.fromType(type) == NumericKind.CHAR ? ZERO | POSITIVE : UNKNOWN;
            for (var assumption : assumptions) {
                work.charge(1);
                if (!assumption.subject().equals(variable.name())) continue;
                result &= switch (assumption.kind()) {
                    case POSITIVE -> POSITIVE;
                    case NON_NEGATIVE, NON_NEGATIVE_UPPER_BOUND -> ZERO | POSITIVE;
                    default -> UNKNOWN;
                };
            }
        } else if (JavaExpressions.isLiteral(expression)) {
            if (JavaExpressions.resultKind(expression).floatingPoint()) return UNKNOWN;
            result = bit(SemanticChecker.integer(JavaExpressions.literalValue(expression)).signum());
        } else if (JavaExpressions.resultKind(expression) == NumericKind.BIG_INTEGER) {
            var operation = JavaExpressions.operationOf(expression).orElse(null);
            if (operation == null) return UNKNOWN;
            var args = JavaExpressions.operands(expression);
            result = switch (operation) {
                case NEGATE -> negate(sign(args.getFirst(), depth + 1, signs));
                case ADD -> combine(sign(args.getFirst(), depth + 1, signs), sign(args.get(1), depth + 1, signs), false);
                case SUBTRACT -> combine(sign(args.getFirst(), depth + 1, signs), negate(sign(args.get(1), depth + 1, signs)), false);
                case MULTIPLY -> combine(sign(args.getFirst(), depth + 1, signs), sign(args.get(1), depth + 1, signs), true);
                default -> UNKNOWN;
            };
        } else result = UNKNOWN;
        signs.put(expression, result);
        return result;
    }
    private static int bit(int sign) { return sign < 0 ? NEGATIVE : sign > 0 ? POSITIVE : ZERO; }
    private static int negate(int sign) {
        return (sign & ZERO) | ((sign & NEGATIVE) << 2) | ((sign & POSITIVE) >> 2);
    }
    private int combine(int left, int right, boolean multiply) {
        int result = 0;
        for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
            work.charge(1);
            if ((left & bit(a)) == 0 || (right & bit(b)) == 0) continue;
            result |= multiply ? bit(a * b) : a == 0 ? bit(b) : b == 0 || a == b ? bit(a) : UNKNOWN;
        }
        return result;
    }
}
