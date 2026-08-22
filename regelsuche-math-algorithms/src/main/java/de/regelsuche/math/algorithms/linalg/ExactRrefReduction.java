package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.SolutionClassification;
import de.regelsuche.representation.RepresentationBridge.Relation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Exact reduced-row-echelon representation of one {@link ExactLinearSystem}.
 *
 * <p>The retained elementary row operations are the authority for the declared
 * solution-set relation. They can be replayed against the original augmented
 * matrix without relying on the reduction algorithm's internal state.</p>
 */
public record ExactRrefReduction(
    ExactMatrix reducedCoefficients,
    List<String> variables,
    ExactVector reducedRightHandSide,
    List<Pivot> coefficientPivots,
    List<Integer> freeVariableColumns,
    Optional<ExactVector> particularSolution,
    List<ExactVector> nullspaceBasis,
    List<Integer> contradictionRows,
    List<RowOperation> rowOperations,
    CapabilityFrontier capabilityFrontier,
    Relation relation
) {
    public static final String CAPABILITY_EXACT_LINEAR_SYSTEM =
        "EXACT_LINEAR_SYSTEM_REPRESENTED";
    public static final String CAPABILITY_RANK_CLASSIFICATION =
        "EXACT_RANK_CLASSIFICATION_AVAILABLE";
    public static final String CAPABILITY_EXACT_RREF =
        "EXACT_RREF_AVAILABLE";
    public static final String CAPABILITY_ROW_OPERATION_REPLAY =
        "REPLAYABLE_ELEMENTARY_ROW_OPERATIONS";
    public static final String CAPABILITY_AFFINE_SOLUTION_SPACE =
        "EXACT_AFFINE_SOLUTION_SPACE_AVAILABLE";
    public static final String CAPABILITY_UNIQUE_SOLUTION =
        "EXACT_UNIQUE_SOLUTION_AVAILABLE";
    public static final String CAPABILITY_PARAMETRIC_SOLUTION =
        "EXACT_PARAMETRIC_SOLUTION_AVAILABLE";
    public static final String CAPABILITY_INCONSISTENCY_WITNESS =
        "EXACT_INCONSISTENCY_WITNESS_AVAILABLE";

    public ExactRrefReduction {
        reducedCoefficients = Objects.requireNonNull(
            reducedCoefficients,
            "reducedCoefficients");
        reducedRightHandSide = Objects.requireNonNull(
            reducedRightHandSide,
            "reducedRightHandSide");
        variables = normalizedVariables(variables);
        int variableCount = variables.size();
        coefficientPivots = List.copyOf(Objects.requireNonNull(
            coefficientPivots,
            "coefficientPivots"));
        freeVariableColumns = integerList(
            freeVariableColumns,
            "freeVariableColumns");
        particularSolution = Objects.requireNonNull(
            particularSolution,
            "particularSolution");
        nullspaceBasis = List.copyOf(Objects.requireNonNull(
            nullspaceBasis,
            "nullspaceBasis"));
        contradictionRows = integerList(
            contradictionRows,
            "contradictionRows");
        rowOperations = List.copyOf(Objects.requireNonNull(
            rowOperations,
            "rowOperations"));
        capabilityFrontier = Objects.requireNonNull(
            capabilityFrontier,
            "capabilityFrontier");
        relation = Objects.requireNonNull(relation, "relation");

        if (relation != Relation.SOLUTION_SET_EQUIVALENCE) {
            throw new IllegalArgumentException(
                "RREF reduction requires solution-set equivalence");
        }
        if (reducedCoefficients.columns() != variableCount) {
            throw new IllegalArgumentException(
                "coefficient columns and variable order disagree");
        }
        if (reducedCoefficients.rowCount()
                != reducedRightHandSide.dimension()) {
            throw new IllegalArgumentException(
                "reduced coefficient rows and RHS disagree");
        }

        validatePivots(
            coefficientPivots,
            reducedCoefficients.rowCount(),
            variableCount);
        validateComplement(
            coefficientPivots,
            freeVariableColumns,
            variableCount);
        validateIndices(
            contradictionRows,
            reducedCoefficients.rowCount(),
            "contradictionRows");
        if (contradictionRows.size() > 1) {
            throw new IllegalArgumentException(
                "an RREF augmented matrix has at most one RHS pivot");
        }

        boolean consistent = contradictionRows.isEmpty();
        if (particularSolution.isPresent() != consistent) {
            throw new IllegalArgumentException(
                "only consistent systems retain a particular solution");
        }
        particularSolution.ifPresent(solution ->
            requireDimension(solution, variableCount, "particularSolution"));
        if (!consistent && !nullspaceBasis.isEmpty()) {
            throw new IllegalArgumentException(
                "an inconsistent solution set retains no affine basis");
        }
        if (consistent && nullspaceBasis.size()
                != freeVariableColumns.size()) {
            throw new IllegalArgumentException(
                "nullspace basis and free variables disagree");
        }
        for (ExactVector basisVector : nullspaceBasis) {
            requireDimension(basisVector, variableCount, "nullspaceBasis");
        }

        for (RowOperation operation : rowOperations) {
            Objects.requireNonNull(operation, "rowOperation");
            operation.requireRowsWithin(reducedCoefficients.rowCount());
        }
        validateCapabilities(
            capabilityFrontier,
            solutionClassification());
    }

    public int coefficientRank() {
        return coefficientPivots.size();
    }

    public int augmentedRank() {
        return coefficientRank() + (contradictionRows.isEmpty() ? 0 : 1);
    }

    public SolutionClassification solutionClassification() {
        if (!contradictionRows.isEmpty()) {
            return SolutionClassification.INCONSISTENT;
        }
        return freeVariableColumns.isEmpty()
            ? SolutionClassification.UNIQUE
            : SolutionClassification.UNDERDETERMINED;
    }

    public List<List<Rational>> reducedAugmentedRows() {
        List<List<Rational>> rows = new ArrayList<>(
            reducedCoefficients.rowCount());
        for (int row = 0; row < reducedCoefficients.rowCount(); row++) {
            List<Rational> augmented = new ArrayList<>(
                reducedCoefficients.columns() + 1);
            augmented.addAll(reducedCoefficients.rows().get(row));
            augmented.add(reducedRightHandSide.get(row));
            rows.add(List.copyOf(augmented));
        }
        return List.copyOf(rows);
    }

    private static List<String> normalizedVariables(List<String> values) {
        Objects.requireNonNull(values, "variables");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("variables must not be empty");
        }
        Set<String> unique = new HashSet<>();
        List<String> retained = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                    "variable names must not be blank");
            }
            String normalized = value.trim();
            if (!unique.add(normalized)) {
                throw new IllegalArgumentException(
                    "variable names must be unique");
            }
            retained.add(normalized);
        }
        return List.copyOf(retained);
    }

    private static List<Integer> integerList(
        List<Integer> values,
        String field
    ) {
        Objects.requireNonNull(values, field);
        List<Integer> retained = values.stream()
            .map(value -> Objects.requireNonNull(value, field + " entry"))
            .toList();
        if (new HashSet<>(retained).size() != retained.size()) {
            throw new IllegalArgumentException(field + " entries must be unique");
        }
        return retained;
    }

    private static void validatePivots(
        List<Pivot> pivots,
        int rowCount,
        int columnCount
    ) {
        int previousColumn = -1;
        for (int index = 0; index < pivots.size(); index++) {
            Pivot pivot = Objects.requireNonNull(pivots.get(index), "pivot");
            if (pivot.row() != index
                    || pivot.row() >= rowCount
                    || pivot.column() >= columnCount
                    || pivot.column() <= previousColumn) {
                throw new IllegalArgumentException(
                    "coefficient pivots must be ordered RREF pivots");
            }
            previousColumn = pivot.column();
        }
    }

    private static void validateComplement(
        List<Pivot> pivots,
        List<Integer> freeColumns,
        int columnCount
    ) {
        Set<Integer> pivotColumns = new HashSet<>();
        pivots.forEach(pivot -> pivotColumns.add(pivot.column()));
        List<Integer> expectedFree = new ArrayList<>();
        for (int column = 0; column < columnCount; column++) {
            if (!pivotColumns.contains(column)) {
                expectedFree.add(column);
            }
        }
        if (!expectedFree.equals(freeColumns)) {
            throw new IllegalArgumentException(
                "free columns must exactly complement pivot columns");
        }
    }

    private static void validateIndices(
        List<Integer> indices,
        int upperExclusive,
        String field
    ) {
        int previous = -1;
        for (int index : indices) {
            if (index < 0 || index >= upperExclusive || index <= previous) {
                throw new IllegalArgumentException(
                    field + " entries must be ordered valid indices");
            }
            previous = index;
        }
    }

    private static void requireDimension(
        ExactVector vector,
        int dimension,
        String field
    ) {
        Objects.requireNonNull(vector, field);
        if (vector.dimension() != dimension) {
            throw new IllegalArgumentException(
                field + " vector dimension disagrees with variables");
        }
    }

    private static void validateCapabilities(
        CapabilityFrontier frontier,
        SolutionClassification classification
    ) {
        Set<String> after = Set.copyOf(frontier.applicableAfter());
        require(after, CAPABILITY_EXACT_LINEAR_SYSTEM);
        require(after, CAPABILITY_RANK_CLASSIFICATION);
        require(after, CAPABILITY_EXACT_RREF);
        require(after, CAPABILITY_ROW_OPERATION_REPLAY);
        boolean consistent = classification != SolutionClassification.INCONSISTENT;
        requirePresence(
            after,
            CAPABILITY_AFFINE_SOLUTION_SPACE,
            consistent);
        requirePresence(
            after,
            CAPABILITY_UNIQUE_SOLUTION,
            classification == SolutionClassification.UNIQUE);
        requirePresence(
            after,
            CAPABILITY_PARAMETRIC_SOLUTION,
            classification == SolutionClassification.UNDERDETERMINED);
        requirePresence(
            after,
            CAPABILITY_INCONSISTENCY_WITNESS,
            classification == SolutionClassification.INCONSISTENT);
    }

    private static void require(Set<String> capabilities, String capability) {
        if (!capabilities.contains(capability)) {
            throw new IllegalArgumentException(
                "missing required capability: " + capability);
        }
    }

    private static void requirePresence(
        Set<String> capabilities,
        String capability,
        boolean required
    ) {
        if (capabilities.contains(capability) != required) {
            throw new IllegalArgumentException(
                "capability disagrees with reduction: " + capability);
        }
    }

    public record Pivot(int row, int column) {
        public Pivot {
            if (row < 0 || column < 0) {
                throw new IllegalArgumentException(
                    "pivot indices must not be negative");
            }
        }
    }

    public enum RowOperationKind {
        SWAP_ROWS,
        SCALE_ROW,
        ADD_ROW_MULTIPLE
    }

    /**
     * One invertible elementary row operation. For {@code ADD_ROW_MULTIPLE},
     * the operation is {@code target += multiplier * source}.
     */
    public record RowOperation(
        RowOperationKind kind,
        int targetRow,
        int sourceRow,
        Rational multiplier
    ) {
        public RowOperation {
            kind = Objects.requireNonNull(kind, "kind");
            multiplier = Objects.requireNonNull(multiplier, "multiplier");
            if (targetRow < 0) {
                throw new IllegalArgumentException(
                    "targetRow must not be negative");
            }
            switch (kind) {
                case SWAP_ROWS -> {
                    if (sourceRow < 0
                            || sourceRow == targetRow
                            || !multiplier.isOne()) {
                        throw new IllegalArgumentException(
                            "row swap requires two rows and multiplier one");
                    }
                }
                case SCALE_ROW -> {
                    if (sourceRow != -1 || multiplier.isZero()) {
                        throw new IllegalArgumentException(
                            "row scaling requires a non-zero multiplier");
                    }
                }
                case ADD_ROW_MULTIPLE -> {
                    if (sourceRow < 0
                            || sourceRow == targetRow
                            || multiplier.isZero()) {
                        throw new IllegalArgumentException(
                            "row addition requires distinct rows and non-zero multiplier");
                    }
                }
            }
        }

        public static RowOperation swap(int firstRow, int secondRow) {
            return new RowOperation(
                RowOperationKind.SWAP_ROWS,
                firstRow,
                secondRow,
                Rational.ONE);
        }

        public static RowOperation scale(int row, Rational multiplier) {
            return new RowOperation(
                RowOperationKind.SCALE_ROW,
                row,
                -1,
                multiplier);
        }

        public static RowOperation addMultiple(
            int targetRow,
            int sourceRow,
            Rational multiplier
        ) {
            return new RowOperation(
                RowOperationKind.ADD_ROW_MULTIPLE,
                targetRow,
                sourceRow,
                multiplier);
        }

        public void requireRowsWithin(int rowCount) {
            if (targetRow >= rowCount
                    || (sourceRow >= 0 && sourceRow >= rowCount)) {
                throw new IllegalArgumentException(
                    "row operation index exceeds matrix rows");
            }
        }

        public String canonicalForm() {
            return switch (kind) {
                case SWAP_ROWS -> "swap(" + targetRow + "," + sourceRow + ")";
                case SCALE_ROW -> "scale(" + targetRow + "," + multiplier + ")";
                case ADD_ROW_MULTIPLE -> "add(" + targetRow + ","
                    + sourceRow + "," + multiplier + ")";
            };
        }
    }

    /**
     * Explicit capability delta caused by the RREF representation.
     */
    public record CapabilityFrontier(
        List<String> applicableBefore,
        List<String> applicableAfter,
        List<String> newlyUnlocked,
        List<String> lostOrConditional
    ) {
        public CapabilityFrontier {
            List<String> normalizedBefore = distinctTexts(
                applicableBefore,
                "applicableBefore");
            List<String> normalizedAfter = distinctTexts(
                applicableAfter,
                "applicableAfter");
            List<String> normalizedNew = distinctTexts(
                newlyUnlocked,
                "newlyUnlocked");
            List<String> normalizedLost = distinctTexts(
                lostOrConditional,
                "lostOrConditional");

            Set<String> before = new LinkedHashSet<>(normalizedBefore);
            Set<String> after = new LinkedHashSet<>(normalizedAfter);
            List<String> expectedNew = normalizedAfter.stream()
                .filter(capability -> !before.contains(capability))
                .toList();
            List<String> expectedLost = normalizedBefore.stream()
                .filter(capability -> !after.contains(capability))
                .toList();
            if (!expectedNew.equals(normalizedNew)
                    || !expectedLost.equals(normalizedLost)) {
                throw new IllegalArgumentException(
                    "capability frontier lists must be exact ordered differences");
            }

            applicableBefore = normalizedBefore;
            applicableAfter = normalizedAfter;
            newlyUnlocked = normalizedNew;
            lostOrConditional = normalizedLost;
        }

        private static List<String> distinctTexts(
            List<String> values,
            String field
        ) {
            Objects.requireNonNull(values, field);
            List<String> retained = values.stream().map(value -> {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(
                        field + " entries must not be blank");
                }
                return value.trim();
            }).toList();
            if (new HashSet<>(retained).size() != retained.size()) {
                throw new IllegalArgumentException(
                    field + " entries must be distinct");
            }
            return retained;
        }
    }
}
