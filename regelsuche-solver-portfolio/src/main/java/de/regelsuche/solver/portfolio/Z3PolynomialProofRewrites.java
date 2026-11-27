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
        Truth first = truth(a, depth + 1), second = truth(b, depth + 1);
        if (first != null && second != null) return first == second;
        if (a instanceof Negation n && b instanceof Negation m) {
            return equivalent(n.operand(), m.operand(), depth + 1);
        }
        if (a instanceof Equality n && b instanceof Equality m) {
            return equivalent(n.left(), m.left(), depth + 1) && equivalent(n.right(), m.right(), depth + 1)
                || equivalent(n.left(), m.right(), depth + 1) && equivalent(n.right(), m.left(), depth + 1)
                || equivalentRealEquations(n, m);
        }
        return false;
    }

    /** Both equations hold exactly when their difference is zero. Only polynomial identities
     * between differences (possibly with reversed orientation) justify this rewrite. */
    private boolean equivalentRealEquations(Equality first, Equality second) {
        if (!(first.left() instanceof Real a) || !(first.right() instanceof Real b)
                || !(second.left() instanceof Real c) || !(second.right() instanceof Real d)) return false;
        Real difference = values.difference(a, b);
        return polynomial(difference, values.difference(c, d))
            || polynomial(difference, values.difference(d, c));
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
        };
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
        var key = new Pair(left, right);
        Boolean old = cache.get(key);
        if (old != null) return old;
        long estimate = (long) (left.size() + right.size()) * (left.monomials() + right.monomials());
        if (++calls > 128 || (admittedWork += estimate) > 262_144) throw new Invalid();
        var obligation = Obligation.create("native-polynomial-rewrite", source.declarations(), source.theories(),
            List.of(), new Goal(Relation.EQUALS, left.expression(), right.expression()),
            RequestedEvidence.SYMBOLIC_CERTIFICATE, source.provenance());
        boolean result = backend.execute(obligation).result().status() == ResultStatus.CONFIRMED;
        cache.put(key, result);
        return result;
    }

    private record Pair(Real left, Real right) { }
}
