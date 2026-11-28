package de.regelsuche.solver.portfolio;

import static de.regelsuche.solver.portfolio.Z3ProofSyntax.*;
import static de.regelsuche.solver.portfolio.Z3ProofValues.*;

import de.regelsuche.solver.ir.PolynomialNormalFormSolverBackend;
import de.regelsuche.solver.ir.SolverIr.Goal;
import de.regelsuche.solver.ir.SolverIr.Literal;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Every rewrite is checked without premises; a native rule label is never sufficient. */
final class Z3PolynomialProofRewrites {
    private final Obligation source;
    private final Z3ProofValues values;
    private final PolynomialNormalFormSolverBackend backend = new PolynomialNormalFormSolverBackend();
    private final Map<Pair, Boolean> cache = new HashMap<>();
    private int calls;
    private long admittedWork;

    Z3PolynomialProofRewrites(Obligation source, Z3ProofValues values) {
        this.source = source;
        this.values = values;
    }

    boolean verifies(Equality equality) { return equivalent(equality.left(), equality.right(), 0); }

    private boolean equivalent(Value left, Value right, int depth) {
        if (depth > 128) throw new Invalid();
        if (left.equals(right)) return true;
        if (left instanceof Real a && right instanceof Real b) return polynomial(a, b);
        if (!(left instanceof Formula a) || !(right instanceof Formula b)) return false;
        return equivalentFormulas(a, b, depth);
    }

    private boolean equivalentFormulas(Formula a, Formula b, int depth) {
        Truth first = truth(a, depth + 1), second = truth(b, depth + 1);
        if (first != null && second != null) return first == second;
        if (a instanceof Negation n && b instanceof Negation m) {
            return equivalent(n.operand(), m.operand(), depth + 1);
        }
        if (a instanceof Negation n && n.operand() instanceof Negation inner) {
            return equivalent(inner.operand(), b, depth + 1);
        }
        if (b instanceof Negation n && n.operand() instanceof Negation inner) {
            return equivalent(a, inner.operand(), depth + 1);
        }
        return equivalentRelations(a, b, depth);
    }

    private boolean equivalentRelations(Formula a, Formula b, int depth) {
        if (a instanceof Order firstOrder && b instanceof Order secondOrder) {
            // Preserve direction: unlike equations, sign reversal is not valid for inequalities.
            return polynomial(values.difference(firstOrder.upper(), firstOrder.lower()),
                values.difference(secondOrder.upper(), secondOrder.lower()));
        }
        if (a instanceof Equality equation && b instanceof Conjunction conjunction) {
            return oppositeOrders(equation, conjunction);
        }
        if (b instanceof Equality equation && a instanceof Conjunction conjunction) {
            return oppositeOrders(equation, conjunction);
        }
        if (a instanceof Conjunction conjunction && b instanceof Negation negation) {
            return deMorgan(conjunction, negation);
        }
        if (b instanceof Conjunction conjunction && a instanceof Negation negation) {
            return deMorgan(conjunction, negation);
        }
        return equivalentEquations(a, b, depth);
    }

    private boolean equivalentEquations(Formula a, Formula b, int depth) {
        if (a instanceof Equality equation && b instanceof Disjunction disjunction) {
            return zeroProduct(equation, disjunction);
        }
        if (a instanceof Disjunction disjunction && b instanceof Equality equation) {
            return zeroProduct(equation, disjunction);
        }
        if (a instanceof Equality n && b instanceof Equality m) {
            return equivalent(n.left(), m.left(), depth + 1) && equivalent(n.right(), m.right(), depth + 1)
                || equivalent(n.left(), m.right(), depth + 1) && equivalent(n.right(), m.left(), depth + 1)
                || equivalentRealEquations(n, m);
        }
        return false;
    }

    /** Antisymmetry and reflexivity of the real order, with both directions present. */
    private static boolean oppositeOrders(Equality equation, Conjunction conjunction) {
        if (!(equation.left() instanceof Real left) || !(equation.right() instanceof Real right)
                || conjunction.operands().size() != 2
                || !(conjunction.operands().get(0) instanceof Order first)
                || !(conjunction.operands().get(1) instanceof Order second)) return false;
        return first.lower().equals(second.upper()) && first.upper().equals(second.lower())
            && (first.lower().equals(left) && first.upper().equals(right)
                || first.lower().equals(right) && first.upper().equals(left));
    }

    /** A conjunction is exactly the negation of the disjunction of its complements. */
    private static boolean deMorgan(Conjunction conjunction, Negation negation) {
        if (!(negation.operand() instanceof Disjunction disjunction)
                || conjunction.operands().size() != disjunction.operands().size()) return false;
        for (int i = 0; i < conjunction.operands().size(); i++) {
            if (!complements(conjunction.operands().get(i), disjunction.operands().get(i))) return false;
        }
        return true;
    }

    /**
     * Over the reals a product is zero iff at least one factor is zero.
     * Independently establish the polynomial identity between the equation difference
     * and the product of every disjunct's difference; no native factorization is trusted.
     */
    private boolean zeroProduct(Equality equation, Disjunction disjunction) {
        if (!(equation.left() instanceof Real left) || !(equation.right() instanceof Real right)) return false;
        Real product = null;
        for (Formula operand : disjunction.operands()) {
            if (!(operand instanceof Equality factor) || !(factor.left() instanceof Real a)
                    || !(factor.right() instanceof Real b)) return false;
            Real difference = values.difference(a, b);
            product = product == null ? difference : values.product(product, difference);
        }
        return product != null && (polynomial(values.difference(left, right), product)
            || polynomial(values.difference(right, left), product));
    }

    /** Nonzero constant rescaling of a polynomial difference preserves its zero set. */
    private boolean equivalentRealEquations(Equality first, Equality second) {
        if (!(first.left() instanceof Real a) || !(first.right() instanceof Real b)
                || !(second.left() instanceof Real c) || !(second.right() instanceof Real d)) return false;
        return checkPolynomial(values.difference(a, b), values.difference(c, d), true);
    }

    /** null means not proved constant; REFUTED polynomial identity is not universal disequality. */
    private Truth truth(Formula formula, int depth) {
        if (depth > 128) throw new Invalid();
        return switch (formula) {
            case Truth constant -> constant;
            case Negation negation -> {
                Truth child = truth(negation.operand(), depth + 1);
                yield child == null ? null : child == Truth.TRUE ? Truth.FALSE : Truth.TRUE;
            }
            case Equality equality -> equalityTruth(equality, depth + 1);
            case Disjunction disjunction -> disjunctionTruth(disjunction, depth + 1);
            case Conjunction conjunction -> conjunctionTruth(conjunction, depth + 1);
            case Order order -> order.lower().equals(order.upper()) ? Truth.TRUE : null;
        };
    }

    private Truth disjunctionTruth(Disjunction disjunction, int depth) {
        boolean unknown = false;
        for (Formula operand : disjunction.operands()) {
            Truth value = truth(operand, depth + 1);
            if (value == Truth.TRUE) return Truth.TRUE;
            unknown |= value == null;
        }
        return unknown ? null : Truth.FALSE;
    }

    private Truth conjunctionTruth(Conjunction conjunction, int depth) {
        boolean unknown = false;
        for (Formula operand : conjunction.operands()) {
            Truth value = truth(operand, depth + 1);
            if (value == Truth.FALSE) return Truth.FALSE;
            unknown |= value == null;
        }
        return unknown ? null : Truth.TRUE;
    }

    private Truth equalityTruth(Equality equality, int depth) {
        Value left = equality.left(), right = equality.right();
        if (left.equals(right)) return Truth.TRUE;
        if (left instanceof Real a && right instanceof Real b) {
            if (polynomial(a, b)) return Truth.TRUE;
            if (a.expression() instanceof Literal && b.expression() instanceof Literal) return Truth.FALSE;
            return null;
        }
        Truth a = truth(asFormula(left), depth + 1), b = truth(asFormula(right), depth + 1);
        return a == null || b == null ? null : a == b ? Truth.TRUE : Truth.FALSE;
    }

    private boolean polynomial(Real left, Real right) {
        return checkPolynomial(left, right, false);
    }

    private boolean checkPolynomial(Real left, Real right, boolean proportional) {
        var key = new Pair(left, right, proportional);
        Boolean old = cache.get(key);
        if (old != null) return old;
        long estimate = (long) (left.size() + right.size()) * (left.monomials() + right.monomials());
        if (++calls > 128 || (admittedWork += estimate) > 262_144) throw new Invalid();
        boolean result = proportional
            ? backend.proportionalEquationDifferences(left.expression(), right.expression())
            : polynomialIdentity(left, right);
        cache.put(key, result);
        return result;
    }

    private boolean polynomialIdentity(Real left, Real right) {
        var obligation = Obligation.create("native-polynomial-rewrite", source.declarations(), source.theories(),
            List.of(), new Goal(Relation.EQUALS, left.expression(), right.expression()),
            RequestedEvidence.SYMBOLIC_CERTIFICATE, source.provenance());
        return backend.execute(obligation).result().status() == ResultStatus.CONFIRMED;
    }

    private record Pair(Real left, Real right, boolean proportional) { }
}
