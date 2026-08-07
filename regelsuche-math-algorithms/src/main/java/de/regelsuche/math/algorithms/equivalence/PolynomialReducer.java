package de.regelsuche.math.algorithms.equivalence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PolynomialReducer {
    public PreparedBasis prepare(List<Polynomial> basis, MonomialOrder order) {
        return new PreparedBasis(indexedReducers(basis, order), order);
    }

    public ReductionResult reduce(Polynomial polynomial, List<Polynomial> basis, MonomialOrder order, int maxSteps) {
        return reduce(polynomial, prepare(basis, order), maxSteps);
    }

    public ReductionResult reduce(Polynomial polynomial, PreparedBasis preparedBasis, int maxSteps) {
        Polynomial remainder = Polynomial.zero();
        Polynomial current = polynomial;
        int steps = 0;
        while (!current.isZero()) {
            if (steps >= maxSteps) {
                return new ReductionResult(remainder, steps, true);
            }
            Term currentLeadingTerm = current.leadingTerm(preparedBasis.order()).orElseThrow();
            Optional<Reduction> reduction = firstReduction(currentLeadingTerm, preparedBasis.reducers());
            if (reduction.isPresent()) {
                Reduction divisor = reduction.orElseThrow();
                current = current.subtract(divisor.polynomial().multiply(
                    currentLeadingTerm.monomial().divideBy(divisor.leadingTerm().monomial()),
                    currentLeadingTerm.coefficient().divide(divisor.leadingTerm().coefficient())
                ));
            } else {
                Polynomial leadingPolynomial = Polynomial.term(
                    currentLeadingTerm.monomial(),
                    currentLeadingTerm.coefficient()
                );
                remainder = remainder.add(leadingPolynomial);
                current = current.subtract(leadingPolynomial);
            }
            steps++;
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

    private List<Reduction> indexedReducers(List<Polynomial> basis, MonomialOrder order) {
        List<Reduction> reductions = new ArrayList<>();
        for (Polynomial polynomial : basis) {
            if (!polynomial.isZero()) {
                reductions.add(new Reduction(polynomial, polynomial.leadingTerm(order).orElseThrow()));
            }
        }
        reductions.sort(Comparator
            .comparing((Reduction reduction) -> reduction.leadingTerm().monomial(), order)
            .thenComparing(reduction -> reduction.polynomial().toCanonicalString(order)));
        return List.copyOf(reductions);
    }

    private Optional<Reduction> firstReduction(Term leadingTerm, List<Reduction> reducers) {
        int leadingDegree = leadingTerm.monomial().totalDegree();
        for (Reduction reduction : reducers) {
            Monomial divisor = reduction.leadingTerm().monomial();
            if (divisor.totalDegree() <= leadingDegree && divisor.divides(leadingTerm.monomial())) {
                return Optional.of(reduction);
            }
        }
        return Optional.empty();
    }

    public record ReductionResult(Polynomial remainder, int steps, boolean budgetExhausted) {
    }

    public static final class PreparedBasis {
        private final List<Reduction> reducers;
        private final MonomialOrder order;

        private PreparedBasis(List<Reduction> reducers, MonomialOrder order) {
            this.reducers = reducers;
            this.order = order;
        }

        private List<Reduction> reducers() {
            return reducers;
        }

        private MonomialOrder order() {
            return order;
        }
    }

    private record Reduction(Polynomial polynomial, Term leadingTerm) {
    }
}
