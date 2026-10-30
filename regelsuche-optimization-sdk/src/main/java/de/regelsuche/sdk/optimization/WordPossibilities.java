package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import java.util.*;

/** Independent conservative bit upper bounds; a zero bit is proved zero for every input. */
final class WordPossibilities {
    private final VerificationWork work;
    private final Map<Expr, Long> cache = new IdentityHashMap<>();
    WordPossibilities(VerificationWork work) { this.work = work; }
    boolean disjoint(Expr a, Expr b, NumericKind kind) { return (possible(a, kind, 0) & possible(b, kind, 0)) == 0; }
    private long possible(Expr expression, NumericKind kind, int depth) {
        work.charge(1);
        if (depth > 128) throw new IllegalArgumentException("WORD_FACT_DEPTH_BOUND");
        Long known = cache.get(expression); if (known != null) return known;
        long mask = kind.bits() == 64 ? -1L : (1L << kind.bits()) - 1;
        long result = mask;
        if (JavaExpressions.isLiteral(expression)) result = SemanticChecker.integer(JavaExpressions.literalValue(expression)).longValue();
        else {
            var args = JavaExpressions.operands(expression);
            var cast = JavaExpressions.castSourceKind(expression);
            var op = JavaExpressions.operationOf(expression).orElse(null);
            if (cast.isPresent() && cast.get().integral()) {
                NumericKind from = cast.get(); result = possible(args.getFirst(), from, depth + 1);
                if (from.signed() && from.bits() < kind.bits() && (result & (1L << (from.bits() - 1))) != 0)
                    result |= -1L << from.bits();
            } else if (op == NumericOperation.AND || op == NumericOperation.OR || op == NumericOperation.XOR) {
                long a = possible(args.getFirst(), kind, depth + 1), b = possible(args.get(1), kind, depth + 1);
                result = op == NumericOperation.AND ? a & b : a | b;
            } else if ((op == NumericOperation.SHIFT_LEFT || op == NumericOperation.UNSIGNED_SHIFT_RIGHT)
                    && JavaExpressions.isLiteral(args.get(1))) {
                int distance = SemanticChecker.integer(JavaExpressions.literalValue(args.get(1))).intValue() & (kind.bits() - 1);
                long a = possible(args.getFirst(), kind, depth + 1);
                result = op == NumericOperation.SHIFT_LEFT ? a << distance : a >>> distance;
            }
        }
        result &= mask; cache.put(expression, result); return result;
    }
}
