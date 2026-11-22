package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.math.algorithms.polynomial.DynamicAlgebraEvaluation.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Splits only at a reached inverse or zero test and continues its enclosing computation on both factors. */
final class DynamicAlgebraBranches {
    private final DynamicAlgebraDomain domain;

    DynamicAlgebraBranches(DynamicAlgebraDomain domain) { this.domain = domain; }

    private record Trace(Decision decision, Trace previous) {}
    private record Component(List<BigInteger> factor, Trace trace) {
        Component decision(List<BigInteger> nextFactor, Decision decision) {
            return new Component(nextFactor, new Trace(decision, trace));
        }
    }
    // A null internal residue propagates an explicitly undefined terminal.
    private record Partial(Component component, List<BigInteger> value) {}

    Certificate build(Request request) {
        var leaves = eval(request.expression(), new Component(domain.modulus, null));
        var branches = new ArrayList<Branch>(leaves.size());
        for (var leaf : leaves) {
            domain.work.charge(1);
            var component = leaf.component();
            var backwards = new ArrayList<Decision>();
            for (var trace = component.trace(); trace != null; trace = trace.previous()) {
                domain.work.charge(1);
                var hint = trace.decision();
                if (hint instanceof Unit unit) hint = new Unit(domain.remainder(unit.inverse(), component.factor()));
                backwards.add(hint);
            }
            branches.add(new Branch(component.factor(), Optional.ofNullable(leaf.value()), backwards.reversed()));
        }
        return new Certificate(request, branches);
    }

    private List<Partial> eval(Expr expression, Component component) {
        domain.work.charge(1);
        return switch (expression) {
            case Literal literal -> List.of(new Partial(component, domain.remainder(literal.coefficients(), component.factor())));
            case Add add -> binary(add.left(), add.right(), component, false);
            case Product product -> binary(product.left(), product.right(), component, true);
            case Inverse inverse -> decide(inverse.operand(), component, null);
            case IfZero test -> decide(test.condition(), component, test);
        };
    }

    private List<Partial> binary(Expr left, Expr right, Component component, boolean multiply) {
        var results = new ArrayList<Partial>();
        for (var a : eval(left, component)) {
            if (a.value() == null) {
                results.add(a);
                continue;
            }
            for (var b : eval(right, a.component())) {
                domain.work.charge(1);
                if (b.value() == null) {
                    results.add(b);
                } else {
                    var raw = multiply ? domain.multiply(a.value(), b.value()) : domain.add(a.value(), b.value());
                    results.add(new Partial(b.component(), domain.remainder(raw, b.component().factor())));
                }
            }
        }
        return results;
    }

    private List<Partial> decide(Expr operand, Component component, IfZero test) {
        var results = new ArrayList<Partial>();
        for (var evaluated : eval(operand, component)) {
            if (evaluated.value() == null) {
                results.add(evaluated);
                continue;
            }
            var current = evaluated.component();
            var factor = current.factor();
            var common = domain.gcd(factor, evaluated.value());
            if (!common.equals(DynamicAlgebraDomain.ONE)) {
                var zero = current.decision(common, new Zero());
                if (test == null) results.add(new Partial(zero, null));
                else results.addAll(eval(test.whenZero(), zero));
            }
            if (!common.equals(factor)) {
                var complement = common.equals(DynamicAlgebraDomain.ONE) ? factor : domain.quotient(factor, common);
                var inverse = domain.inverse(domain.remainder(evaluated.value(), complement), complement);
                var unit = current.decision(complement, new Unit(inverse));
                if (test == null) results.add(new Partial(unit, inverse));
                else results.addAll(eval(test.whenUnit(), unit));
            }
        }
        return results;
    }
}
