package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Exact evidence for a rational maximization problem with unrestricted variables. */
public final class LinearProgramCertificates {
    private LinearProgramCertificates() {}

    /** Maximizes c^T x subject to Ax ≤ b, in the supplied row and column order. */
    public record Problem(List<List<ExactRational>> a, List<ExactRational> b, List<ExactRational> c) {
        public Problem {
            Objects.requireNonNull(a);
            Objects.requireNonNull(b);
            Objects.requireNonNull(c);
            if (a.isEmpty() || c.isEmpty() || a.size() != b.size()) {
                throw new IllegalArgumentException("INVALID_LINEAR_PROGRAM_SHAPE");
            }
            for (List<ExactRational> row : a) {
                if (row.size() != c.size()) {
                    throw new IllegalArgumentException("INVALID_LINEAR_PROGRAM_SHAPE");
                }
            }
            var rows = new ArrayList<List<ExactRational>>(a.size());
            for (List<ExactRational> row : a) rows.add(List.copyOf(row));
            a = List.copyOf(rows);
            b = List.copyOf(b);
            c = List.copyOf(c);
        }

        public int constraints() { return a.size(); }

        public int variables() { return c.size(); }

        long storageCells() {
            return (long) constraints() * (variables() + 2L) + variables();
        }
    }

    public sealed interface Certificate permits Infeasible, Optimal, Unbounded {
        Problem source();
    }

    /** A nonnegative Farkas multiplier y with A^T y = 0 and b^T y < 0. */
    public record Infeasible(Problem source, List<ExactRational> y) implements Certificate {
        public Infeasible {
            Objects.requireNonNull(source);
            y = List.copyOf(y);
        }
    }

    /** A feasible x and a feasible dual multiplier y with equal objective values. */
    public record Optimal(Problem source, List<ExactRational> x, List<ExactRational> y)
            implements Certificate {
        public Optimal {
            Objects.requireNonNull(source);
            x = List.copyOf(x);
            y = List.copyOf(y);
        }
    }

    /** A feasible x and a ray v with Av ≤ 0 and c^T v = 1. */
    public record Unbounded(Problem source, List<ExactRational> x, List<ExactRational> v)
            implements Certificate {
        public Unbounded {
            Objects.requireNonNull(source);
            x = List.copyOf(x);
            v = List.copyOf(v);
        }
    }
}
