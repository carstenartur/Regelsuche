package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.algebra.OperatorChecks.need;
import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.*;

/** Exact certification of linear shears on three disjoint, arbitrary-initial-value register blocks. */
public final class OccupiedWorkspace {
    private OccupiedWorkspace() {}
    public enum Register { X, Y, Z }
    public enum Sign { PLUS, MINUS }

    public record Shear(Register from, Register to, PolynomialMatrix map, Sign sign) {
        public Shear {
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
            Objects.requireNonNull(map);
            Objects.requireNonNull(sign);
        }
    }
    public record Schedule(PolynomialMatrix first, PolynomialMatrix second, List<Shear> steps) {
        public Schedule {
            Objects.requireNonNull(first);
            Objects.requireNonNull(second);
            steps = List.copyOf(steps);
        }
    }
    /** borrowedCells is the declared Z-register capacity, even if an alternative schedule never accesses Z. */
    public record Comparison(Cost scheduled, Cost direct, int borrowedCells) {}

    /** Immutable column vectors; equal Java objects in different slots still denote separate register values. */
    public record State(PolynomialMatrix x, PolynomialMatrix y, PolynomialMatrix z) {}

    public static Schedule composition(PolynomialMatrix first, PolynomialMatrix second) {
        return new Schedule(first, second, List.of(
            new Shear(Register.X, Register.Z, first, Sign.PLUS),
            new Shear(Register.Z, Register.Y, second, Sign.PLUS),
            new Shear(Register.X, Register.Z, first, Sign.MINUS),
            new Shear(Register.Z, Register.Y, second, Sign.MINUS)));
    }

    public static Result<Comparison> verify(PolynomialMatrix first, PolynomialMatrix second,
            Schedule schedule, IterationLimits limits, BooleanSupplier cancel) {
        return OperatorChecks.run(limits, cancel, checks -> verify(first, second, schedule, checks));
    }

    private static int dimension(Register register, PolynomialMatrix first, PolynomialMatrix second) {
        return switch (register) {
            case X -> first.columns();
            case Y -> second.rows();
            case Z -> first.rows();
        };
    }

    private static Comparison verify(PolynomialMatrix first, PolynomialMatrix second,
            Schedule schedule, OperatorChecks checks) {
        need(schedule != null, "MISSING_SCHEDULE");
        checks.input(first);
        checks.input(second);
        checks.input(schedule.first());
        checks.input(schedule.second());
        need(checks.algebra.equal(first, schedule.first()) && checks.algebra.equal(second, schedule.second()),
            "SOURCE_MISMATCH");
        need(first.rows() == second.columns(), "COMPOSITION_DIMENSION_MISMATCH");
        checks.work.cells(schedule.steps().size());
        var scheduled = new Cost(0, 0);
        for (var step : schedule.steps()) {
            checks.work.charge(1);
            need(step.from() != step.to(), "ALIASED_REGISTERS");
            checks.input(step.map());
            need(step.map().columns() == dimension(step.from(), first, second)
                && step.map().rows() == dimension(step.to(), first, second), "SHEAR_DIMENSION_MISMATCH");
            scheduled = scheduled.plus(Cost.dense(step.map())).updates(step.map().rows());
        }
        var product = checks.times(second, first);
        for (Register input : Register.values()) {
            int width = dimension(input, first, second);
            var blocks = new EnumMap<Register, PolynomialMatrix>(Register.class);
            for (Register r : Register.values()) {
                var block = input == r ? checks.unit(width)
                    : checks.matrix(dimension(r, first, second), width, (i, j) -> Polynomial.zero());
                blocks.put(r, block);
            }
            var expected = new EnumMap<>(blocks);
            if (input == Register.X) expected.put(Register.Y, product);
            replay(schedule, blocks, checks);
            for (Register r : Register.values())
                need(checks.algebra.equal(blocks.get(r), expected.get(r)), "REGISTER_MAP_MISMATCH");
        }
        var direct = Cost.dense(first).plus(Cost.dense(second)).updates(second.rows());
        return new Comparison(scheduled, direct, first.rows());
    }

    /** Verify the full map, then replay on actual occupied columns using immutable value semantics. */
    public static Result<State> apply(PolynomialMatrix first, PolynomialMatrix second, Schedule schedule,
            State initial, IterationLimits limits, BooleanSupplier cancel) {
        return OperatorChecks.run(limits, cancel, checks -> {
            verify(first, second, schedule, checks);
            need(initial != null, "MISSING_STATE");
            var blocks = new EnumMap<Register, PolynomialMatrix>(Register.class);
            blocks.put(Register.X, initial.x());
            blocks.put(Register.Y, initial.y());
            blocks.put(Register.Z, initial.z());
            for (Register r : Register.values()) {
                var column = blocks.get(r);
                checks.input(column);
                need(column.rows() == dimension(r, first, second) && column.columns() == 1, "STATE_DIMENSION_MISMATCH");
            }
            replay(schedule, blocks, checks);
            return new State(blocks.get(Register.X), blocks.get(Register.Y), blocks.get(Register.Z));
        });
    }

    private static void replay(Schedule schedule, EnumMap<Register, PolynomialMatrix> blocks, OperatorChecks checks) {
        for (var step : schedule.steps()) {
            var delta = checks.times(step.map(), blocks.get(step.from()));
            var old = blocks.get(step.to());
            var next = step.sign() == Sign.PLUS ? checks.plus(old, delta)
                : checks.matrix(old.rows(), old.columns(), (i, j) -> checks.algebra.add(old.get(i, j),
                    checks.algebra.multiply(OperatorChecks.MINUS_ONE, delta.get(i, j))));
            blocks.put(step.to(), next);
        }
    }
}
