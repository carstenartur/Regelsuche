package de.regelsuche.egraph.proof;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.*;
import java.util.List;

/** Refinement lattice of possible signs and integral sorts over exact rational values; the empty set records contradiction. */
public record ScalarGuardFacts(int signs, int integral, boolean defined) {
    public enum Predicate { NONZERO, POSITIVE, NONNEGATIVE, INTEGER, NATURAL, EXACT_RATIONAL, INVERTIBLE }
    public enum Truth { TRUE, FALSE, UNKNOWN, CONFLICT }
    public static final int NEG = 1, ZERO = 2, POS = 4, ALL = 7;
    private static final int INTEGER = 1, FRACTION = 2;
    public static final ScalarGuardFacts UNKNOWN = new ScalarGuardFacts(ALL, INTEGER | FRACTION, false);
    private ScalarGuardFacts(int signs, int integral) { this(signs, integral, true); }

    public ScalarGuardFacts merge(ScalarGuardFacts other) { return new ScalarGuardFacts(signs & other.signs, integral & other.integral, defined || other.defined); }
    public boolean conflict() { return signs == 0 || integral == 0; }
    public Truth nonzero() { return signQuery(NEG | POS); }
    public Truth positive() { return signQuery(POS); }
    public Truth nonnegative() { return signQuery(ZERO | POS); }
    public Truth invertible() { return nonzero(); }
    public Truth integer() { return conflict() ? Truth.CONFLICT : !defined ? Truth.UNKNOWN : integral == INTEGER ? Truth.TRUE : integral == FRACTION ? Truth.FALSE : Truth.UNKNOWN; }
    public Truth evaluate(Predicate predicate) {
        if (conflict()) return Truth.CONFLICT;
        return switch (predicate) {
            case NONZERO, INVERTIBLE -> nonzero();
            case POSITIVE -> positive();
            case NONNEGATIVE -> nonnegative();
            case INTEGER -> integer();
            case EXACT_RATIONAL -> defined ? Truth.TRUE : Truth.UNKNOWN;
            case NATURAL -> integer() == Truth.FALSE || nonnegative() == Truth.FALSE ? Truth.FALSE
                : integer() == Truth.TRUE && nonnegative() == Truth.TRUE ? Truth.TRUE : Truth.UNKNOWN;
        };
    }
    private Truth signQuery(int admitted) {
        if (conflict()) return Truth.CONFLICT;
        if (!defined) return Truth.UNKNOWN;
        if ((signs & admitted) == 0) return Truth.FALSE;
        return (signs & ~admitted) == 0 ? Truth.TRUE : Truth.UNKNOWN;
    }

    public static ScalarGuardFacts of(Expr value,List<Assumption> assumptions) {
        return switch(value) {
            case NumberExpr n -> new ScalarGuardFacts(1 << (n.value().signum()+1), n.value().isInteger() ? INTEGER : FRACTION);
            case VariableExpr v -> variable(v.name(),assumptions);
            case BinaryExpr b -> combine(b.operator(), of(b.left(),assumptions), of(b.right(),assumptions));
            case FunctionExpr ignored -> UNKNOWN;
        };
    }

    public static ScalarGuardFacts variable(String symbol, List<Assumption> assumptions) {
        var facts = new ScalarGuardFacts(ALL, INTEGER | FRACTION);
        for (var premise : assumptions) {
            var refinement = premiseFacts(symbol, premise);
            if (refinement.defined) facts = facts.merge(refinement);
        }
        return facts;
    }

    private static ScalarGuardFacts premiseFacts(String symbol, Assumption premise) {
        if (!premise.symbols().equals(List.of(symbol))) return UNKNOWN;
        if (premise.equals(Assumption.positive(symbol))) return new ScalarGuardFacts(POS, INTEGER | FRACTION);
        if (premise.equals(Assumption.nonZero(symbol)) || premise.equals(Assumption.invertible(symbol)))
            return new ScalarGuardFacts(NEG | POS, INTEGER | FRACTION);
        if (premise.equals(Assumption.nonNegative(symbol))) return new ScalarGuardFacts(ZERO | POS, INTEGER | FRACTION);
        if (premise.equals(Assumption.natural(symbol)) || premise.equals(Assumption.domainMembership(symbol, "N")))
            return new ScalarGuardFacts(ZERO | POS, INTEGER);
        if (premise.equals(Assumption.integer(symbol)) || premise.equals(Assumption.domainMembership(symbol, "Z")))
            return new ScalarGuardFacts(ALL, INTEGER);
        return premise.kind() == Assumption.Kind.CUSTOM_PREDICATE ? customFacts(symbol, premise.expression()) : UNKNOWN;
    }

    private static ScalarGuardFacts customFacts(String symbol, String expression) {
        String statement = expression.replace(" ", "");
        if (statement.equals(symbol + "=0") || statement.equals(symbol + "==0")) return new ScalarGuardFacts(ZERO, INTEGER);
        if (statement.equals(symbol + "<0")) return new ScalarGuardFacts(NEG, INTEGER | FRACTION);
        return UNKNOWN;
    }

    public static ScalarGuardFacts combine(BinaryOperator op, ScalarGuardFacts left, ScalarGuardFacts right) {
        if (left.conflict() || right.conflict()) return new ScalarGuardFacts(0,0);
        if (!left.defined || !right.defined || op == BinaryOperator.POW || op == BinaryOperator.DIV && (right.signs & ZERO) != 0) return UNKNOWN;
        int result = 0;
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) {
            if ((left.signs & 1 << x+1) == 0 || (right.signs & 1 << y+1) == 0) continue;
            int second = op == BinaryOperator.SUB ? -y : y;
            if (op == BinaryOperator.MUL || op == BinaryOperator.DIV) result |= 1 << (x*y+1);
            else result |= x == -second && x != 0 ? ALL : 1 << (Integer.signum(x+second)+1);
        }
        int sort = op != BinaryOperator.DIV && left.integral == INTEGER && right.integral == INTEGER ? INTEGER : 3;
        return new ScalarGuardFacts(result, sort);
    }
}
