package de.regelsuche.math.algorithms.equivalence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
            Optional<Reduction> reduction = firstReduction(currentLeadingTerm, preparedBasis);
            if (reduction.isPresent()) {
                Reduction divisor = reduction.orElseThrow();
                current = current.subtractMultiple(
                    divisor.polynomial(),
                    currentLeadingTerm.monomial().divideBy(divisor.leadingTerm().monomial()),
                    currentLeadingTerm.coefficient().divide(divisor.leadingTerm().coefficient())
                );
            } else {
                remainder = remainder.addTerm(
                    currentLeadingTerm.monomial(),
                    currentLeadingTerm.coefficient()
                );
                current = current.withoutTerm(currentLeadingTerm.monomial());
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
                Term leadingTerm = polynomial.leadingTerm(order).orElseThrow();
                reductions.add(new Reduction(
                    polynomial,
                    leadingTerm,
                    leadingTerm.monomial().totalDegree(),
                    supportMask(leadingTerm.monomial())
                ));
            }
        }
        reductions.sort(Comparator
            .comparing((Reduction reduction) -> reduction.leadingTerm().monomial(), order)
            .thenComparing(reduction -> reduction.polynomial().toCanonicalString(order)));
        return List.copyOf(reductions);
    }

    private Optional<Reduction> firstReduction(Term leadingTerm, PreparedBasis preparedBasis) {
        Monomial target = leadingTerm.monomial();
        int leadingDegree = target.totalDegree();
        long targetSupportMask = supportMask(target);
        for (Reduction reduction : preparedBasis.reducersForDegree(leadingDegree)) {
            if ((reduction.supportMask() & ~targetSupportMask) != 0L) {
                continue;
            }
            if (reduction.leadingTerm().monomial().divides(target)) {
                return Optional.of(reduction);
            }
        }
        return Optional.empty();
    }

    private static long supportMask(Monomial monomial) {
        long mask = 0L;
        for (String variable : monomial.powers().keySet()) {
            int hash = variable.hashCode();
            int firstBit = hash & 63;
            int secondBit = Integer.rotateRight(hash, 16) & 63;
            if (secondBit == firstBit) {
                secondBit = (secondBit + 1) & 63;
            }
            mask |= 1L << firstBit;
            mask |= 1L << secondBit;
        }
        return mask;
    }

    public record ReductionResult(Polynomial remainder, int steps, boolean budgetExhausted) {
    }

    public static final class PreparedBasis {
        private final List<Reduction> reducers;
        private final MonomialOrder order;
        private final int maxReducerDegree;
        private final ConcurrentMap<Integer, List<Reduction>> reducersByMaximumDegree = new ConcurrentHashMap<>();

        private PreparedBasis(List<Reduction> reducers, MonomialOrder order) {
            this.reducers = reducers;
            this.order = order;
            this.maxReducerDegree = reducers.stream()
                .mapToInt(Reduction::leadingDegree)
                .max()
                .orElse(-1);
        }

        private List<Reduction> reducersForDegree(int maximumDegree) {
            if (maximumDegree >= maxReducerDegree) {
                return reducers;
            }
            return reducersByMaximumDegree.computeIfAbsent(maximumDegree, degree -> reducers.stream()
                .filter(reduction -> reduction.leadingDegree() <= degree)
                .toList());
        }

        int reducerCount() {
            return reducers.size();
        }

        int eligibleReducerCount(int maximumDegree) {
            return reducersForDegree(maximumDegree).size();
        }

        int cachedDegreeViewCount() {
            return reducersByMaximumDegree.size();
        }

        private MonomialOrder order() {
            return order;
        }
    }

    private record Reduction(
        Polynomial polynomial,
        Term leadingTerm,
        int leadingDegree,
        long supportMask
    ) {
    }
}
