package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.RowOrigin;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystemBlockDecomposition.Component;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystemBlockDecomposition.ComponentKind;
import de.regelsuche.representation.RepresentationBridge;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Exposes independent subsystems as connected components of the exact
 * row-variable incidence graph.
 *
 * <p>A non-zero coefficient connects one source equation row with one variable
 * column. Distinct connected components can be solved independently because all
 * cross-component coefficients are exactly zero. Zero columns remain explicit
 * free-variable components; zero rows remain explicit constant constraints.</p>
 */
public final class ExactLinearSystemBlockDecomposer implements
        RepresentationBridge<ExactLinearSystem,
            ExactLinearSystemBlockDecomposition,
            ExactLinearSystemBlockDecomposer.Certificate> {

    public static final String DECOMPOSER_ID =
        "exact-linear-system-block-decomposition/v1";
    public static final String CERTIFICATE_SCHEMA =
        "regelsuche.exact-linear-system-block-certificate/v1";
    private static final Relation RELATION =
        Relation.SOLUTION_SET_EQUIVALENCE;

    @Override
    public Result<ExactLinearSystemBlockDecomposition, Certificate> analyze(
        ExactLinearSystem source,
        Budget budget
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(budget, "budget");
        WorkCounter work = new WorkCounter(budget.maxWorkUnits());
        try {
            Adjacency adjacency = adjacency(source, work);
            List<DraftComponent> drafts = connectedComponents(
                adjacency,
                work);
            if (drafts.size() < 2) {
                return Result.withoutRepresentation(
                    Status.NOT_APPLICABLE,
                    work.ledger(),
                    "COEFFICIENT_INCIDENCE_GRAPH_IS_CONNECTED");
            }

            List<Component> components = materialize(source, drafts, work);
            if (!crossComponentCoefficientsAreZero(
                    source,
                    components,
                    work)) {
                return Result.withoutRepresentation(
                    Status.INVALID_CERTIFICATE,
                    work.ledger(),
                    "NON_ZERO_CROSS_COMPONENT_COEFFICIENT");
            }

            List<Integer> rowPermutation = components.stream()
                .flatMap(component -> component.sourceRowIndices().stream())
                .toList();
            List<Integer> columnPermutation = components.stream()
                .flatMap(component -> component.sourceColumnIndices().stream())
                .toList();
            List<String> capabilities = capabilities(components);
            ExactLinearSystemBlockDecomposition decomposition =
                new ExactLinearSystemBlockDecomposition(
                    source.equationCount(),
                    source.variableCount(),
                    components,
                    rowPermutation,
                    columnPermutation,
                    capabilities);
            Certificate certificate = certificate(source, decomposition);
            return Result.represented(
                decomposition,
                certificate,
                RELATION,
                work.ledger(),
                "INDEPENDENT_EXACT_LINEAR_BLOCKS_EXPOSED");
        } catch (BudgetExceeded exception) {
            return Result.withoutRepresentation(
                Status.BUDGET_INCONCLUSIVE,
                work.ledger(),
                "BLOCK_DECOMPOSITION_WORK_BUDGET_EXHAUSTED");
        }
    }

    @Override
    public boolean verify(
        ExactLinearSystem source,
        Result<ExactLinearSystemBlockDecomposition, Certificate> result
    ) {
        if (source == null
                || result == null
                || result.status() != Status.REPRESENTED
                || result.relation().orElse(null) != RELATION) {
            return false;
        }
        try {
            Result<ExactLinearSystemBlockDecomposition, Certificate>
                recomputed = analyze(
                    source,
                    new Budget(result.work().configuredWorkUnits()));
            return recomputed.equals(result);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static Adjacency adjacency(
        ExactLinearSystem source,
        WorkCounter work
    ) {
        List<List<Integer>> rowToColumns = emptyAdjacency(
            source.equationCount());
        List<List<Integer>> columnToRows = emptyAdjacency(
            source.variableCount());
        for (int row = 0; row < source.equationCount(); row++) {
            for (int column = 0;
                    column < source.variableCount();
                    column++) {
                work.consume();
                if (!source.coefficients().get(row, column).isZero()) {
                    rowToColumns.get(row).add(column);
                    columnToRows.get(column).add(row);
                }
            }
        }
        return new Adjacency(rowToColumns, columnToRows);
    }

    private static List<List<Integer>> emptyAdjacency(int size) {
        List<List<Integer>> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(new ArrayList<>());
        }
        return result;
    }

    private static List<DraftComponent> connectedComponents(
        Adjacency adjacency,
        WorkCounter work
    ) {
        boolean[] seenRows = new boolean[adjacency.rowToColumns().size()];
        boolean[] seenColumns = new boolean[
            adjacency.columnToRows().size()];
        List<DraftComponent> result = new ArrayList<>();
        for (int row = 0; row < seenRows.length; row++) {
            if (!seenRows[row]) {
                result.add(walk(
                    Node.row(row),
                    adjacency,
                    seenRows,
                    seenColumns,
                    work));
            }
        }
        for (int column = 0; column < seenColumns.length; column++) {
            if (!seenColumns[column]) {
                result.add(walk(
                    Node.column(column),
                    adjacency,
                    seenRows,
                    seenColumns,
                    work));
            }
        }
        return List.copyOf(result);
    }

    private static DraftComponent walk(
        Node start,
        Adjacency adjacency,
        boolean[] seenRows,
        boolean[] seenColumns,
        WorkCounter work
    ) {
        List<Integer> rows = new ArrayList<>();
        List<Integer> columns = new ArrayList<>();
        ArrayDeque<Node> pending = new ArrayDeque<>();
        markSeen(start, seenRows, seenColumns);
        pending.add(start);
        while (!pending.isEmpty()) {
            Node current = pending.removeFirst();
            work.consume();
            if (current.row()) {
                rows.add(current.index());
                for (int column : adjacency.rowToColumns()
                        .get(current.index())) {
                    work.consume();
                    if (!seenColumns[column]) {
                        seenColumns[column] = true;
                        pending.addLast(Node.column(column));
                    }
                }
            } else {
                columns.add(current.index());
                for (int row : adjacency.columnToRows()
                        .get(current.index())) {
                    work.consume();
                    if (!seenRows[row]) {
                        seenRows[row] = true;
                        pending.addLast(Node.row(row));
                    }
                }
            }
        }
        rows.sort(Integer::compareTo);
        columns.sort(Integer::compareTo);
        return new DraftComponent(rows, columns);
    }

    private static void markSeen(
        Node node,
        boolean[] seenRows,
        boolean[] seenColumns
    ) {
        if (node.row()) {
            seenRows[node.index()] = true;
        } else {
            seenColumns[node.index()] = true;
        }
    }

    private static List<Component> materialize(
        ExactLinearSystem source,
        List<DraftComponent> drafts,
        WorkCounter work
    ) {
        List<Component> result = new ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            DraftComponent draft = drafts.get(index);
            List<String> variables = new ArrayList<>(
                draft.columns().size());
            for (int column : draft.columns()) {
                work.consume();
                variables.add(source.variables().get(column));
            }
            List<RowOrigin> origins = new ArrayList<>(draft.rows().size());
            boolean contradictory = false;
            for (int row : draft.rows()) {
                work.consume();
                origins.add(source.rowOrigins().get(row));
                if (draft.columns().isEmpty()
                        && !source.rightHandSide().get(row).isZero()) {
                    contradictory = true;
                }
            }
            result.add(new Component(
                "component-" + index,
                draft.rows(),
                draft.columns(),
                variables,
                origins,
                contradictory));
        }
        return List.copyOf(result);
    }

    private static boolean crossComponentCoefficientsAreZero(
        ExactLinearSystem source,
        List<Component> components,
        WorkCounter work
    ) {
        int[] rowComponent = new int[source.equationCount()];
        int[] columnComponent = new int[source.variableCount()];
        Arrays.fill(rowComponent, -1);
        Arrays.fill(columnComponent, -1);
        for (int component = 0;
                component < components.size();
                component++) {
            for (int row : components.get(component).sourceRowIndices()) {
                rowComponent[row] = component;
            }
            for (int column : components.get(component)
                    .sourceColumnIndices()) {
                columnComponent[column] = component;
            }
        }
        for (int row = 0; row < source.equationCount(); row++) {
            for (int column = 0;
                    column < source.variableCount();
                    column++) {
                work.consume();
                Rational value = source.coefficients().get(row, column);
                if (!value.isZero()
                        && rowComponent[row] != columnComponent[column]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<String> capabilities(List<Component> components) {
        Set<String> result = new TreeSet<>();
        result.add(ExactLinearSystemBlockDecomposition
            .CAPABILITY_INDEPENDENT_SUBSYSTEMS);
        for (Component component : components) {
            if (component.kind() == ComponentKind.FREE_VARIABLES) {
                result.add(ExactLinearSystemBlockDecomposition
                    .CAPABILITY_FREE_VARIABLE_COMPONENTS);
            } else if (component.kind()
                    == ComponentKind.CONSTANT_CONSTRAINTS) {
                result.add(ExactLinearSystemBlockDecomposition
                    .CAPABILITY_CONSTANT_CONSTRAINT_COMPONENTS);
            }
            if (component.contradictoryConstantConstraint()) {
                result.add(ExactLinearSystemBlockDecomposition
                    .CAPABILITY_INCONSISTENCY_LOCALIZATION);
            }
        }
        return List.copyOf(result);
    }

    private static Certificate certificate(
        ExactLinearSystem source,
        ExactLinearSystemBlockDecomposition decomposition
    ) {
        String sourceHash = sourceHash(source);
        List<ComponentWitness> witnesses = decomposition.components().stream()
            .map(component -> new ComponentWitness(
                component.id(),
                component.kind(),
                component.sourceRowIndices(),
                component.sourceColumnIndices(),
                component.contradictoryConstantConstraint()))
            .toList();
        StringBuilder payload = new StringBuilder();
        append(payload, CERTIFICATE_SCHEMA);
        append(payload, DECOMPOSER_ID);
        append(payload, RELATION.name());
        append(payload, sourceHash);
        append(payload, Integer.toString(decomposition.sourceRowCount()));
        append(payload, Integer.toString(decomposition.sourceColumnCount()));
        for (ComponentWitness witness : witnesses) {
            append(payload, witness.id());
            append(payload, witness.kind().name());
            appendIndices(payload, witness.sourceRowIndices());
            appendIndices(payload, witness.sourceColumnIndices());
            append(payload, Boolean.toString(
                witness.contradictoryConstantConstraint()));
        }
        appendIndices(payload, decomposition.rowPermutation());
        appendIndices(payload, decomposition.columnPermutation());
        decomposition.unlockedCapabilities().forEach(capability ->
            append(payload, capability));
        append(payload, "crossComponentCoefficientsAreZero=true");
        return new Certificate(
            CERTIFICATE_SCHEMA,
            DECOMPOSER_ID,
            RELATION,
            sourceHash,
            witnesses,
            decomposition.rowPermutation(),
            decomposition.columnPermutation(),
            decomposition.unlockedCapabilities(),
            true,
            sha256(payload.toString()));
    }

    private static String sourceHash(ExactLinearSystem source) {
        StringBuilder payload = new StringBuilder();
        append(payload, Integer.toString(source.equationCount()));
        append(payload, Integer.toString(source.variableCount()));
        source.variables().forEach(variable -> append(payload, variable));
        for (List<Rational> row : source.coefficients().rows()) {
            append(payload, Integer.toString(row.size()));
            row.forEach(value -> append(payload, value.toString()));
        }
        source.rightHandSide().values().forEach(value ->
            append(payload, value.toString()));
        source.rowOrigins().forEach(origin -> {
            append(payload, Integer.toString(origin.sourceIndex()));
            append(payload, origin.sourceEquation());
        });
        append(payload, Integer.toString(source.coefficientRank()));
        append(payload, Integer.toString(source.augmentedRank()));
        append(payload, source.solutionClassification().name());
        return sha256(payload.toString());
    }

    private static void appendIndices(
        StringBuilder payload,
        List<Integer> values
    ) {
        append(payload, Integer.toString(values.size()));
        values.forEach(value -> append(payload, Integer.toString(value)));
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

    public record Certificate(
        String schema,
        String decomposerId,
        Relation relation,
        String sourceHash,
        List<ComponentWitness> components,
        List<Integer> rowPermutation,
        List<Integer> columnPermutation,
        List<String> unlockedCapabilities,
        boolean crossComponentCoefficientsAreZero,
        String contentHash
    ) {
        public Certificate {
            if (schema == null || schema.isBlank()
                    || decomposerId == null || decomposerId.isBlank()
                    || sourceHash == null || sourceHash.isBlank()
                    || contentHash == null || contentHash.isBlank()) {
                throw new IllegalArgumentException(
                    "certificate identities must not be blank");
            }
            relation = Objects.requireNonNull(relation, "relation");
            components = List.copyOf(Objects.requireNonNull(
                components,
                "components"));
            rowPermutation = List.copyOf(Objects.requireNonNull(
                rowPermutation,
                "rowPermutation"));
            columnPermutation = List.copyOf(Objects.requireNonNull(
                columnPermutation,
                "columnPermutation"));
            unlockedCapabilities = List.copyOf(Objects.requireNonNull(
                unlockedCapabilities,
                "unlockedCapabilities"));
            if (components.size() < 2
                    || !crossComponentCoefficientsAreZero) {
                throw new IllegalArgumentException(
                    "certificate must describe a verified decomposition");
            }
        }
    }

    public record ComponentWitness(
        String id,
        ComponentKind kind,
        List<Integer> sourceRowIndices,
        List<Integer> sourceColumnIndices,
        boolean contradictoryConstantConstraint
    ) {
        public ComponentWitness {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("component id is required");
            }
            kind = Objects.requireNonNull(kind, "kind");
            sourceRowIndices = List.copyOf(Objects.requireNonNull(
                sourceRowIndices,
                "sourceRowIndices"));
            sourceColumnIndices = List.copyOf(Objects.requireNonNull(
                sourceColumnIndices,
                "sourceColumnIndices"));
        }
    }

    private record Adjacency(
        List<List<Integer>> rowToColumns,
        List<List<Integer>> columnToRows
    ) {
        private Adjacency {
            rowToColumns = immutableNested(rowToColumns);
            columnToRows = immutableNested(columnToRows);
        }

        private static List<List<Integer>> immutableNested(
            List<List<Integer>> values
        ) {
            return values.stream().map(List::copyOf).toList();
        }
    }

    private record DraftComponent(
        List<Integer> rows,
        List<Integer> columns
    ) {
        private DraftComponent {
            rows = List.copyOf(rows);
            columns = List.copyOf(columns);
            if (rows.isEmpty() && columns.isEmpty()) {
                throw new IllegalArgumentException(
                    "draft component must not be empty");
            }
        }
    }

    private record Node(boolean row, int index) {
        private Node {
            if (index < 0) {
                throw new IllegalArgumentException(
                    "node index must not be negative");
            }
        }

        private static Node row(int index) {
            return new Node(true, index);
        }

        private static Node column(int index) {
            return new Node(false, index);
        }
    }

    private static final class WorkCounter {
        private final int configured;
        private int consumed;

        private WorkCounter(int configured) {
            this.configured = configured;
        }

        private void consume() {
            if (consumed >= configured) {
                throw new BudgetExceeded();
            }
            consumed++;
        }

        private WorkLedger ledger() {
            return WorkLedger.of(configured, consumed);
        }
    }

    private static final class BudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
