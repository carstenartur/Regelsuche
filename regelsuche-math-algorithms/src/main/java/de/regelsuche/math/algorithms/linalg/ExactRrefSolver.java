package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.SolutionClassification;
import de.regelsuche.math.algorithms.linalg.ExactRrefReduction.CapabilityFrontier;
import de.regelsuche.math.algorithms.linalg.ExactRrefReduction.Pivot;
import de.regelsuche.math.algorithms.linalg.ExactRrefReduction.RowOperation;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.representation.RepresentationBridge.Relation;
import de.regelsuche.representation.RepresentationBridge.WorkLedger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic exact Gauss-Jordan reduction for {@link ExactLinearSystem}.
 *
 * <p>The solver is bounded by explicit mechanical work. Every successful result
 * retains the invertible elementary row-operation lineage, an exact affine
 * solution description or inconsistency witness, a capability delta and a
 * content-addressed certificate. Verification recomputes the complete result.</p>
 */
public final class ExactRrefSolver {
    public static final String SOLVER_ID = "exact-rref/gauss-jordan/v1";
    public static final String CERTIFICATE_SCHEMA =
        "regelsuche.exact-rref-certificate/v1";

    public Result solve(ExactLinearSystem source, Budget budget) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(budget, "budget");
        WorkCounter work = new WorkCounter(budget.maxWorkUnits());
        try {
            MutableAugmentedMatrix matrix = copy(source, work);
            List<RowOperation> operations = new ArrayList<>();
            List<Pivot> coefficientPivots = new ArrayList<>();
            int pivotRow = 0;
            for (int column = 0;
                    column < matrix.width() && pivotRow < matrix.rowCount();
                    column++) {
                int candidate = findPivot(matrix, pivotRow, column, work);
                if (candidate < 0) {
                    continue;
                }
                if (candidate != pivotRow) {
                    swapRows(matrix, pivotRow, candidate, work);
                    operations.add(RowOperation.swap(pivotRow, candidate));
                }

                Rational pivot = matrix.get(pivotRow, column);
                work.consume();
                if (!pivot.isOne()) {
                    Rational multiplier = Rational.ONE.divide(pivot);
                    scaleRow(matrix, pivotRow, multiplier, work);
                    operations.add(RowOperation.scale(pivotRow, multiplier));
                }

                for (int row = 0; row < matrix.rowCount(); row++) {
                    if (row == pivotRow) {
                        continue;
                    }
                    work.consume();
                    Rational factor = matrix.get(row, column);
                    if (factor.isZero()) {
                        continue;
                    }
                    Rational multiplier = factor.negate();
                    addRowMultiple(
                        matrix,
                        row,
                        pivotRow,
                        multiplier,
                        work);
                    operations.add(RowOperation.addMultiple(
                        row,
                        pivotRow,
                        multiplier));
                }

                if (column < matrix.coefficientColumns()) {
                    coefficientPivots.add(new Pivot(pivotRow, column));
                }
                pivotRow++;
            }

            List<Integer> contradictionRows = contradictionRows(matrix, work);
            int coefficientRank = coefficientPivots.size();
            int augmentedRank = coefficientRank
                + (contradictionRows.isEmpty() ? 0 : 1);
            SolutionClassification classification = contradictionRows.isEmpty()
                ? coefficientRank == matrix.coefficientColumns()
                    ? SolutionClassification.UNIQUE
                    : SolutionClassification.UNDERDETERMINED
                : SolutionClassification.INCONSISTENT;
            if (source.coefficientRank() != coefficientRank
                    || source.augmentedRank() != augmentedRank
                    || source.solutionClassification() != classification) {
                return Result.withoutReduction(
                    Status.INVALID_SOURCE,
                    work.ledger(),
                    "SOURCE_RANK_METADATA_MISMATCH");
            }

            ExactMatrix reducedCoefficients = reducedCoefficients(matrix);
            ExactVector reducedRightHandSide = reducedRightHandSide(matrix);
            List<Integer> freeColumns = freeColumns(
                coefficientPivots,
                matrix.coefficientColumns());
            SolutionData solutionData = solutionData(
                reducedCoefficients,
                reducedRightHandSide,
                coefficientPivots,
                freeColumns,
                contradictionRows);
            CapabilityFrontier frontier = capabilityFrontier(classification);
            ExactRrefReduction reduction = new ExactRrefReduction(
                reducedCoefficients,
                source.variables(),
                reducedRightHandSide,
                coefficientPivots,
                freeColumns,
                solutionData.particularSolution(),
                solutionData.nullspaceBasis(),
                contradictionRows,
                operations,
                frontier,
                Relation.SOLUTION_SET_EQUIVALENCE);

            if (!isReducedRowEchelon(reduction, work)
                    || !replayMatches(source, reduction, work)) {
                return Result.withoutReduction(
                    Status.INVALID_CERTIFICATE,
                    work.ledger(),
                    "RREF_REPLAY_OR_NORMAL_FORM_MISMATCH");
            }

            Certificate certificate = certificate(source, reduction);
            return Result.solved(reduction, certificate, work.ledger());
        } catch (BudgetExceeded exception) {
            return Result.withoutReduction(
                Status.BUDGET_INCONCLUSIVE,
                work.ledger(),
                "RREF_WORK_BUDGET_EXHAUSTED");
        }
    }

    public boolean verify(ExactLinearSystem source, Result result) {
        if (source == null
                || result == null
                || result.status() != Status.SOLVED) {
            return false;
        }
        try {
            return solve(
                source,
                new Budget(result.work().configuredWorkUnits()))
                .equals(result);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static MutableAugmentedMatrix copy(
        ExactLinearSystem source,
        WorkCounter work
    ) {
        List<List<Rational>> rows = new ArrayList<>(source.equationCount());
        for (int row = 0; row < source.equationCount(); row++) {
            List<Rational> retained = new ArrayList<>(
                source.variableCount() + 1);
            for (int column = 0;
                    column < source.variableCount();
                    column++) {
                work.consume();
                retained.add(source.coefficients().get(row, column));
            }
            work.consume();
            retained.add(source.rightHandSide().get(row));
            rows.add(retained);
        }
        return new MutableAugmentedMatrix(rows, source.variableCount());
    }

    private static int findPivot(
        MutableAugmentedMatrix matrix,
        int firstRow,
        int column,
        WorkCounter work
    ) {
        for (int row = firstRow; row < matrix.rowCount(); row++) {
            work.consume();
            if (!matrix.get(row, column).isZero()) {
                return row;
            }
        }
        return -1;
    }

    private static void swapRows(
        MutableAugmentedMatrix matrix,
        int firstRow,
        int secondRow,
        WorkCounter work
    ) {
        work.consume(matrix.width());
        Collections.swap(matrix.rows(), firstRow, secondRow);
    }

    private static void scaleRow(
        MutableAugmentedMatrix matrix,
        int row,
        Rational multiplier,
        WorkCounter work
    ) {
        for (int column = 0; column < matrix.width(); column++) {
            work.consume();
            matrix.set(
                row,
                column,
                matrix.get(row, column).multiply(multiplier));
        }
    }

    private static void addRowMultiple(
        MutableAugmentedMatrix matrix,
        int targetRow,
        int sourceRow,
        Rational multiplier,
        WorkCounter work
    ) {
        for (int column = 0; column < matrix.width(); column++) {
            work.consume();
            Rational replacement = matrix.get(targetRow, column).add(
                matrix.get(sourceRow, column).multiply(multiplier));
            matrix.set(targetRow, column, replacement);
        }
    }

    private static List<Integer> contradictionRows(
        MutableAugmentedMatrix matrix,
        WorkCounter work
    ) {
        List<Integer> result = new ArrayList<>();
        for (int row = 0; row < matrix.rowCount(); row++) {
            boolean zeroCoefficients = true;
            for (int column = 0;
                    column < matrix.coefficientColumns();
                    column++) {
                work.consume();
                if (!matrix.get(row, column).isZero()) {
                    zeroCoefficients = false;
                    break;
                }
            }
            work.consume();
            if (zeroCoefficients
                    && !matrix.get(row, matrix.coefficientColumns()).isZero()) {
                result.add(row);
            }
        }
        return List.copyOf(result);
    }

    private static ExactMatrix reducedCoefficients(
        MutableAugmentedMatrix matrix
    ) {
        List<List<Rational>> rows = new ArrayList<>(matrix.rowCount());
        for (List<Rational> row : matrix.rows()) {
            rows.add(List.copyOf(row.subList(
                0,
                matrix.coefficientColumns())));
        }
        return new ExactMatrix(rows);
    }

    private static ExactVector reducedRightHandSide(
        MutableAugmentedMatrix matrix
    ) {
        return new ExactVector(matrix.rows().stream()
            .map(row -> row.get(matrix.coefficientColumns()))
            .toList());
    }

    private static List<Integer> freeColumns(
        List<Pivot> pivots,
        int coefficientColumns
    ) {
        Set<Integer> pivotColumns = new HashSet<>();
        pivots.forEach(pivot -> pivotColumns.add(pivot.column()));
        List<Integer> result = new ArrayList<>();
        for (int column = 0; column < coefficientColumns; column++) {
            if (!pivotColumns.contains(column)) {
                result.add(column);
            }
        }
        return List.copyOf(result);
    }

    private static SolutionData solutionData(
        ExactMatrix coefficients,
        ExactVector rightHandSide,
        List<Pivot> pivots,
        List<Integer> freeColumns,
        List<Integer> contradictionRows
    ) {
        if (!contradictionRows.isEmpty()) {
            return new SolutionData(Optional.empty(), List.of());
        }
        List<Rational> particular = zeroVector(coefficients.columns());
        for (Pivot pivot : pivots) {
            particular.set(pivot.column(), rightHandSide.get(pivot.row()));
        }

        List<ExactVector> basis = new ArrayList<>(freeColumns.size());
        for (int freeColumn : freeColumns) {
            List<Rational> vector = zeroVector(coefficients.columns());
            vector.set(freeColumn, Rational.ONE);
            for (Pivot pivot : pivots) {
                vector.set(
                    pivot.column(),
                    coefficients.get(pivot.row(), freeColumn).negate());
            }
            basis.add(new ExactVector(vector));
        }
        return new SolutionData(
            Optional.of(new ExactVector(particular)),
            List.copyOf(basis));
    }

    private static List<Rational> zeroVector(int dimension) {
        return new ArrayList<>(Collections.nCopies(
            dimension,
            Rational.ZERO));
    }

    private static CapabilityFrontier capabilityFrontier(
        SolutionClassification classification
    ) {
        List<String> before = List.of(
            ExactRrefReduction.CAPABILITY_EXACT_LINEAR_SYSTEM,
            ExactRrefReduction.CAPABILITY_RANK_CLASSIFICATION);
        List<String> after = new ArrayList<>(before);
        after.add(ExactRrefReduction.CAPABILITY_EXACT_RREF);
        after.add(ExactRrefReduction.CAPABILITY_ROW_OPERATION_REPLAY);
        switch (classification) {
            case UNIQUE -> {
                after.add(ExactRrefReduction
                    .CAPABILITY_AFFINE_SOLUTION_SPACE);
                after.add(ExactRrefReduction.CAPABILITY_UNIQUE_SOLUTION);
            }
            case UNDERDETERMINED -> {
                after.add(ExactRrefReduction
                    .CAPABILITY_AFFINE_SOLUTION_SPACE);
                after.add(ExactRrefReduction
                    .CAPABILITY_PARAMETRIC_SOLUTION);
            }
            case INCONSISTENT -> after.add(ExactRrefReduction
                .CAPABILITY_INCONSISTENCY_WITNESS);
        }
        List<String> immutableAfter = List.copyOf(after);
        return new CapabilityFrontier(
            before,
            immutableAfter,
            immutableAfter.subList(before.size(), immutableAfter.size()),
            List.of());
    }

    private static boolean isReducedRowEchelon(
        ExactRrefReduction reduction,
        WorkCounter work
    ) {
        List<List<Rational>> rows = reduction.reducedAugmentedRows();
        int width = reduction.variables().size() + 1;
        int previousPivot = -1;
        boolean zeroRowSeen = false;
        List<Pivot> coefficientPivots = new ArrayList<>();
        List<Integer> contradictions = new ArrayList<>();
        for (int row = 0; row < rows.size(); row++) {
            int leadingColumn = -1;
            for (int column = 0; column < width; column++) {
                work.consume();
                if (!rows.get(row).get(column).isZero()) {
                    leadingColumn = column;
                    break;
                }
            }
            if (leadingColumn < 0) {
                zeroRowSeen = true;
                continue;
            }
            if (zeroRowSeen || leadingColumn <= previousPivot) {
                return false;
            }
            work.consume();
            if (!rows.get(row).get(leadingColumn).isOne()) {
                return false;
            }
            for (int otherRow = 0; otherRow < rows.size(); otherRow++) {
                if (otherRow == row) {
                    continue;
                }
                work.consume();
                if (!rows.get(otherRow).get(leadingColumn).isZero()) {
                    return false;
                }
            }
            if (leadingColumn < reduction.variables().size()) {
                coefficientPivots.add(new Pivot(row, leadingColumn));
            } else {
                contradictions.add(row);
            }
            previousPivot = leadingColumn;
        }
        return coefficientPivots.equals(reduction.coefficientPivots())
            && contradictions.equals(reduction.contradictionRows());
    }

    private static boolean replayMatches(
        ExactLinearSystem source,
        ExactRrefReduction reduction,
        WorkCounter work
    ) {
        MutableAugmentedMatrix replay = copy(source, work);
        for (RowOperation operation : reduction.rowOperations()) {
            switch (operation.kind()) {
                case SWAP_ROWS -> swapRows(
                    replay,
                    operation.targetRow(),
                    operation.sourceRow(),
                    work);
                case SCALE_ROW -> scaleRow(
                    replay,
                    operation.targetRow(),
                    operation.multiplier(),
                    work);
                case ADD_ROW_MULTIPLE -> addRowMultiple(
                    replay,
                    operation.targetRow(),
                    operation.sourceRow(),
                    operation.multiplier(),
                    work);
            }
        }
        List<List<Rational>> expected = reduction.reducedAugmentedRows();
        for (int row = 0; row < replay.rowCount(); row++) {
            for (int column = 0; column < replay.width(); column++) {
                work.consume();
                if (!replay.get(row, column).equals(
                        expected.get(row).get(column))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Certificate certificate(
        ExactLinearSystem source,
        ExactRrefReduction reduction
    ) {
        String sourceHash = sourceHash(source);
        List<List<String>> reducedRows = canonicalRows(
            reduction.reducedAugmentedRows());
        List<String> operations = reduction.rowOperations().stream()
            .map(RowOperation::canonicalForm)
            .toList();
        List<String> particular = reduction.particularSolution()
            .map(solution -> canonicalVector(solution.values()))
            .orElseGet(List::of);
        List<List<String>> nullspace = reduction.nullspaceBasis().stream()
            .map(vector -> canonicalVector(vector.values()))
            .toList();
        CapabilityFrontier frontier = reduction.capabilityFrontier();

        StringBuilder payload = new StringBuilder();
        append(payload, CERTIFICATE_SCHEMA);
        append(payload, SOLVER_ID);
        append(payload, Relation.SOLUTION_SET_EQUIVALENCE.name());
        append(payload, sourceHash);
        append(payload, reduction.solutionClassification().name());
        appendRows(payload, reducedRows);
        operations.forEach(value -> append(payload, value));
        reduction.coefficientPivots().forEach(pivot -> {
            append(payload, Integer.toString(pivot.row()));
            append(payload, Integer.toString(pivot.column()));
        });
        reduction.freeVariableColumns().forEach(value ->
            append(payload, Integer.toString(value)));
        reduction.contradictionRows().forEach(value ->
            append(payload, Integer.toString(value)));
        particular.forEach(value -> append(payload, value));
        appendRows(payload, nullspace);
        frontier.applicableBefore().forEach(value -> append(payload, value));
        frontier.applicableAfter().forEach(value -> append(payload, value));
        frontier.newlyUnlocked().forEach(value -> append(payload, value));
        frontier.lostOrConditional().forEach(value -> append(payload, value));

        return new Certificate(
            CERTIFICATE_SCHEMA,
            SOLVER_ID,
            Relation.SOLUTION_SET_EQUIVALENCE,
            sourceHash,
            reduction.solutionClassification(),
            reducedRows,
            operations,
            reduction.coefficientPivots(),
            reduction.freeVariableColumns(),
            reduction.contradictionRows(),
            particular,
            nullspace,
            frontier.applicableBefore(),
            frontier.applicableAfter(),
            frontier.newlyUnlocked(),
            frontier.lostOrConditional(),
            sha256(payload.toString()));
    }

    private static String sourceHash(ExactLinearSystem source) {
        StringBuilder payload = new StringBuilder();
        append(payload, Integer.toString(source.equationCount()));
        append(payload, Integer.toString(source.variableCount()));
        source.variables().forEach(value -> append(payload, value));
        for (List<Rational> row : source.coefficients().rows()) {
            row.stream().map(Rational::toString)
                .forEach(value -> append(payload, value));
        }
        source.rightHandSide().values().stream()
            .map(Rational::toString)
            .forEach(value -> append(payload, value));
        source.rowOrigins().forEach(origin -> {
            append(payload, Integer.toString(origin.sourceIndex()));
            append(payload, origin.sourceEquation());
        });
        append(payload, Integer.toString(source.coefficientRank()));
        append(payload, Integer.toString(source.augmentedRank()));
        append(payload, source.solutionClassification().name());
        return sha256(payload.toString());
    }

    private static List<List<String>> canonicalRows(
        List<List<Rational>> rows
    ) {
        return rows.stream().map(ExactRrefSolver::canonicalVector).toList();
    }

    private static List<String> canonicalVector(List<Rational> values) {
        return values.stream().map(Rational::toString).toList();
    }

    private static void appendRows(
        StringBuilder target,
        List<List<String>> rows
    ) {
        append(target, Integer.toString(rows.size()));
        for (List<String> row : rows) {
            append(target, Integer.toString(row.size()));
            row.forEach(value -> append(target, value));
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public enum Status {
        SOLVED,
        INVALID_SOURCE,
        BUDGET_INCONCLUSIVE,
        INVALID_CERTIFICATE
    }

    public record Certificate(
        String schema,
        String solverId,
        Relation relation,
        String sourceSystemHash,
        SolutionClassification solutionClassification,
        List<List<String>> reducedAugmentedRows,
        List<String> canonicalOperations,
        List<Pivot> coefficientPivots,
        List<Integer> freeVariableColumns,
        List<Integer> contradictionRows,
        List<String> particularSolution,
        List<List<String>> nullspaceBasis,
        List<String> capabilitiesBefore,
        List<String> capabilitiesAfter,
        List<String> newlyUnlockedCapabilities,
        List<String> lostOrConditionalCapabilities,
        String contentHash
    ) {
        public Certificate {
            if (!CERTIFICATE_SCHEMA.equals(schema)
                    || !SOLVER_ID.equals(solverId)
                    || sourceSystemHash == null
                    || !sourceSystemHash.matches("[0-9a-f]{64}")
                    || contentHash == null
                    || !contentHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "certificate identities are invalid");
            }
            relation = Objects.requireNonNull(relation, "relation");
            if (relation != Relation.SOLUTION_SET_EQUIVALENCE) {
                throw new IllegalArgumentException(
                    "RREF certificate relation is invalid");
            }
            solutionClassification = Objects.requireNonNull(
                solutionClassification,
                "solutionClassification");
            reducedAugmentedRows = matrixText(
                reducedAugmentedRows,
                "reducedAugmentedRows");
            canonicalOperations = textListAllowEmpty(
                canonicalOperations,
                "canonicalOperations");
            coefficientPivots = List.copyOf(Objects.requireNonNull(
                coefficientPivots,
                "coefficientPivots"));
            freeVariableColumns = integerList(
                freeVariableColumns,
                "freeVariableColumns");
            contradictionRows = integerList(
                contradictionRows,
                "contradictionRows");
            particularSolution = textListAllowEmpty(
                particularSolution,
                "particularSolution");
            nullspaceBasis = matrixTextAllowEmpty(
                nullspaceBasis,
                "nullspaceBasis");
            capabilitiesBefore = textList(
                capabilitiesBefore,
                "capabilitiesBefore");
            capabilitiesAfter = textList(
                capabilitiesAfter,
                "capabilitiesAfter");
            newlyUnlockedCapabilities = textList(
                newlyUnlockedCapabilities,
                "newlyUnlockedCapabilities");
            lostOrConditionalCapabilities = textListAllowEmpty(
                lostOrConditionalCapabilities,
                "lostOrConditionalCapabilities");
        }

        private static List<List<String>> matrixText(
            List<List<String>> rows,
            String field
        ) {
            List<List<String>> retained = matrixTextAllowEmpty(rows, field);
            if (retained.isEmpty()) {
                throw new IllegalArgumentException(field + " must not be empty");
            }
            int width = retained.getFirst().size();
            if (width == 0
                    || retained.stream().anyMatch(row -> row.size() != width)) {
                throw new IllegalArgumentException(
                    field + " rows must have one common positive width");
            }
            return retained;
        }

        private static List<List<String>> matrixTextAllowEmpty(
            List<List<String>> rows,
            String field
        ) {
            Objects.requireNonNull(rows, field);
            return rows.stream()
                .map(row -> textList(row, field + " row"))
                .toList();
        }

        private static List<String> textList(
            List<String> values,
            String field
        ) {
            List<String> retained = textListAllowEmpty(values, field);
            if (retained.isEmpty()) {
                throw new IllegalArgumentException(field + " must not be empty");
            }
            return retained;
        }

        private static List<String> textListAllowEmpty(
            List<String> values,
            String field
        ) {
            Objects.requireNonNull(values, field);
            return values.stream().map(value -> {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(
                        field + " entries must not be blank");
                }
                return value.trim();
            }).toList();
        }

        private static List<Integer> integerList(
            List<Integer> values,
            String field
        ) {
            Objects.requireNonNull(values, field);
            return values.stream()
                .map(value -> Objects.requireNonNull(value, field + " entry"))
                .toList();
        }
    }

    public record Result(
        Status status,
        Optional<ExactRrefReduction> reduction,
        Optional<Certificate> certificate,
        WorkLedger work,
        String detailCode
    ) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            reduction = Objects.requireNonNull(reduction, "reduction");
            certificate = Objects.requireNonNull(certificate, "certificate");
            work = Objects.requireNonNull(work, "work");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
            detailCode = detailCode.trim();
            boolean complete = reduction.isPresent() && certificate.isPresent();
            if ((status == Status.SOLVED) != complete
                    || reduction.isPresent() != certificate.isPresent()) {
                throw new IllegalArgumentException(
                    "only solved results retain complete RREF evidence");
            }
        }

        private static Result solved(
            ExactRrefReduction reduction,
            Certificate certificate,
            WorkLedger work
        ) {
            return new Result(
                Status.SOLVED,
                Optional.of(reduction),
                Optional.of(certificate),
                work,
                "EXACT_RREF_AND_SOLUTION_SPACE_COMPUTED");
        }

        private static Result withoutReduction(
            Status status,
            WorkLedger work,
            String detailCode
        ) {
            if (status == Status.SOLVED) {
                throw new IllegalArgumentException(
                    "solved result requires RREF evidence");
            }
            return new Result(
                status,
                Optional.empty(),
                Optional.empty(),
                work,
                detailCode);
        }
    }

    private record SolutionData(
        Optional<ExactVector> particularSolution,
        List<ExactVector> nullspaceBasis
    ) {
        private SolutionData {
            particularSolution = Objects.requireNonNull(
                particularSolution,
                "particularSolution");
            nullspaceBasis = List.copyOf(Objects.requireNonNull(
                nullspaceBasis,
                "nullspaceBasis"));
        }
    }

    private record MutableAugmentedMatrix(
        List<List<Rational>> rows,
        int coefficientColumns
    ) {
        private MutableAugmentedMatrix {
            Objects.requireNonNull(rows, "rows");
            if (rows.isEmpty() || coefficientColumns < 1) {
                throw new IllegalArgumentException(
                    "augmented matrix dimensions must be positive");
            }
            int width = coefficientColumns + 1;
            if (rows.stream().anyMatch(row -> row.size() != width)) {
                throw new IllegalArgumentException(
                    "augmented matrix rows have inconsistent widths");
            }
        }

        private int rowCount() {
            return rows.size();
        }

        private int width() {
            return coefficientColumns + 1;
        }

        private Rational get(int row, int column) {
            return rows.get(row).get(column);
        }

        private void set(int row, int column, Rational value) {
            rows.get(row).set(column, value);
        }
    }

    private static final class WorkCounter {
        private final int configured;
        private int consumed;

        private WorkCounter(int configured) {
            this.configured = configured;
        }

        private void consume() {
            consume(1);
        }

        private void consume(int units) {
            if (units < 0) {
                throw new IllegalArgumentException(
                    "work units must not be negative");
            }
            if (units > configured - consumed) {
                consumed = configured;
                throw new BudgetExceeded();
            }
            consumed += units;
        }

        private WorkLedger ledger() {
            return WorkLedger.of(configured, consumed);
        }
    }

    private static final class BudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
