package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.MAX_RATIONAL_BITS;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.addProduct;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.checked;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.magnitudeBits;

import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.RowOrigin;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.ArithmeticLimit;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.representation.RepresentationBridge.WorkLedger;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** A checked minimal scalar recurrence for a particular observation of an affine transition. */
public final class RecurrenceObservationKrylov {
    private static final int CACHED_POWER_LIMIT = 128;

    private RecurrenceObservationKrylov() { }

    public enum Status { COMPLETE, BUDGET_INCONCLUSIVE, ARITHMETIC_LIMIT }

    /** An exhausted or arithmetic-limited phase never publishes a partial value. */
    public record Result<T>(Status status, Optional<T> value, WorkLedger work) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(work, "work");
            if ((status == Status.COMPLETE) != value.isPresent()) {
                throw new IllegalArgumentException("only a complete result has a value");
            }
        }
    }

    /**
     * Forms and checks the first exact dependence of P, P∘T, P∘T², … over Q.
     * P may use the transition's coordinates through total degree two. Input coefficients
     * use the transition's 128-bit limit. Exact phases limit normalized numerator and
     * denominator magnitudes to 16,384 bits and preflight the RREF intermediate sizes.
     */
    public static Result<VerifiedPlan> prepare(Transition transition, Polynomial observation, Budget budget) {
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(observation, "observation");
        return measured(budget, work -> {
            int dimension = transition.linear().columns();
            int degree = checkedDegree(observation, dimension, work);
            var coefficients = firstDependence(transition, observation, degree, work);
            if (!RecurrenceObservationProof.verify(transition, observation, degree, coefficients, work)) {
                throw new IllegalStateException("independent trajectory proof rejected the coefficient dependence");
            }
            work.add(1L + coefficients.size());
            return new VerifiedPlan(transition, observation, coefficients);
        });
    }

    /** Only successful preparation can issue a plan. Its minimal order holds universally over initial states. */
    public static final class VerifiedPlan {
        private final Transition transition;
        private final Polynomial observation;
        private final List<Rational> coefficients;

        private VerifiedPlan(Transition transition, Polynomial observation, List<Rational> coefficients) {
            this.transition = transition;
            this.observation = observation;
            this.coefficients = List.copyOf(coefficients);
        }

        public int order() {
            return coefficients.size();
        }

        /** In ascending order: a(n + r) = c[0]·a(n) + … + c[r−1]·a(n + r−1). */
        public List<Rational> coefficients() {
            return coefficients;
        }

        /** Binds the first r values, all under the supplied budget. */
        public Result<BoundPlan> bind(List<Rational> initialState, Budget budget) {
            Objects.requireNonNull(initialState, "initialState");
            int dimension = transition.linear().columns();
            if (initialState.size() != dimension) throw new IllegalArgumentException("initial state dimension mismatch");
            return measured(budget, work -> {
                work.add(dimension);
                var state = List.copyOf(initialState);
                state.forEach(RecurrenceInvariantSupport::requireBounded);
                var values = new ArrayList<Rational>(order());
                boolean allZero = true;
                for (int i = 0; i < order(); i++) {
                    var value = RecurrenceObservationProof.evaluate(observation, state, work);
                    values.add(value);
                    allZero &= value.isZero();
                    if (i + 1 < order()) state = RecurrenceObservationProof.advance(transition, state, work);
                }
                work.add(1L + values.size());
                return new BoundPlan(coefficients, List.copyOf(values), allZero);
            });
        }
    }

    /** A verified recurrence with exact base values for one initial state. */
    public static final class BoundPlan {
        private final List<Rational> relation;
        private final List<Rational> base;
        private final boolean allZero;

        private BoundPlan(List<Rational> relation, List<Rational> base, boolean allZero) {
            this.relation = relation;
            this.base = base;
            this.allZero = allZero;
        }

        public Result<Rational> valueAt(BigInteger index, Budget budget) {
            requireIndex(index);
            return measured(budget, work -> query(index, new ArrayList<>(), 0, work));
        }

        /** Returns values in request order under one budget and shares binary powers within the batch. */
        public Result<List<Rational>> valuesAt(List<BigInteger> indices, Budget budget) {
            Objects.requireNonNull(indices, "indices");
            return measured(budget, work -> {
                work.add(1);
                var values = new ArrayList<Rational>();
                var powers = new ArrayList<Rational[]>();
                for (BigInteger index : indices) {
                    requireIndex(index);
                    values.add(query(index, powers, CACHED_POWER_LIMIT, work));
                }
                work.add(values.size());
                return List.copyOf(values);
            });
        }

        private Rational query(BigInteger index, List<Rational[]> powers, int cacheLimit, Work work) {
            work.add(1);
            if (allZero) return Rational.ZERO;
            int order = relation.size();
            if (index.bitLength() < Integer.SIZE && index.intValue() < order) return base.get(index.intValue());
            if (order == 1) {
                var ratio = relation.getFirst();
                if (ratio.isZero()) return Rational.ZERO;
                if (ratio.isOne()) return base.getFirst();
                if (ratio.equals(Rational.NEGATIVE_ONE)) {
                    return index.testBit(0) ? base.getFirst().negate() : base.getFirst();
                }
            }

            Rational[] power = null;
            Rational[] remainder = null;
            for (int bit = 0; bit < index.bitLength(); bit++) {
                work.add(1);
                if (bit < powers.size()) {
                    power = powers.get(bit);
                } else {
                    power = bit == 0 ? firstPower(work) : multiplyModulo(power, power, relation, work);
                    if (bit < cacheLimit) powers.add(power);
                }
                if (index.testBit(bit)) {
                    remainder = remainder == null ? power : multiplyModulo(remainder, power, relation, work);
                }
            }
            var result = Rational.ZERO;
            for (int i = 0; i < order; i++) result = addProduct(result, remainder[i], base.get(i), work);
            return result;
        }

        private Rational[] firstPower(Work work) {
            var power = zeros(relation.size(), work);
            if (power.length == 1) power[0] = relation.getFirst();
            else power[1] = Rational.ONE;
            return power;
        }
    }

    private static int checkedDegree(Polynomial observation, int dimension, Work work) {
        var basis = RecurrenceInvariantSupport.basis(dimension, 2);
        work.add(1L + basis.size());
        if (observation.termCount() > basis.size()) throw new IllegalArgumentException("too many observation monomials");
        int degree = 0;
        for (var term : observation.terms().entrySet()) {
            work.add(1L + basis.size());
            // Check membership before summing exponents, even for malformed enormous powers.
            if (!basis.contains(term.getKey())) throw new IllegalArgumentException("observation outside the degree-two basis");
            RecurrenceInvariantSupport.requireBounded(term.getValue());
            degree = Math.max(degree, term.getKey().totalDegree());
        }
        return degree;
    }

    private static List<Rational> firstDependence(Transition transition, Polynomial observation, int degree, Work work) {
        if (degree == 0) return observation.isZero() ? List.of() : List.of(Rational.ONE);

        var basis = RecurrenceInvariantSupport.basis(transition.linear().columns(), degree);
        var positions = new HashMap<Monomial, Integer>();
        work.add(basis.size());
        for (int i = 0; i < basis.size(); i++) positions.put(basis.get(i), i);
        var images = RecurrenceInvariantSupport.liftedImages(transition, basis, work);
        var first = zeros(basis.size(), work);
        for (var term : observation.terms().entrySet()) {
            work.add(1);
            first[positions.get(term.getKey())] = term.getValue();
        }
        var columns = new ArrayList<Rational[]>();
        columns.add(first);
        for (int order = 1; order <= basis.size(); order++) {
            columns.add(compose(columns.getLast(), basis, positions, images, work));
            var dependence = dependence(columns, basis, work);
            if (dependence.isPresent()) return dependence.orElseThrow();
        }
        throw new IllegalStateException("bounded polynomial space must have a dependence");
    }

    private static Rational[] compose(Rational[] previous, List<Monomial> basis, Map<Monomial, Integer> positions,
            Map<Monomial, Polynomial> images, Work work) {
        var next = zeros(basis.size(), work);
        for (int col = 0; col < previous.length; col++) {
            work.add(1);
            if (previous[col].isZero()) continue;
            for (var term : images.get(basis.get(col)).terms().entrySet()) {
                int row = positions.get(term.getKey());
                next[row] = addProduct(next[row], previous[col], term.getValue(), work);
            }
        }
        return next;
    }

    private static Optional<List<Rational>> dependence(List<Rational[]> columns, List<Monomial> basis, Work work) {
        var rows = new ArrayList<List<Rational>>();
        var origins = new ArrayList<RowOrigin>();
        long minorBits = 0;
        for (int row = 0; row < basis.size(); row++) {
            work.add(1);
            var values = new ArrayList<Rational>();
            var denominator = BigInteger.ONE;
            boolean nonzero = false;
            for (var column : columns) {
                work.add(1);
                var value = column[row];
                values.add(value);
                nonzero |= !value.isZero();
                var divisor = value.denominator();
                if (!divisor.equals(BigInteger.ONE) && !divisor.equals(denominator)) {
                    work.add(3);
                    denominator = checked(denominator.divide(denominator.gcd(divisor)).multiply(divisor));
                }
            }
            if (!nonzero) continue;
            int rowBits = 0;
            for (int col = 0; col < values.size(); col++) {
                work.add(1);
                var value = values.get(col);
                if (!denominator.equals(BigInteger.ONE) && !value.isZero()) {
                    work.add(3);
                    var integer = checked(value.numerator().multiply(denominator.divide(value.denominator())));
                    value = new Rational(integer, BigInteger.ONE);
                    values.set(col, value);
                }
                rowBits = Math.max(rowBits, magnitudeBits(value.numerator()));
            }
            // A row of at most eleven integers has Euclidean norm below 2^(rowBits + 2).
            // After row scaling, pivoted Gauss-Jordan entries are ratios of integer minors.
            // Four minor sizes plus a carry also bound temporary products and sums.
            minorBits += rowBits + 2L;
            if (4 * minorBits + 1 > MAX_RATIONAL_BITS) throw new ArithmeticLimit();
            rows.add(values);
            origins.add(new RowOrigin(row, "coefficient of " + basis.get(row).key()));
        }

        int width = columns.size();
        var names = new ArrayList<String>(width);
        work.add(1L + width);
        for (int i = 0; i < width; i++) names.add("k" + i);
        var result = new ExactRrefSolver().solveHomogeneous(new ExactMatrix(rows), names, origins,
            new Budget(work.remaining()));
        work.add(result.work().consumedWorkUnits());
        if (result.status() == ExactRrefSolver.Status.BUDGET_INCONCLUSIVE) throw new RecurrenceInvariantSupport.Exhausted();
        var reduction = result.reduction().orElseThrow(() -> new IllegalStateException("Krylov reduction failed"));
        work.add(1L + 2L * rows.size() * width + reduction.rowOperations().size() + width);
        var nullspace = reduction.nullspaceBasis();
        if (nullspace.isEmpty()) return Optional.empty();
        if (nullspace.size() != 1 || !nullspace.getFirst().get(width - 1).isOne()) {
            throw new IllegalStateException("first dependence must have a unique monic last coefficient");
        }
        var relation = new ArrayList<Rational>(width - 1);
        for (int i = 0; i < width - 1; i++) {
            work.add(1);
            relation.add(checked(nullspace.getFirst().get(i).negate()));
        }
        return Optional.of(relation);
    }

    private static Rational[] multiplyModulo(Rational[] left, Rational[] right, List<Rational> relation, Work work) {
        int order = relation.size();
        var product = zeros(2 * order - 1, work);
        for (int i = 0; i < order; i++) {
            work.add(1);
            if (left[i].isZero()) continue;
            for (int j = 0; j < order; j++) product[i + j] = addProduct(product[i + j], left[i], right[j], work);
        }
        for (int degree = product.length - 1; degree >= order; degree--) {
            work.add(1);
            if (product[degree].isZero()) continue;
            for (int i = 0; i < order; i++) {
                int target = degree - order + i;
                product[target] = addProduct(product[target], product[degree], relation.get(i), work);
            }
        }
        work.add(order);
        return Arrays.copyOf(product, order);
    }

    private static Rational[] zeros(int size, Work work) {
        work.add(size);
        var values = new Rational[size];
        Arrays.fill(values, Rational.ZERO);
        return values;
    }

    private static void requireIndex(BigInteger index) {
        Objects.requireNonNull(index, "index");
        if (index.signum() < 0) throw new IllegalArgumentException("index must be nonnegative");
    }

    private static <T> Result<T> measured(Budget budget, Function<Work, T> action) {
        var work = new Work(Objects.requireNonNull(budget, "budget").maxWorkUnits());
        try {
            return new Result<>(Status.COMPLETE, Optional.of(action.apply(work)), work.ledger());
        } catch (RecurrenceInvariantSupport.Exhausted exhausted) {
            return new Result<>(Status.BUDGET_INCONCLUSIVE, Optional.empty(), work.ledger());
        } catch (ArithmeticLimit limit) {
            return new Result<>(Status.ARITHMETIC_LIMIT, Optional.empty(), work.ledger());
        }
    }
}
