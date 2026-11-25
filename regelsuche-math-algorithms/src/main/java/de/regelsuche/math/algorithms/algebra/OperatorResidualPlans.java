package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.algebra.OperatorChecks.need;

/** Finite rational operators with an exactly checked predictor and optional factored correction. */
public final class OperatorResidualPlans {
    private OperatorResidualPlans() {}

    public enum Status { VERIFIED, INVALID, UNSUPPORTED, STOPPED }
    public record Result<T>(Status status, String detail, Optional<T> value, long work) {
        public T requireValue() { return value.orElseThrow(() -> new IllegalStateException(detail)); }
    }

    /** Staged dense dot products; a nonempty row starts with its first product. */
    public record Cost(long multiplies, long additions) {
        public long total() { return Math.addExact(multiplies, additions); }
        static Cost dense(PolynomialMatrix m) {
            return new Cost((long) m.rows() * m.columns(), (long) m.rows() * (m.columns() - 1));
        }
        Cost plus(Cost other) {
            return new Cost(Math.addExact(multiplies, other.multiplies), Math.addExact(additions, other.additions));
        }
        Cost updates(int rows) { return plus(new Cost(0, rows)); }
    }

    /** The residual image lies in the column span of lift, and has rank at most lift.columns(). */
    public record Factors(PolynomialMatrix lift, PolynomialMatrix coordinates) {
        public Factors { Objects.requireNonNull(lift); Objects.requireNonNull(coordinates); }
    }
    public record Plan(PolynomialMatrix source, ExactMatrixExpression predictor, Optional<Factors> correction) {
        public Plan {
            Objects.requireNonNull(source);
            Objects.requireNonNull(predictor);
            Objects.requireNonNull(correction);
        }
        public ExactMatrixExpression expression() {
            if (correction.isEmpty()) return predictor;
            var factors = correction.orElseThrow();
            return new ExactMatrixExpression.Sum(predictor, new ExactMatrixExpression.Product(
                new ExactMatrixExpression.Matrix("lift", factors.lift()),
                new ExactMatrixExpression.Matrix("coordinates", factors.coordinates())));
        }
    }

    public record Selection(Plan plan, Cost cost, Cost directCost) {}

    public static Result<Cost> verify(PolynomialMatrix source, Plan plan, IterationLimits limits,
            BooleanSupplier cancel) {
        return OperatorChecks.run(limits, cancel, checks -> verify(source, plan, checks));
    }

    private static Cost verify(PolynomialMatrix source, Plan plan, OperatorChecks checks) {
        need(plan != null, "MISSING_PLAN");
        checks.input(source);
        checks.input(plan.source());
        need(checks.algebra.equal(source, plan.source()), "SOURCE_MISMATCH");
        var expression = plan.expression();
        var shape = checks.inspect(expression, 0);
        need(shape.rows() == source.rows() && shape.columns() == source.columns(), "IDENTITY_MISMATCH");
        need(checks.algebra.equal(source, checks.algebra.evaluate(expression)), "IDENTITY_MISMATCH");
        return shape.cost();
    }

    /** Compare all supplied plans with the dense source. Invalid evidence fails the request. Ties keep the first. */
    public static Result<Selection> select(PolynomialMatrix source, List<Plan> plans, int maxCandidates,
            IterationLimits limits, BooleanSupplier cancel) {
        return OperatorChecks.run(limits, cancel, checks -> {
            if (maxCandidates < 0) throw new IllegalArgumentException("NEGATIVE_CANDIDATE_LIMIT");
            need(plans != null, "MISSING_PLANS");
            if (plans.size() > maxCandidates) throw new OperatorChecks.Rejected(Status.STOPPED, "CANDIDATE_LIMIT");
            checks.input(source);
            var direct = Cost.dense(source);
            var best = new Selection(new Plan(source, new ExactMatrixExpression.Matrix("source", source),
                Optional.empty()), direct, direct);
            for (var plan : plans) {
                checks.work.charge(1);
                var cost = verify(source, plan, checks);
                if (cost.total() < best.cost().total()) best = new Selection(plan, cost, direct);
            }
            return best;
        });
    }

    /** Given a common rational eigenbasis and its inverse, construct a correction only on mismatching columns. */
    public static Result<Plan> spectral(PolynomialMatrix source, ExactMatrixExpression predictor,
            PolynomialMatrix basis, PolynomialMatrix inverse, IterationLimits limits, BooleanSupplier cancel) {
        return OperatorChecks.run(limits, cancel, checks -> {
            checks.input(source);
            checks.input(basis);
            checks.input(inverse);
            int n = source.rows();
            need(n == source.columns() && n == basis.rows() && n == basis.columns()
                && n == inverse.rows() && n == inverse.columns(), "BASIS_DIMENSION_MISMATCH");
            var shape = checks.inspect(predictor, 0);
            need(n == shape.rows() && n == shape.columns(), "PREDICTOR_DIMENSION_MISMATCH");
            var unit = checks.unit(n);
            need(checks.algebra.equal(checks.times(basis, inverse), unit)
                && checks.algebra.equal(checks.times(inverse, basis), unit), "BASIS_INVERSE_MISMATCH");
            var predicted = checks.algebra.evaluate(predictor);
            var targetDiagonal = inBasis(source, basis, inverse, checks);
            var predictedDiagonal = inBasis(predicted, basis, inverse, checks);
            var exceptional = new ArrayList<Integer>();
            var differences = new ArrayList<Polynomial>();
            for (int i = 0; i < n; i++) {
                checks.work.charge(1);
                var delta = checks.algebra.add(targetDiagonal.get(i, i),
                    checks.algebra.multiply(OperatorChecks.MINUS_ONE, predictedDiagonal.get(i, i)));
                if (!delta.isZero()) {
                    exceptional.add(i);
                    differences.add(delta);
                }
            }
            Optional<Factors> factors = Optional.empty();
            if (!exceptional.isEmpty()) {
                var lift = checks.matrix(n, exceptional.size(),
                    (r, j) -> checks.algebra.multiply(basis.get(r, exceptional.get(j)), differences.get(j)));
                var coordinates = checks.matrix(exceptional.size(), n, (j, c) -> inverse.get(exceptional.get(j), c));
                factors = Optional.of(new Factors(lift, coordinates));
            }
            var plan = new Plan(source, predictor, factors);
            verify(source, plan, checks);
            return plan;
        });
    }

    private static PolynomialMatrix inBasis(PolynomialMatrix operator, PolynomialMatrix basis,
            PolynomialMatrix inverse, OperatorChecks checks) {
        var diagonal = checks.times(inverse, checks.times(operator, basis));
        for (int i = 0; i < diagonal.rows(); i++) for (int j = 0; j < diagonal.columns(); j++) {
            checks.work.charge(1);
            need(i == j || diagonal.get(i, j).isZero(), "NOT_COMMON_EIGENBASIS");
        }
        return diagonal;
    }
}
