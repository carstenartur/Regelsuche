package de.regelsuche.math.algorithms.equivalence;

import java.util.List;
import java.util.Optional;

public final class PolynomialReducer {
    public ReductionResult reduce(Polynomial polynomial, List<Polynomial> basis, MonomialOrder order, int maxSteps) {
        Polynomial remainder = Polynomial.zero();
        Polynomial current = polynomial;
        int steps = 0;
        while (!current.isZero()) {
            if (steps++ >= maxSteps) {
                return new ReductionResult(remainder, steps, true);
            }
            Term currentLeadingTerm = current.leadingTerm(order).orElseThrow();
            Optional<Reduction> reduction = firstReduction(currentLeadingTerm, basis, order);
            if (reduction.isPresent()) {
                Reduction divisor = reduction.orElseThrow();
                current = current.subtract(divisor.polynomial().multiply(
                    currentLeadingTerm.monomial().divideBy(divisor.leadingTerm().monomial()),
                    currentLeadingTerm.coefficient().divide(divisor.leadingTerm().coefficient())
                ));
            } else {
                Polynomial leadingPolynomial = Polynomial.term(currentLeadingTerm.monomial(), currentLeadingTerm.coefficient());
                remainder = remainder.add(leadingPolynomial);
                current = current.subtract(leadingPolynomial);
            }
        }
        return new ReductionResult(remainder, steps, false);
    }

    public Polynomial sPolynomial(Polynomial left, Polynomial right, MonomialOrder order) {
        Term leftTerm = left.leadingTerm(order).orElseThrow();
        Term rightTerm = right.leadingTerm(order).orElseThrow();
        Monomial lcm = leftTerm.monomial().lcm(rightTerm.monomial());
        Monomial leftMultiplier = lcm.divideBy(leftTerm.monomial());
        Monomial rightMultiplier = lcm.divideBy(rightTerm.monomial());
        Polynomial normalizedLeft = left.multiply(leftMultiplier, Rational.ONE.divide(leftTerm.coefficient()));
        Polynomial normalizedRight = right.multiply(rightMultiplier, Rational.ONE.divide(rightTerm.coefficient()));
        return normalizedLeft.subtract(normalizedRight);
    }

    private Optional<Reduction> firstReduction(Term leadingTerm, List<Polynomial> basis, MonomialOrder order) {
        return basis.stream()
            .map(polynomial -> new Reduction(polynomial, polynomial.leadingTerm(order).orElseThrow()))
            .filter(reduction -> reduction.leadingTerm().monomial().divides(leadingTerm.monomial()))
            .findFirst();
    }

    public record ReductionResult(Polynomial remainder, int steps, boolean budgetExhausted) {
    }

    private record Reduction(Polynomial polynomial, Term leadingTerm) {
    }
}
