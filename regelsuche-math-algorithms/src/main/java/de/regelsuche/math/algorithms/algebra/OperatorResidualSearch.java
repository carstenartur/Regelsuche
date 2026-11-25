package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.*;

/** Source-derived finite predictor search with independently checked residuals. */
public final class OperatorResidualSearch {
    private OperatorResidualSearch() {}
    public enum Predictor { DIRECT, SUPPLIED, ZERO, IDENTITY, DIAGONAL, TRIDIAGONAL }
    public record Policy(List<Predictor> predictors, int maximumResidualRank) {
        public Policy {
            predictors = List.copyOf(predictors);
            if (predictors.contains(Predictor.DIRECT) || predictors.contains(Predictor.SUPPLIED) || maximumResidualRank < 0)
                throw new IllegalArgumentException("INVALID_RESIDUAL_SEARCH_POLICY");
        }
        public static Policy defaults() {
            return new Policy(List.of(Predictor.ZERO, Predictor.IDENTITY, Predictor.DIAGONAL, Predictor.TRIDIAGONAL), 16);
        }
    }
    public record Candidate(Predictor predictor, int residualRank, Optional<Cost> cost) {}
    public record Discovery(PreparedOperator operator, List<Candidate> candidates) {
        public Discovery { candidates = List.copyOf(candidates); }
    }
    public static Result<Discovery> discover(PolynomialMatrix source, Policy policy, IterationLimits limits,
                                             BooleanSupplier cancelled) {
        Objects.requireNonNull(policy);
        return discover(source, policy, policy.predictors().size(), limits, cancelled);
    }

    /** An enclosing search can additionally limit how many declared predictors may be considered. */
    public static Result<Discovery> discover(PolynomialMatrix source, Policy policy, int maximumCandidates,
                                             IterationLimits limits, BooleanSupplier cancelled) {
        Objects.requireNonNull(policy);
        if (maximumCandidates < 0) throw new IllegalArgumentException("NEGATIVE_CANDIDATE_LIMIT");
        return OperatorChecks.run(limits, cancelled, checks -> {
            if (policy.predictors().size() > maximumCandidates)
                throw new OperatorChecks.Rejected(Status.STOPPED, "CANDIDATE_LIMIT");
            checks.input(source);
            checks.work.cells(policy.predictors().size());
            var direct = new Plan(source, new ExactMatrixExpression.Matrix("source", source), Optional.empty());
            var best = PreparedOperator.create(source, direct, Predictor.DIRECT, 0, null, checks);
            var candidates = new ArrayList<Candidate>();
            for (var kind : policy.predictors()) {
                checks.work.charge(1);
                if (kind == Predictor.IDENTITY && source.rows() != source.columns()) continue;
                var predictor = predictor(source, kind, checks);
                checks.reserve(source.rows(), source.columns());
                var value = checks.algebra.evaluate(predictor);
                var factors = factorResidual(source, value, checks);
                int rank = factors.map(f -> f.lift().columns()).orElse(0);
                checks.reserve(1, 3);
                if (rank > policy.maximumResidualRank()) {
                    candidates.add(new Candidate(kind, rank, Optional.empty()));
                    continue;
                }
                var plan = new Plan(source, predictor, factors);
                var prepared = PreparedOperator.create(source, plan, kind, rank, best.directCost(), checks);
                candidates.add(new Candidate(kind, rank, Optional.of(prepared.cost())));
                if (prepared.cost().total() < best.cost().total()) best = prepared;
            }
            return new Discovery(best, candidates);
        });
    }
    public static Result<PreparedOperator> prepare(PolynomialMatrix source, Plan plan, IterationLimits limits,
                                                   BooleanSupplier cancelled) {
        Objects.requireNonNull(plan);
        return OperatorChecks.run(limits, cancelled, checks -> PreparedOperator.create(source, plan,
            Predictor.SUPPLIED, plan.correction().map(f -> f.lift().columns()).orElse(0), null, checks));
    }

    private static ExactMatrixExpression predictor(PolynomialMatrix source, Predictor kind, OperatorChecks checks) {
        if (kind == Predictor.IDENTITY) return new ExactMatrixExpression.Identity(source.rows());
        var matrix = checks.matrix(source.rows(), source.columns(), (row, column) -> switch (kind) {
            case ZERO -> Polynomial.zero();
            case DIAGONAL -> row.equals(column) ? source.get(row, column) : Polynomial.zero();
            case TRIDIAGONAL -> Math.abs(row - column) <= 1 ? source.get(row, column) : Polynomial.zero();
            default -> throw new IllegalArgumentException("INVALID_PREDICTOR");
        });
        return new ExactMatrixExpression.Matrix(kind.name(), matrix);
    }

    /** R=C*V: C consists of original pivot columns, V of the nonzero rows of RREF(R). */
    private static Optional<Factors> factorResidual(PolynomialMatrix source, PolynomialMatrix predictor,
                                                     OperatorChecks checks) {
        int rows = source.rows();
        int columns = source.columns();
        checks.reserve(rows, columns);
        checks.reserve(rows, columns);
        checks.reserve(1, columns);
        var original = new ExactRational[rows][columns];
        var reduced = new ExactRational[rows][columns];
        for (int row = 0; row < rows; row++) for (int column = 0; column < columns; column++) {
            original[row][column] = checks.work.sub(rational(source.get(row, column), checks),
                rational(predictor.get(row, column), checks));
            reduced[row][column] = original[row][column];
        }
        int[] pivots = new int[columns];
        int rank = 0;
        for (int column = 0; column < columns && rank < rows; column++) {
            int pivot = rank;
            while (pivot < rows) {
                checks.work.charge(1);
                if (!reduced[pivot][column].isZero()) break;
                pivot++;
            }
            if (pivot == rows) continue;
            var swap = reduced[rank];
            reduced[rank] = reduced[pivot];
            reduced[pivot] = swap;
            var divisor = reduced[rank][column];
            for (int j = column; j < columns; j++)
                reduced[rank][j] = checks.work.div(reduced[rank][j], divisor);
            for (int row = 0; row < rows; row++) {
                checks.work.charge(1);
                if (row == rank || reduced[row][column].isZero()) continue;
                var scale = reduced[row][column];
                for (int j = column; j < columns; j++)
                    reduced[row][j] = checks.work.sub(reduced[row][j], checks.work.mul(scale, reduced[rank][j]));
            }
            pivots[rank++] = column;
        }
        if (rank == 0) return Optional.empty();
        var lift = checks.matrix(rows, rank,
            (i,j) -> Polynomial.constant(Rational.fromExact(original[i][pivots[j]])));
        var coordinates = checks.matrix(rank, columns,
            (i,j) -> Polynomial.constant(Rational.fromExact(reduced[i][j])));
        return Optional.of(new Factors(lift, coordinates));
    }

    private static ExactRational rational(Polynomial value, OperatorChecks checks) {
        checks.work.charge(1);
        if (value.isZero()) return ExactRational.ZERO;
        var coefficient = value.terms().values().iterator().next();
        return checks.work.check(new ExactRational(coefficient.numerator(), coefficient.denominator()));
    }
}
