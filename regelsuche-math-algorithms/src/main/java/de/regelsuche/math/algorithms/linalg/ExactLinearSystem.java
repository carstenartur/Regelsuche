package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Rational;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Exact symbolic representation of a scalar equation system as {@code A*x=b}.
 * Source row and variable identities are retained so the matrix view can be
 * replayed back to the original equations without losing occurrence meaning.
 */
public record ExactLinearSystem(
    ExactMatrix coefficients,
    List<String> variables,
    ExactVector rightHandSide,
    List<RowOrigin> rowOrigins,
    int coefficientRank,
    int augmentedRank,
    SolutionClassification solutionClassification
) {
    public ExactLinearSystem {
        coefficients = Objects.requireNonNull(coefficients, "coefficients");
        rightHandSide = Objects.requireNonNull(
            rightHandSide,
            "rightHandSide");
        rowOrigins = List.copyOf(Objects.requireNonNull(
            rowOrigins,
            "rowOrigins"));
        solutionClassification = Objects.requireNonNull(
            solutionClassification,
            "solutionClassification");

        Objects.requireNonNull(variables, "variables");
        List<String> normalizedVariables = new ArrayList<>(variables.size());
        Set<String> uniqueVariables = new HashSet<>();
        for (String variable : variables) {
            if (variable == null || variable.isBlank()) {
                throw new IllegalArgumentException(
                    "variable names must not be blank");
            }
            String normalized = variable.trim();
            if (!uniqueVariables.add(normalized)) {
                throw new IllegalArgumentException(
                    "variable names must be unique: " + normalized);
            }
            normalizedVariables.add(normalized);
        }
        if (normalizedVariables.isEmpty()) {
            throw new IllegalArgumentException(
                "an exact linear system needs at least one variable");
        }
        variables = List.copyOf(normalizedVariables);

        if (coefficients.columns() != variables.size()) {
            throw new IllegalArgumentException(
                "coefficient columns and variable count disagree");
        }
        if (coefficients.rowCount() != rightHandSide.dimension()
                || coefficients.rowCount() != rowOrigins.size()) {
            throw new IllegalArgumentException(
                "coefficient rows, RHS and row origins disagree");
        }

        int maximumCoefficientRank = Math.min(
            coefficients.rowCount(),
            coefficients.columns());
        int maximumAugmentedRank = Math.min(
            coefficients.rowCount(),
            coefficients.columns() + 1);
        if (coefficientRank < 0
                || coefficientRank > maximumCoefficientRank
                || augmentedRank < coefficientRank
                || augmentedRank > maximumAugmentedRank) {
            throw new IllegalArgumentException("invalid exact ranks");
        }

        SolutionClassification expected = augmentedRank > coefficientRank
            ? SolutionClassification.INCONSISTENT
            : coefficientRank == coefficients.columns()
                ? SolutionClassification.UNIQUE
                : SolutionClassification.UNDERDETERMINED;
        if (solutionClassification != expected) {
            throw new IllegalArgumentException(
                "solution classification disagrees with exact ranks");
        }
    }

    public int equationCount() {
        return coefficients.rowCount();
    }

    public int variableCount() {
        return coefficients.columns();
    }

    public DimensionShape dimensionShape() {
        if (equationCount() == variableCount()) {
            return DimensionShape.SQUARE;
        }
        return equationCount() < variableCount()
            ? DimensionShape.MORE_VARIABLES_THAN_EQUATIONS
            : DimensionShape.MORE_EQUATIONS_THAN_VARIABLES;
    }

    /** Number of rows not contributing to the augmented-system rank. */
    public int redundantEquationCount() {
        return equationCount() - augmentedRank;
    }

    public enum DimensionShape {
        SQUARE,
        MORE_VARIABLES_THAN_EQUATIONS,
        MORE_EQUATIONS_THAN_VARIABLES
    }

    public enum SolutionClassification {
        UNIQUE,
        UNDERDETERMINED,
        INCONSISTENT
    }

    public record ExactMatrix(List<List<Rational>> rows) {
        public ExactMatrix {
            Objects.requireNonNull(rows, "rows");
            if (rows.isEmpty()) {
                throw new IllegalArgumentException(
                    "matrix must contain at least one row");
            }
            List<List<Rational>> retained = new ArrayList<>(rows.size());
            int columns = -1;
            for (List<Rational> row : rows) {
                Objects.requireNonNull(row, "matrix row");
                if (columns < 0) {
                    columns = row.size();
                    if (columns == 0) {
                        throw new IllegalArgumentException(
                            "matrix must contain at least one column");
                    }
                } else if (row.size() != columns) {
                    throw new IllegalArgumentException(
                        "matrix rows must have equal length");
                }
                List<Rational> retainedRow = row.stream()
                    .map(value -> Objects.requireNonNull(
                        value,
                        "matrix entry"))
                    .toList();
                retained.add(retainedRow);
            }
            rows = List.copyOf(retained);
        }

        public int rowCount() {
            return rows.size();
        }

        public int columns() {
            return rows.getFirst().size();
        }

        public Rational get(int row, int column) {
            return rows.get(row).get(column);
        }
    }

    public record ExactVector(List<Rational> values) {
        public ExactVector {
            Objects.requireNonNull(values, "values");
            if (values.isEmpty()) {
                throw new IllegalArgumentException(
                    "vector must contain at least one entry");
            }
            values = values.stream()
                .map(value -> Objects.requireNonNull(value, "vector entry"))
                .toList();
        }

        public int dimension() {
            return values.size();
        }

        public Rational get(int index) {
            return values.get(index);
        }
    }

    public record RowOrigin(int sourceIndex, String sourceEquation) {
        public RowOrigin {
            if (sourceIndex < 0) {
                throw new IllegalArgumentException(
                    "sourceIndex must not be negative");
            }
            if (sourceEquation == null || sourceEquation.isBlank()) {
                throw new IllegalArgumentException(
                    "sourceEquation must not be blank");
            }
            sourceEquation = sourceEquation.trim();
        }
    }
}
