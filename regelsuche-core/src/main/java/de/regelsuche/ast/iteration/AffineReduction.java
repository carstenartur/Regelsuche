package de.regelsuche.ast.iteration;

import java.util.List;
import java.util.Objects;

/**
 * A scalar reduction over integer affine domains, reading one element of a
 * zero-based one-dimensional input at each reduction point.
 */
public record AffineReduction(Operator operator, List<Form> parameters, List<Form> inputs,
        int outputRank, List<Form> outputs, int reductionRank, List<Form> reductions, int inputIndex) {
    private static final int MAX_CONSTRAINTS = 12;
    private static final int MAX_COEFFICIENT = 2_048;

    public enum Operator { SUM, MAX }

    /** A term in an inequality of the form value >= 0. */
    public record Form(int constant, int parameter, List<Integer> coordinates) {
        public Form {
            if (coordinates.size() > 4 || !bounded(constant) || !bounded(parameter)
                    || !coordinates.stream().allMatch(Form::bounded)) {
                throw new IllegalArgumentException("AFFINE_FORM_LIMIT");
            }
            coordinates = List.copyOf(coordinates);
        }

        private static boolean bounded(int value) {
            return value >= -MAX_COEFFICIENT && value <= MAX_COEFFICIENT;
        }

        public long evaluate(int size, int... axes) {
            if (axes.length != coordinates.size()) {
                throw new IllegalArgumentException("AFFINE_RANK_MISMATCH");
            }
            long value = constant + (long) parameter * size;
            for (int axis = 0; axis < axes.length; axis++) {
                value += (long) coordinates.get(axis) * axes[axis];
            }
            return value;
        }
    }

    public AffineReduction {
        Objects.requireNonNull(operator);
        if (outputRank < 1 || outputRank > 2 || reductionRank < 1 || reductionRank > 2
                || inputIndex < 0 || inputIndex >= reductionRank) {
            throw new IllegalArgumentException("AFFINE_REDUCTION_RANK");
        }
        parameters = domain(parameters, 0);
        inputs = domain(inputs, 1);
        outputs = domain(outputs, outputRank);
        // The reduction domain lists output axes followed by reduction binders.
        reductions = domain(reductions, outputRank + reductionRank);
    }

    private static List<Form> domain(List<Form> forms, int rank) {
        if (forms.size() > MAX_CONSTRAINTS
                || forms.stream().anyMatch(form -> form.coordinates().size() != rank)) {
            throw new IllegalArgumentException("AFFINE_DOMAIN_LIMIT");
        }
        return List.copyOf(forms);
    }
}
