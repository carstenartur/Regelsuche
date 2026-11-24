package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.AffineReduction;
import de.regelsuche.ast.iteration.AffineReduction.Form;
import de.regelsuche.ast.iteration.AffineReduction.Operator;
import de.regelsuche.math.algorithms.iteration.AffineDomains.Clause;
import de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Exact range aggregation plans for bounded affine integer array reductions. */
public final class AffineReductionPlan {
    private AffineReductionPlan() {}

    public record Shape(int inputSize, List<List<Integer>> outputCoordinates) {
        public Shape {
            outputCoordinates = outputCoordinates.stream().map(List::copyOf).toList();
        }
    }

    public record Preparation(Verified plan, long work) {}

    public record ShapePreparation(Shape shape, long work) {}

    public record Evaluation(Shape shape, List<BigInteger> values, long work) {
        public Evaluation {
            values = List.copyOf(values);
        }
    }

    /** A plan can only be created by checking the source's affine constraints. */
    public static final class Verified {
        private final AffineReduction source;
        private final List<Form> accessBounds;
        private final Form eliminatedLower;
        private final Form eliminatedUpper;

        private Verified(AffineReduction source, List<Form> accessBounds, Form eliminatedLower, Form eliminatedUpper) {
            this.source = source;
            this.accessBounds = List.copyOf(accessBounds);
            this.eliminatedLower = eliminatedLower;
            this.eliminatedUpper = eliminatedUpper;
        }

        public Evaluation evaluate(int parameter, List<BigInteger> input, IterationLimits limits, BooleanSupplier cancellation) {
            var work = new Work(limits, cancellation, 1);
            try {
                var shape = shape(source, parameter, work);
                var data = checkedInput(input, shape, work);
                if (shape.outputCoordinates().isEmpty()) return new Evaluation(shape, List.of(), work.used());
                var windows = new ArrayList<Window>();
                for (var position : shape.outputCoordinates()) windows.add(window(position, parameter, shape.inputSize(), work));
                var values = source.operator() == Operator.SUM
                    ? AffineRangeAggregates.sums(data, windows, eliminatedLower != null, work)
                    : AffineRangeAggregates.maxima(data, windows, work);
                return new Evaluation(shape, values, work.used());
            } catch (Unavailable failure) {
                throw new IllegalArgumentException(failure.getMessage(), failure);
            }
        }

        private Window window(List<Integer> position, int parameter, int inputSize, Work work) {
            int selectedAxis = source.outputRank() + source.inputIndex();
            var bounds = new ArrayList<Clause>();
            for (var form : accessBounds) bounds.add(prepareClause(form, position, parameter, selectedAxis, work));
            long weightConstant = 1;
            long weightSlope = 0;
            if (eliminatedLower != null) {
                var lower = prepareClause(eliminatedLower, position, parameter, selectedAxis, work);
                var upper = prepareClause(eliminatedUpper, position, parameter, selectedAxis, work);
                // Exactly upper - lower + 1 integer secondary binders exist when the count is positive.
                weightConstant += upper.constant() + lower.constant();
                weightSlope = upper.first() + lower.first();
                bounds.add(new Clause(weightConstant - 1, weightSlope, 0));
            }
            var range = AffineDomains.firstRange(bounds, work);
            requireReadableRange(range, inputSize);
            return new Window(range.lower(), range.upper(), weightConstant, weightSlope);
        }
    }

    record Window(int lower, int upper, long weightConstant, long weightSlope) {}

    public static Preparation prepare(AffineReduction source, IterationLimits limits, BooleanSupplier cancellation) {
        var work = new Work(limits, cancellation, 1);
        try {
            work.charge(1);
            if (source.reductions().size() > limits.maximumTerms())
                throw new IllegalArgumentException("AFFINE_CONSTRAINT_LIMIT");
            var bounds = new ArrayList<Form>();
            Form lower = null;
            Form upper = null;
            for (var form : source.reductions()) {
                work.charge(1);
                switch (eliminatedCoefficient(source, form)) {
                    case 0 -> bounds.add(form);
                    case 1 -> lower = uniqueBound(lower, form);
                    case -1 -> upper = uniqueBound(upper, form);
                    default -> throw new IllegalArgumentException("NON_UNIT_ELIMINATION_BOUND");
                }
            }
            if (source.reductionRank() == 2 && (source.operator() != Operator.SUM || lower == null || upper == null))
                throw new IllegalArgumentException("UNSUPPORTED_AFFINE_ELIMINATION");
            return new Preparation(new Verified(source, bounds, lower, upper), work.used());
        } catch (Unavailable failure) {
            throw new IllegalArgumentException(failure.getMessage(), failure);
        }
    }

    private static int eliminatedCoefficient(AffineReduction source, Form form) {
        return source.reductionRank() == 1 ? 0
            : form.coordinates().get(source.outputRank() + (1 - source.inputIndex()));
    }

    private static Form uniqueBound(Form old, Form next) {
        if (old != null) throw new IllegalArgumentException("COMPETING_ELIMINATION_BOUNDS");
        return next;
    }

    public static ShapePreparation prepareShape(AffineReduction source, int parameter, IterationLimits limits,
            BooleanSupplier cancellation) {
        var work = new Work(limits, cancellation, 1);
        try {
            return new ShapePreparation(shape(source, parameter, work), work.used());
        } catch (Unavailable failure) {
            throw new IllegalArgumentException(failure.getMessage(), failure);
        }
    }

    static Shape shape(AffineReduction source, int parameter, Work work) {
        if (Math.abs((long) parameter) > AffineDomains.MAX_COORDINATE)
            throw new IllegalArgumentException("AFFINE_PARAMETER_LIMIT");
        for (var form : source.parameters()) {
            work.charge(1);
            if (form.evaluate(parameter) < 0) throw new IllegalArgumentException("OUTSIDE_AFFINE_PARAMETER_DOMAIN");
        }
        var input = AffineDomains.firstRange(AffineDomains.instantiate(source.inputs(), 1, parameter), work);
        if (input.lower() != 0 || input.isEmpty()) throw new IllegalArgumentException("NON_ZERO_BASED_AFFINE_INPUT");
        var domain = AffineDomains.instantiate(source.outputs(), source.outputRank(), parameter);
        var first = AffineDomains.firstRange(domain, work);
        var coordinates = new ArrayList<List<Integer>>();
        for (int i = first.lower(); i <= first.upper(); i++) {
            work.charge(1);
            if (source.outputRank() == 1) append(coordinates, List.of(i), work);
            else {
                var second = AffineDomains.secondRange(domain, i, work);
                for (int j = second.lower(); j <= second.upper(); j++)
                    append(coordinates, List.of(i, j), work);
            }
        }
        return new Shape(input.upper() + 1, coordinates);
    }

    private static void append(List<List<Integer>> coordinates, List<Integer> point, Work work) {
        work.charge(1);
        if (coordinates.size() == AffineDomains.MAX_COORDINATE + 1)
            throw new IllegalArgumentException("AFFINE_OUTPUT_SIZE_LIMIT");
        coordinates.add(point);
    }

    static List<BigInteger> checkedInput(List<BigInteger> input, Shape shape, Work work) {
        int size = shape.inputSize();
        if (input.size() != size) throw new IllegalArgumentException("AFFINE_INPUT_LENGTH");
        var data = new ArrayList<BigInteger>();
        var values = input.iterator();
        for (int index = 0; index < size; index++) {
            work.charge(1);
            var value = Objects.requireNonNull(values.next());
            work.runtimeInteger(value, 0); // The fixed read unit was paid before accessing the element.
            data.add(value);
        }
        return data;
    }

    private static Clause prepareClause(Form form, List<Integer> position, int parameter, int index, Work work) {
        work.charge(1L + position.size());
        long constant = form.constant() + (long) form.parameter() * parameter;
        for (int axis = 0; axis < position.size(); axis++)
            constant += (long) form.coordinates().get(axis) * position.get(axis);
        return new Clause(constant, form.coordinates().get(index), 0);
    }

    static void requireReadableRange(AffineDomains.Range range, int inputSize) {
        if (range.isEmpty()) throw new IllegalArgumentException("UNDEFINED_EMPTY_AFFINE_REDUCTION");
        if (range.lower() < 0 || range.upper() >= inputSize)
            throw new IllegalArgumentException("AFFINE_ACCESS_OUTSIDE_INPUT");
    }
}
