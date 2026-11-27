package de.regelsuche.math.algorithms.noncommutative;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison.Evidence;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison.Status;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Assignment;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Matrix;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/** Bounded center discovery retaining every original inverse-domain obligation. */
public final class RationalFormulaCenterSearch {
    private RationalFormulaCenterSearch() {}

    /** Cartesian grid, in supplied value order and ascending variable-id order. */
    public record Policy(List<ExactRational> values, int maximumCenters) {
        public Policy {
            values = List.copyOf(values);
            if (values.isEmpty() || maximumCenters < 0) throw new IllegalArgumentException("INVALID_CENTER_POLICY");
        }

        public static Policy defaults() {
            return new Policy(List.of(ExactRational.ZERO, ExactRational.ONE, ExactRational.NEGATIVE_ONE,
                ExactRational.integer(2), ExactRational.integer(-2)), 256);
        }
    }

    /**
     * Entry grids in supplied order/value order, ascending variable ids, then row-major entries.
     * Exhausting this finite grid is inconclusive; it does not prove an empty common domain.
     */
    public record MatrixPolicy(List<ExactRational> values, List<Integer> orders, int maximumCenters) {
        public MatrixPolicy {
            values = List.copyOf(values);
            orders = List.copyOf(orders);
            if (values.isEmpty() || orders.isEmpty() || maximumCenters < 0
                || orders.stream().anyMatch(order -> order < 1) || new HashSet<>(orders).size() != orders.size())
                throw new IllegalArgumentException("INVALID_MATRIX_CENTER_POLICY");
        }

        public static MatrixPolicy defaults() {
            return new MatrixPolicy(List.of(ExactRational.ZERO, ExactRational.ONE, ExactRational.NEGATIVE_ONE),
                List.of(1, 2), 256);
        }
    }

    /** Evidence is an ordinary comparison certificate, independently replayable without this search. */
    public record Result(Status status, Optional<Assignment> center, Optional<Evidence> evidence,
                         String reason, long work, int centersTried) {}

    public static Result compare(RationalFormula left, RationalFormula right, Policy policy,
                                 IterationLimits limits, BooleanSupplier cancelled) {
        Objects.requireNonNull(left);
        Objects.requireNonNull(right);
        Objects.requireNonNull(policy);
        var work = new RationalFormulaWork(limits, cancelled);
        int tried = 0;
        try {
            work.ledger.charge(0);
            work.reserve(policy.values().size());
            for (var value : policy.values()) work.ledger.check(value);
            if (policy.maximumCenters() == 0) return open("CENTER_LIMIT", work, tried);
            var variables = new TreeSet<Integer>();
            collect(left, variables, work, 1);
            collect(right, variables, work, 1);
            work.reserve(3L * variables.size());
            var ids = List.copyOf(variables);
            int[] digits = new int[ids.size()];
            var leftCertificate = RationalFormulaRealization.build(left, work);
            var rightCertificate = RationalFormulaRealization.build(right, work);
            do {
                if (tried == policy.maximumCenters()) return open("CENTER_LIMIT", work, tried);
                work.ledger.charge(1);
                work.reserve(5L * ids.size() + 2);
                var assignments = new TreeMap<Integer, Matrix>();
                for (int index = 0; index < ids.size(); index++) {
                    work.ledger.charge(1L + Integer.SIZE - Integer.numberOfLeadingZeros(index + 1));
                    assignments.put(ids.get(index), new Matrix(1, List.of(policy.values().get(digits[index]))));
                }
                var center = new Assignment(1, assignments);
                tried++;
                var comparison = RationalFormulaComparison.comparePrepared(left, right, leftCertificate,
                    rightCertificate, center, work);
                if (comparison.status() == Status.EQUAL_ON_SHARED_DOMAIN || comparison.status() == Status.DISTINCT) {
                    work.ledger.charge(0);
                    return new Result(comparison.status(), Optional.of(center), comparison.evidence(), "",
                        work.ledger.used(), tried);
                }
                if (!"UNDEFINED_SCALAR_CENTER".equals(comparison.reason()))
                    return open(comparison.reason(), work, tried);
            } while (advance(digits, policy.values().size(), work));
            return open("CENTER_GRID_EXHAUSTED", work, tried);
        } catch (CertificateWork.Stop stopped) {
            return open(stopped.getMessage(), work, tried);
        } catch (RationalFormulaVerifier.Rejected rejected) {
            return open(rejected.getMessage(), work, tried);
        }
    }

    /** Search all requested matrix entry grids with one cumulative work and allocation ledger. */
    public static Result compare(RationalFormula left, RationalFormula right, MatrixPolicy policy,
                                 IterationLimits limits, BooleanSupplier cancelled) {
        Objects.requireNonNull(left);
        Objects.requireNonNull(right);
        Objects.requireNonNull(policy);
        var work = new RationalFormulaWork(limits, cancelled);
        int tried = 0;
        try {
            work.ledger.charge(0);
            work.reserve((long) policy.values().size() + policy.orders().size());
            for (var value : policy.values()) work.ledger.check(value);
            if (policy.maximumCenters() == 0) return open("CENTER_LIMIT", work, tried);
            var variables = new TreeSet<Integer>();
            collect(left, variables, work, 1);
            collect(right, variables, work, 1);
            work.reserve(2L * variables.size());
            var ids = List.copyOf(variables);
            var leftCertificate = RationalFormulaRealization.build(left, work);
            var rightCertificate = RationalFormulaRealization.build(right, work);
            for (int order : policy.orders()) {
                if (tried == policy.maximumCenters()) return open("CENTER_LIMIT", work, tried);
                long square = (long) order * order;
                work.ledger.cells(square);
                long digitCount = square * ids.size();
                work.reserve(digitCount);
                int[] digits = new int[(int) digitCount];
                do {
                    if (tried == policy.maximumCenters()) return open("CENTER_LIMIT", work, tried);
                    work.ledger.charge(1);
                    var center = matrixAssignment(order, ids, policy.values(), digits, work);
                    tried++;
                    var comparison = RationalFormulaComparison.comparePrepared(left, right, leftCertificate,
                        rightCertificate, center, work);
                    if (comparison.status() == Status.EQUAL_ON_SHARED_DOMAIN || comparison.status() == Status.DISTINCT) {
                        work.ledger.charge(0);
                        return new Result(comparison.status(), Optional.of(center), comparison.evidence(), "",
                            work.ledger.used(), tried);
                    }
                    if (!"UNDEFINED_SCALAR_CENTER".equals(comparison.reason())
                        && !"UNDEFINED_MATRIX_CENTER".equals(comparison.reason()))
                        return open(comparison.reason(), work, tried);
                } while (advance(digits, policy.values().size(), work));
            }
            return open("CENTER_GRID_EXHAUSTED", work, tried);
        } catch (CertificateWork.Stop stopped) {
            return open(stopped.getMessage(), work, tried);
        } catch (RationalFormulaVerifier.Rejected rejected) {
            return open(rejected.getMessage(), work, tried);
        }
    }

    private static Assignment matrixAssignment(int order, List<Integer> ids, List<ExactRational> values,
                                                int[] digits, RationalFormulaWork work) {
        int square = order * order;
        work.reserve(4L * ids.size() + 2);
        var assignments = new TreeMap<Integer, Matrix>();
        for (int index = 0; index < ids.size(); index++) {
            work.reserve(2L * square);
            var entries = new ArrayList<ExactRational>(square);
            for (int entry = 0; entry < square; entry++) {
                work.ledger.charge(1);
                entries.add(values.get(digits[index * square + entry]));
            }
            work.ledger.charge(1L + Integer.SIZE - Integer.numberOfLeadingZeros(index + 1));
            assignments.put(ids.get(index), new Matrix(order, entries));
        }
        return new Assignment(order, assignments);
    }

    private static Result open(String reason, RationalFormulaWork work, int tried) {
        return new Result(Status.INCONCLUSIVE, Optional.empty(), Optional.empty(), reason, work.ledger.used(), tried);
    }

    private static void collect(RationalFormula formula, TreeSet<Integer> variables,
                                RationalFormulaWork work, int depth) {
        work.enter(depth);
        switch (formula) {
            case RationalFormula.Variable variable -> {
                work.ledger.charge(1L + Integer.SIZE - Integer.numberOfLeadingZeros(variables.size() + 1));
                work.reserve(1);
                variables.add(variable.id());
            }
            case RationalFormula.Scalar scalar -> work.ledger.check(scalar.value());
            case RationalFormula.Sum sum -> {
                collect(sum.left(), variables, work, depth + 1);
                collect(sum.right(), variables, work, depth + 1);
            }
            case RationalFormula.Product product -> {
                collect(product.left(), variables, work, depth + 1);
                collect(product.right(), variables, work, depth + 1);
            }
            case RationalFormula.Inverse inverse -> collect(inverse.operand(), variables, work, depth + 1);
        }
    }

    /** Mixed-radix increment avoids constructing the potentially enormous grid cardinality. */
    private static boolean advance(int[] digits, int radix, RationalFormulaWork work) {
        for (int index = digits.length - 1; index >= 0; index--) {
            work.ledger.charge(1);
            if (digits[index] + 1 < radix) {
                digits[index]++;
                return true;
            }
            digits[index] = 0;
        }
        return false;
    }
}
