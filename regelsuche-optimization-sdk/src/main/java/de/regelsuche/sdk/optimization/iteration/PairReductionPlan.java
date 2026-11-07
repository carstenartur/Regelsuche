package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.iteration.FiniteIteration;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** A proposed linear or quadratic expression in sums of row monomials. */
public record PairReductionPlan(List<Term> terms, int divisor) {
    static final int MAX_TERMS = 2 * IterationPolynomial.MAX_TERMS;

    public PairReductionPlan {
        if (terms.size() > MAX_TERMS || (divisor != 1 && divisor != 2)) {
            throw new IllegalArgumentException("INVALID_PAIR_PLAN");
        }
        terms = List.copyOf(terms);
    }

    /** The empty monomial contributes one per row and represents the row count. */
    public record Moment(List<Integer> columns) implements Comparable<Moment> {
        public Moment {
            if (columns.size() > PairReduction.MAX_DEGREE) {
                throw new IllegalArgumentException("DEGREE_LIMIT");
            }
            columns = columns.stream().sorted().toList();
            if (!columns.isEmpty()
                    && (columns.getFirst() < 0 || columns.getLast() >= PairReduction.MAX_COLUMNS)) {
                throw new IllegalArgumentException("UNKNOWN_COLUMN");
            }
        }

        @Override
        public int compareTo(Moment other) {
            int byDegree = Integer.compare(columns.size(), other.columns.size());
            if (byDegree != 0) {
                return byDegree;
            }
            for (int i = 0; i < columns.size(); i++) {
                int byColumn = Integer.compare(columns.get(i), other.columns.get(i));
                if (byColumn != 0) {
                    return byColumn;
                }
            }
            return 0;
        }
    }

    public record Term(BigInteger coefficient, List<Moment> factors) {
        public Term {
            Objects.requireNonNull(coefficient);
            if (coefficient.signum() == 0 || coefficient.abs().bitLength() > FiniteIteration.MAX_COEFFICIENT_BITS
                    || factors.isEmpty() || factors.size() > 2) {
                throw new IllegalArgumentException("INVALID_PAIR_PLAN_TERM");
            }
            factors = factors.stream().sorted().toList();
        }
    }

    /** Nonconstant row monomials the executable plan needs to accumulate. */
    public List<Moment> accumulators() {
        return terms.stream().flatMap(term -> term.factors().stream())
            .filter(moment -> !moment.columns().isEmpty()).distinct().sorted().toList();
    }
}
