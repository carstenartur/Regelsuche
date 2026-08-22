package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * A system that is linear in an explicitly declared vector of unknowns while
 * permitting exact polynomial expressions in the remaining scalar parameters.
 *
 * <p>This is deliberately different from {@link ExactLinearSystem}: the latter
 * has rational coefficients and can compute exact ranks immediately, while this
 * value retains symbolic scalar coefficients such as {@code a-lambda}. The
 * explicit unknown order prevents variables from being guessed by name.</p>
 */
public record SymbolicLinearSystem(
    PolynomialMatrix coefficients,
    List<String> unknowns,
    PolynomialVector rightHandSide,
    List<String> scalarParameters,
    List<RowOrigin> rowOrigins
) {
    public SymbolicLinearSystem {
        coefficients = Objects.requireNonNull(coefficients, "coefficients");
        rightHandSide = Objects.requireNonNull(
            rightHandSide,
            "rightHandSide");
        unknowns = distinctNames(unknowns, "unknowns", false);
        scalarParameters = distinctNames(
            scalarParameters,
            "scalarParameters",
            true);
        rowOrigins = List.copyOf(Objects.requireNonNull(
            rowOrigins,
            "rowOrigins"));
        if (coefficients.columns() != unknowns.size()) {
            throw new IllegalArgumentException(
                "coefficient columns and declared unknowns disagree");
        }
        if (coefficients.rows() != rightHandSide.dimension()
                || coefficients.rows() != rowOrigins.size()) {
            throw new IllegalArgumentException(
                "coefficient rows, RHS and row origins disagree");
        }
        Set<String> overlap = new HashSet<>(unknowns);
        overlap.retainAll(scalarParameters);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                "unknowns and scalar parameters must be disjoint: "
                    + overlap);
        }
        Set<String> actualParameters = new TreeSet<>();
        coefficients.entries().forEach(row -> row.forEach(polynomial ->
            actualParameters.addAll(polynomial.variables())));
        rightHandSide.values().forEach(polynomial ->
            actualParameters.addAll(polynomial.variables()));
        if (!actualParameters.equals(new TreeSet<>(scalarParameters))) {
            throw new IllegalArgumentException(
                "scalar parameter list does not match symbolic coefficients: "
                    + actualParameters);
        }
    }

    public int equationCount() {
        return coefficients.rows();
    }

    public int unknownCount() {
        return coefficients.columns();
    }

    public boolean homogeneous() {
        return rightHandSide.values().stream().allMatch(Polynomial::isZero);
    }

    private static List<String> distinctNames(
        List<String> names,
        String field,
        boolean allowEmpty
    ) {
        Objects.requireNonNull(names, field);
        if (!allowEmpty && names.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        Set<String> retained = new TreeSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(
                    field + " entries must not be blank");
            }
            if (!retained.add(name.trim())) {
                throw new IllegalArgumentException(
                    field + " entries must be unique");
            }
        }
        return List.copyOf(retained);
    }

    public record PolynomialMatrix(List<List<Polynomial>> entries) {
        public PolynomialMatrix {
            Objects.requireNonNull(entries, "entries");
            if (entries.isEmpty()) {
                throw new IllegalArgumentException(
                    "matrix must contain at least one row");
            }
            List<List<Polynomial>> retained = new ArrayList<>(entries.size());
            int columns = -1;
            for (List<Polynomial> row : entries) {
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
                retained.add(row.stream()
                    .map(value -> Objects.requireNonNull(
                        value,
                        "matrix entry"))
                    .toList());
            }
            entries = List.copyOf(retained);
        }

        public int rows() {
            return entries.size();
        }

        public int columns() {
            return entries.getFirst().size();
        }

        public Polynomial get(int row, int column) {
            return entries.get(row).get(column);
        }
    }

    public record PolynomialVector(List<Polynomial> values) {
        public PolynomialVector {
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

        public Polynomial get(int index) {
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
