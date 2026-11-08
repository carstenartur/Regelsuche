package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.FieldInversionBatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * A straight-line schedule with one output for each inversion operand.
 * Slots start with the source operands in order, followed by step results.
 */
public record FieldBatchInversionPlan(List<Step> steps, List<Integer> outputs) {
    public FieldBatchInversionPlan {
        if (steps.size() > maximumSteps(FieldInversionBatch.MAX_OPERANDS)
                || outputs.size() > FieldInversionBatch.MAX_OPERANDS) {
            throw new IllegalArgumentException("FIELD_BATCH_PLAN_LIMIT");
        }
        steps = List.copyOf(steps);
        outputs = List.copyOf(outputs);
    }

    public sealed interface Step permits Multiply, Invert {}

    public record Multiply(int left, int right) implements Step {}

    public record Invert(int operand) implements Step {}

    private static int maximumSteps(int operands) {
        return 3 * (operands - 1) + 1;
    }

    static Optional<FieldBatchInversionPlan> derive(FieldInversionBatch source, LongConsumer charge) {
        int operands = source.operands();
        if (operands < 2) return Optional.empty();
        var steps = new ArrayList<Step>(maximumSteps(operands));
        var prefixes = new int[operands];
        for (int i = 1; i < operands; i++) {
            charge.accept(1);
            prefixes[i] = operands + steps.size();
            steps.add(new Multiply(prefixes[i - 1], i));
        }
        charge.accept(1);
        int inverse = operands + steps.size();
        steps.add(new Invert(prefixes[operands - 1]));
        var outputs = new Integer[operands];
        for (int i = operands - 1; i > 0; i--) {
            charge.accept(2);
            outputs[i] = operands + steps.size();
            steps.add(new Multiply(prefixes[i - 1], inverse));
            int previous = operands + steps.size();
            steps.add(new Multiply(inverse, i));
            inverse = previous;
        }
        outputs[0] = inverse;
        return Optional.of(new FieldBatchInversionPlan(steps, List.of(outputs)));
    }

    /**
     * Replay the candidate as products of symbolic powers. Each operand starts
     * with exponent +1 in its own slot. Verify that each output ends with
     * exponent -1 in that slot and zero in every other slot.
     */
    static Optional<Verified> check(FieldInversionBatch source, FieldBatchInversionPlan plan, LongConsumer charge) {
        int operands = source.operands();
        if (operands < 2 || plan.outputs().size() != operands || plan.steps().size() > maximumSteps(operands)) {
            return Optional.empty();
        }
        var powers = new ArrayList<Powers>(operands + plan.steps().size());
        for (int i = 0; i < operands; i++) {
            charge.accept(1);
            powers.add(new Powers(1L << i, 0));
        }
        boolean inverted = false;
        for (var step : plan.steps()) {
            charge.accept(1);
            if (step instanceof Multiply multiply) {
                if (!slot(multiply.left(), powers.size()) || !slot(multiply.right(), powers.size())) return Optional.empty();
                var product = powers.get(multiply.left()).multiply(powers.get(multiply.right()));
                if (product == null) return Optional.empty();
                powers.add(product);
            } else if (step instanceof Invert invert) {
                if (inverted || !slot(invert.operand(), powers.size())) return Optional.empty();
                powers.add(powers.get(invert.operand()).inverse());
                inverted = true;
            }
        }
        if (!inverted) return Optional.empty();
        for (int i = 0; i < operands; i++) {
            charge.accept(1);
            int output = plan.outputs().get(i);
            if (!slot(output, powers.size()) || !powers.get(output).equals(new Powers(0, 1L << i))) return Optional.empty();
        }
        return Optional.of(new Verified(source, plan));
    }

    private static boolean slot(int index, int size) {
        return index >= 0 && index < size;
    }

    /** Disjoint masks encode exponents in {-1, 0, +1}. */
    private record Powers(long positive, long negative) {
        Powers multiply(Powers other) {
            if ((positive & other.positive) != 0 || (negative & other.negative) != 0) return null;
            long cancelled = (positive & other.negative) | (negative & other.positive);
            return new Powers((positive | other.positive) & ~cancelled, (negative | other.negative) & ~cancelled);
        }

        Powers inverse() {
            return new Powers(negative, positive);
        }
    }

    public static final class Verified {
        private final FieldInversionBatch source;
        private final FieldBatchInversionPlan plan;

        private Verified(FieldInversionBatch source, FieldBatchInversionPlan plan) {
            this.source = source;
            this.plan = plan;
        }

        public FieldInversionBatch source() { return source; }
        public FieldBatchInversionPlan plan() { return plan; }
        public int originalInversions() { return source.operands(); }
        public int plannedInversions() { return 1; }
        public int plannedMultiplications() { return plan.steps().size() - plannedInversions(); }
    }
}
