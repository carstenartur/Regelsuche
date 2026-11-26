package de.regelsuche.math.algorithms.modular;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Field-valued SSA. Registers 0, 1, 2 are a, b, g. Each arm extends the common prefix independently. */
public record QuadraticRootProgram(List<Step> prefix, int guardLeft, int guardRight, Arm equal, Arm unequal) {
    static final int INPUTS = 3;
    static final int MAX_BLOCK = 128;

    public QuadraticRootProgram {
        prefix = block(prefix);
        Objects.requireNonNull(equal);
        Objects.requireNonNull(unequal);
    }

    public enum Op { CONSTANT, ADD, SUBTRACT, MULTIPLY, SQUARE, NEGATE, POWER, INVERSE }

    /** Unused references are -1 and an unused literal is zero. */
    public record Step(Op op, int left, int right, BigInteger literal) {
        public Step {
            Objects.requireNonNull(op);
            SharedPowerPlan.bounded(literal);
            boolean binary = op == Op.ADD || op == Op.SUBTRACT || op == Op.MULTIPLY;
            if ((op == Op.CONSTANT ? left != -1 : left < 0) || (binary ? right < 0 : right != -1)
                    || (op == Op.POWER || op == Op.CONSTANT ? literal.signum() < 0 : literal.signum() != 0)) {
                throw new IllegalArgumentException("INVALID_FIELD_STEP");
            }
        }
        public static Step constant(BigInteger value) { return new Step(Op.CONSTANT, -1, -1, value); }
        public static Step add(int a, int b) { return new Step(Op.ADD, a, b, BigInteger.ZERO); }
        public static Step subtract(int a, int b) { return new Step(Op.SUBTRACT, a, b, BigInteger.ZERO); }
        public static Step multiply(int a, int b) { return new Step(Op.MULTIPLY, a, b, BigInteger.ZERO); }
        public static Step square(int a) { return new Step(Op.SQUARE, a, -1, BigInteger.ZERO); }
        public static Step negate(int a) { return new Step(Op.NEGATE, a, -1, BigInteger.ZERO); }
        public static Step power(int a, BigInteger exponent) { return new Step(Op.POWER, a, -1, exponent); }
        public static Step inverse(int a) { return new Step(Op.INVERSE, a, -1, BigInteger.ZERO); }
    }

    /** The optional exit operation negates both outputs iff the nonzero real part has character -1. */
    public record Arm(List<Step> steps, int real, int imaginary, boolean normalizeRealCharacter) {
        public Arm { steps = block(steps); }
    }

    /** Structural counts for the common prefix followed by the selected arm, with one equality comparison. */
    public record PathCost(int powerCalls, int multiplications, int squarings, int inversions, int additions,
            int subtractions, int negations, int characterSelections, long weightedGroupWork) {}

    /** Binary-chain group-work estimate. Additive operations and character selections are counted separately. */
    public PathCost cost(boolean equalityHolds, SharedPowerPlan.Weights weights) {
        var arm = equalityHolds ? equal : unequal;
        int[] counts = new int[Op.values().length];
        long weighted = 0;
        for (var block : List.of(prefix, arm.steps())) for (var step : block) {
            counts[step.op().ordinal()]++;
            weighted += switch (step.op()) {
                case POWER -> SharedPowerPlan.powerCost(step.literal(), weights);
                case MULTIPLY -> weights.multiplication();
                case SQUARE -> weights.squaring();
                case INVERSE -> weights.inverse();
                default -> 0;
            };
        }
        return new PathCost(counts[Op.POWER.ordinal()], counts[Op.MULTIPLY.ordinal()], counts[Op.SQUARE.ordinal()],
                counts[Op.INVERSE.ordinal()], counts[Op.ADD.ordinal()], counts[Op.SUBTRACT.ordinal()],
                counts[Op.NEGATE.ordinal()], arm.normalizeRealCharacter() ? 1 : 0, weighted);
    }

    /**
     * Default structural estimate for one traversal of each path. Includes binary-chain group work,
     * one unit per power call, guard, addition, subtraction or negation, and eight per character
     * selection. Literal references carry no group work. Consumers can price each path separately.
     */
    public long score(SharedPowerPlan.Weights weights) {
        return score(cost(true, weights)) + score(cost(false, weights));
    }

    private static long score(PathCost path) {
        return path.weightedGroupWork() + path.powerCalls() + 1L + path.additions() + path.subtractions()
                + path.negations() + 8L * path.characterSelections();
    }

    private static List<Step> block(List<Step> steps) {
        var copy = new ArrayList<Step>();
        for (var step : steps) {
            if (copy.size() == MAX_BLOCK) throw new IllegalArgumentException("FIELD_BLOCK_LIMIT");
            copy.add(Objects.requireNonNull(step));
        }
        return List.copyOf(copy);
    }
}
