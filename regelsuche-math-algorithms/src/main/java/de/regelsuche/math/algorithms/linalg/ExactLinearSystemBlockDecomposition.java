package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.RowOrigin;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic partition of equation rows and variable columns into independent
 * connected components of the exact coefficient matrix.
 *
 * <p>Every source row and source column occurs in exactly one component. A
 * component with rows and columns is a coupled subsystem; a column-only
 * component is a free coordinate; a row-only component is a constant
 * constraint. Cross-component coefficients are certified zero by the producer.</p>
 */
public record ExactLinearSystemBlockDecomposition(
    int sourceRowCount,
    int sourceColumnCount,
    List<Component> components,
    List<Integer> rowPermutation,
    List<Integer> columnPermutation,
    List<String> unlockedCapabilities
) {
    public static final String CAPABILITY_INDEPENDENT_SUBSYSTEMS =
        "INDEPENDENT_LINEAR_SUBSYSTEMS";
    public static final String CAPABILITY_FREE_VARIABLE_COMPONENTS =
        "FREE_VARIABLE_COMPONENTS";
    public static final String CAPABILITY_CONSTANT_CONSTRAINT_COMPONENTS =
        "CONSTANT_CONSTRAINT_COMPONENTS";
    public static final String CAPABILITY_INCONSISTENCY_LOCALIZATION =
        "INCONSISTENCY_LOCALIZATION";

    public ExactLinearSystemBlockDecomposition {
        if (sourceRowCount < 1 || sourceColumnCount < 1) {
            throw new IllegalArgumentException(
                "source dimensions must be positive");
        }
        components = List.copyOf(Objects.requireNonNull(
            components,
            "components"));
        rowPermutation = indexPermutation(
            rowPermutation,
            sourceRowCount,
            "rowPermutation");
        columnPermutation = indexPermutation(
            columnPermutation,
            sourceColumnCount,
            "columnPermutation");
        unlockedCapabilities = textSet(
            unlockedCapabilities,
            "unlockedCapabilities");
        if (components.size() < 2) {
            throw new IllegalArgumentException(
                "a decomposition requires at least two components");
        }

        boolean[] seenRows = new boolean[sourceRowCount];
        boolean[] seenColumns = new boolean[sourceColumnCount];
        for (Component component : components) {
            Objects.requireNonNull(component, "component");
            for (int row : component.sourceRowIndices()) {
                if (row < 0 || row >= sourceRowCount || seenRows[row]) {
                    throw new IllegalArgumentException(
                        "component row partition is invalid: " + row);
                }
                seenRows[row] = true;
            }
            for (int column : component.sourceColumnIndices()) {
                if (column < 0
                        || column >= sourceColumnCount
                        || seenColumns[column]) {
                    throw new IllegalArgumentException(
                        "component column partition is invalid: " + column);
                }
                seenColumns[column] = true;
            }
        }
        requireComplete(seenRows, "row");
        requireComplete(seenColumns, "column");
        if (!unlockedCapabilities.contains(
                CAPABILITY_INDEPENDENT_SUBSYSTEMS)) {
            throw new IllegalArgumentException(
                "decomposition must unlock independent subsystems");
        }
    }

    public boolean hasFreeVariableComponents() {
        return components.stream()
            .anyMatch(component -> component.kind()
                == ComponentKind.FREE_VARIABLES);
    }

    public boolean hasConstantConstraintComponents() {
        return components.stream()
            .anyMatch(component -> component.kind()
                == ComponentKind.CONSTANT_CONSTRAINTS);
    }

    public boolean hasContradictoryConstantConstraint() {
        return components.stream()
            .anyMatch(Component::contradictoryConstantConstraint);
    }

    private static List<Integer> indexPermutation(
        List<Integer> values,
        int size,
        String field
    ) {
        Objects.requireNonNull(values, field);
        if (values.size() != size) {
            throw new IllegalArgumentException(
                field + " must contain every index exactly once");
        }
        boolean[] seen = new boolean[size];
        List<Integer> retained = new ArrayList<>(size);
        for (Integer value : values) {
            if (value == null || value < 0 || value >= size || seen[value]) {
                throw new IllegalArgumentException(
                    field + " contains an invalid index: " + value);
            }
            seen[value] = true;
            retained.add(value);
        }
        return List.copyOf(retained);
    }

    private static List<String> textSet(
        List<String> values,
        String field
    ) {
        Objects.requireNonNull(values, field);
        List<String> retained = new ArrayList<>(values.size());
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                    field + " entries must not be blank");
            }
            String normalized = value.trim();
            if (!unique.add(normalized)) {
                throw new IllegalArgumentException(
                    field + " entries must be unique");
            }
            retained.add(normalized);
        }
        return List.copyOf(retained);
    }

    private static void requireComplete(boolean[] seen, String kind) {
        for (int index = 0; index < seen.length; index++) {
            if (!seen[index]) {
                throw new IllegalArgumentException(
                    "component partition omits " + kind + " " + index);
            }
        }
    }

    public enum ComponentKind {
        COUPLED_SUBSYSTEM,
        FREE_VARIABLES,
        CONSTANT_CONSTRAINTS
    }

    public record Component(
        String id,
        List<Integer> sourceRowIndices,
        List<Integer> sourceColumnIndices,
        List<String> variableNames,
        List<RowOrigin> rowOrigins,
        boolean contradictoryConstantConstraint
    ) {
        public Component {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("component id is required");
            }
            id = id.trim();
            sourceRowIndices = sortedUniqueIndices(
                sourceRowIndices,
                "sourceRowIndices");
            sourceColumnIndices = sortedUniqueIndices(
                sourceColumnIndices,
                "sourceColumnIndices");
            variableNames = textSet(variableNames, "variableNames");
            rowOrigins = List.copyOf(Objects.requireNonNull(
                rowOrigins,
                "rowOrigins"));
            if (sourceRowIndices.isEmpty()
                    && sourceColumnIndices.isEmpty()) {
                throw new IllegalArgumentException(
                    "component must contain a row or column");
            }
            if (variableNames.size() != sourceColumnIndices.size()
                    || rowOrigins.size() != sourceRowIndices.size()) {
                throw new IllegalArgumentException(
                    "component labels and index counts disagree");
            }
            for (int index = 0; index < rowOrigins.size(); index++) {
                if (rowOrigins.get(index).sourceIndex()
                        != sourceRowIndices.get(index)) {
                    throw new IllegalArgumentException(
                        "row origin does not match source row index");
                }
            }
            if (contradictoryConstantConstraint
                    && (!sourceColumnIndices.isEmpty()
                        || sourceRowIndices.isEmpty())) {
                throw new IllegalArgumentException(
                    "only a row-only component may be contradictory");
            }
        }

        public ComponentKind kind() {
            if (sourceRowIndices.isEmpty()) {
                return ComponentKind.FREE_VARIABLES;
            }
            if (sourceColumnIndices.isEmpty()) {
                return ComponentKind.CONSTANT_CONSTRAINTS;
            }
            return ComponentKind.COUPLED_SUBSYSTEM;
        }

        private static List<Integer> sortedUniqueIndices(
            List<Integer> values,
            String field
        ) {
            Objects.requireNonNull(values, field);
            List<Integer> retained = new ArrayList<>(values);
            retained.sort(Integer::compareTo);
            for (int index = 0; index < retained.size(); index++) {
                Integer value = retained.get(index);
                if (value == null
                        || value < 0
                        || (index > 0
                            && retained.get(index - 1).equals(value))) {
                    throw new IllegalArgumentException(
                        field + " contains invalid indices");
                }
            }
            return List.copyOf(retained);
        }
    }
}
