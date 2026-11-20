package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.modular.MontgomerySchedule.*;

/** Finite proposals for dot products; every proposal passes the separate schedule checker. */
public final class MontgomeryReductionPlanner {
    private static final int MAX_PRODUCTS = 64;
    private MontgomeryReductionPlanner() {}

    public record Product(int left, int right) {}
    public record QuadraticProduct(int left0, int left1, int right0, int right1) {}
    public enum QuadraticStrategy { THREE_REDUCTIONS, KARATSUBA_FOUR_REDUCTIONS }

    /** Proposes M(sum a*b) for sum M(a*b). The original additions use the explicitly supplied sum word. */
    public static Result dotProduct(Parameters p, List<Input> inputs, List<Product> products, Word sourceSumWord,
            Observation observation, IterationLimits limits, BooleanSupplier cancellation) {
        return plan(p, inputs, products.size(), observation, limits, cancellation, () -> {
            var source = new Builder(p, inputs.size());
            var target = new Builder(p, inputs.size());
            int sourceSum = -1, targetSum = -1;
            for (var product : products) {
                input(product.left(), inputs);
                input(product.right(), inputs);
                int reduced = source.reduce(source.multiply(product.left(), product.right()));
                sourceSum = source.accumulate(sourceSum, reduced, sourceSumWord);
                targetSum = target.accumulate(targetSum, target.multiply(product.left(), product.right()), p.accumulatorWord());
            }
            return new Request(p, inputs, source.build(sourceSum), target.build(target.reduce(targetSum)), observation);
        });
    }

    /**
     * Degree-two extension products with the same zeta for all terms:
     * r0=sum(M(a0*b0)+M(zeta*M(a1*b1))), r1=sum(M(a0*b1)+M(a1*b0)).
     * Outputs and each original block are stored in the residue word; all casts must be exact.
     * This models a Montgomery-scaled extension coefficient zeta/R, not an ordinary zeta.
     */
    public static Result quadraticDotProduct(Parameters p, List<Input> inputs, List<QuadraticProduct> products,
            int zeta, QuadraticStrategy strategy, Observation observation, IterationLimits limits,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(strategy);
        return plan(p, inputs, products.size(), observation, limits, cancellation, () -> {
            input(zeta, inputs);
            var source = new Builder(p, inputs.size());
            var target = new Builder(p, inputs.size());
            int s0 = -1, s1 = -1, a00 = -1, a11 = -1, across = -1;
            for (var term : products) {
                input(term.left0(), inputs); input(term.left1(), inputs);
                input(term.right0(), inputs); input(term.right1(), inputs);
                int diagonal = source.reduce(source.multiply(term.left1(), term.right1()));
                int r0 = source.add(source.reduce(source.multiply(zeta, diagonal)),
                        source.reduce(source.multiply(term.left0(), term.right0())));
                int r1 = source.add(source.reduce(source.multiply(term.left0(), term.right1())),
                        source.reduce(source.multiply(term.left1(), term.right0())));
                s0 = source.cast(source.accumulate(s0, source.cast(r0), p.accumulatorWord()));
                s1 = source.cast(source.accumulate(s1, source.cast(r1), p.accumulatorWord()));
                a00 = target.accumulate(a00, target.multiply(term.left0(), term.right0()), p.accumulatorWord());
                a11 = target.accumulate(a11, target.multiply(term.left1(), term.right1()), p.accumulatorWord());
                int cross = strategy == QuadraticStrategy.THREE_REDUCTIONS
                        ? target.add(target.multiply(term.left0(), term.right1()), target.multiply(term.left1(), term.right0()))
                        : target.multiply(target.add(term.left0(), term.left1()), target.add(term.right0(), term.right1()));
                across = target.accumulate(across, cross, p.accumulatorWord());
            }
            int r0, r1;
            int v = target.reduce(a11);
            if (strategy == QuadraticStrategy.THREE_REDUCTIONS) {
                r0 = target.reduce(target.add(a00, target.multiply(zeta, v)));
                r1 = target.reduce(across);
            } else {
                int u = target.reduce(a00);
                r0 = target.add(u, target.reduce(target.multiply(zeta, v)));
                r1 = target.subtract(target.subtract(target.reduce(across), u), v);
            }
            return new Request(p, inputs, source.build(s0, s1), target.build(target.cast(r0), target.cast(r1)), observation);
        });
    }

    private static Result plan(Parameters parameters, List<Input> inputs, int productCount, Observation observation,
            IterationLimits limits, BooleanSupplier cancellation, java.util.function.Supplier<Request> proposal) {
        var work = new Work(limits, cancellation);
        try {
            work.charge(1);
            if (productCount < 1 || productCount > MAX_PRODUCTS) throw new Unavailable("PRODUCT_COUNT_LIMIT");
            int inputCount = inputs.size();
            work.size(inputCount);
            if (inputCount > MAX_INPUTS) throw new Unavailable("INPUT_SIZE_LIMIT");
            Objects.requireNonNull(parameters);
            Objects.requireNonNull(observation);
            // Charge a conservative bound for constructing both finite schedules before allocation.
            work.charge(64L * productCount + inputCount);
            var result = certify(proposal.get(), work);
            work.charge(0);
            return result;
        } catch (Unavailable unavailable) {
            return new Result(Optional.empty(), unavailable.getMessage(), work.used);
        }
    }

    private static void input(int reference, List<Input> inputs) {
        if (reference < 0 || reference >= inputs.size()) throw new Unavailable("INVALID_INPUT_REFERENCE");
    }

    private static final class Builder {
        private final Parameters parameters;
        private final int inputCount;
        private final List<Step> steps = new ArrayList<>();
        Builder(Parameters parameters, int inputCount) { this.parameters = parameters; this.inputCount = inputCount; }
        int apply(Operation operation, Word word, int... operands) {
            int reference = inputCount + steps.size();
            steps.add(Step.apply(operation, word, operands));
            return reference;
        }
        int multiply(int left, int right) { return apply(Operation.MULTIPLY, parameters.accumulatorWord(), left, right); }
        int add(int left, int right) { return apply(Operation.ADD, parameters.accumulatorWord(), left, right); }
        int subtract(int left, int right) { return apply(Operation.SUBTRACT, parameters.accumulatorWord(), left, right); }
        int reduce(int value) { return apply(Operation.MONTGOMERY, parameters.residueWord(), value); }
        int cast(int value) { return apply(Operation.CAST, parameters.residueWord(), value); }
        int accumulate(int sum, int value, Word word) { return sum < 0 ? value : apply(Operation.ADD, word, sum, value); }
        Schedule build(int... outputs) {
            var references = new ArrayList<Integer>();
            for (int output : outputs) references.add(output);
            return new Schedule(steps, references);
        }
    }
}
