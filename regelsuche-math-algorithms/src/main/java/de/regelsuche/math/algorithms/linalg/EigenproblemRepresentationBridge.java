package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.linalg.EigenproblemRepresentation.ModelDomain;
import de.regelsuche.math.algorithms.linalg.EigenproblemRepresentation.ModelInterpretation;
import de.regelsuche.math.algorithms.linalg.EigenproblemRepresentation.OperatorProperty;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.representation.RepresentationBridge;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Recognizes the exact matrix pattern {@code A*v=lambda*v} from a symbolic
 * homogeneous linear system.
 *
 * <p>The bridge requires declared unknown-vector coordinates and an explicit
 * eigenvalue parameter from the upstream symbolic-system bridge. A non-zero
 * vector assumption is mandatory. Quantum meaning is retained only as declared
 * model metadata when the request explicitly names a finite-dimensional quantum
 * domain; it is not promoted to a proved physical fact.</p>
 */
public final class EigenproblemRepresentationBridge implements
        RepresentationBridge<
            EigenproblemRepresentationBridge.Source,
            EigenproblemRepresentation,
            EigenproblemRepresentationBridge.Certificate> {

    public static final String BRIDGE_ID =
        "symbolic-linear-system-to-eigenproblem/v1";
    public static final String CERTIFICATE_SCHEMA =
        "regelsuche.eigenproblem-representation-certificate/v1";
    private static final Relation RELATION =
        Relation.LINEAR_MAP_REPRESENTATION_EQUIVALENCE;

    @Override
    public Result<EigenproblemRepresentation, Certificate> analyze(
        Source source,
        Budget budget
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(budget, "budget");
        WorkCounter work = new WorkCounter(budget.maxWorkUnits());
        try {
            SymbolicLinearSystem system = source.system();
            if (!source.nonZeroVectorAssumed()) {
                return Result.withoutRepresentation(
                    Status.ASSUMPTION_REQUIRED,
                    work.ledger(),
                    "NON_ZERO_EIGENVECTOR_REQUIRED");
            }
            if (system.equationCount() != system.unknownCount()) {
                return Result.withoutRepresentation(
                    Status.NOT_APPLICABLE,
                    work.ledger(),
                    "EIGENPROBLEM_REQUIRES_SQUARE_SYSTEM");
            }
            if (!system.homogeneous()) {
                return Result.withoutRepresentation(
                    Status.NOT_APPLICABLE,
                    work.ledger(),
                    "EIGENPROBLEM_REQUIRES_HOMOGENEOUS_SYSTEM");
            }
            if (!system.scalarParameters().contains(
                    source.eigenvalueParameter())) {
                return Result.withoutRepresentation(
                    Status.NOT_APPLICABLE,
                    work.ledger(),
                    "EIGENVALUE_PARAMETER_NOT_PRESENT");
            }

            Polynomial eigenvalue = Polynomial.variable(
                source.eigenvalueParameter());
            List<List<Polynomial>> operatorRows = new ArrayList<>(
                system.equationCount());
            for (int row = 0; row < system.equationCount(); row++) {
                List<Polynomial> operatorRow = new ArrayList<>(
                    system.unknownCount());
                for (int column = 0;
                        column < system.unknownCount();
                        column++) {
                    work.consume();
                    Polynomial shifted = system.coefficients().get(
                        row,
                        column);
                    Polynomial operatorEntry;
                    if (row == column) {
                        operatorEntry = shifted.add(eigenvalue);
                        if (operatorEntry.variables().contains(
                                source.eigenvalueParameter())) {
                            return Result.withoutRepresentation(
                                Status.NOT_APPLICABLE,
                                work.ledger(),
                                "DIAGONAL_IS_NOT_OPERATOR_MINUS_EIGENVALUE");
                        }
                    } else {
                        if (shifted.variables().contains(
                                source.eigenvalueParameter())) {
                            return Result.withoutRepresentation(
                                Status.NOT_APPLICABLE,
                                work.ledger(),
                                "EIGENVALUE_PARAMETER_OCCURS_OFF_DIAGONAL");
                        }
                        operatorEntry = shifted;
                    }
                    operatorRow.add(operatorEntry);
                }
                operatorRows.add(List.copyOf(operatorRow));
            }

            ModelInterpretation interpretation = interpretation(source);
            List<String> capabilities = capabilities(
                interpretation,
                source.operatorProperties());
            EigenproblemRepresentation representation =
                new EigenproblemRepresentation(
                    new PolynomialMatrix(operatorRows),
                    system.coefficients(),
                    system.unknowns(),
                    source.eigenvalueParameter(),
                    system.scalarParameters(),
                    List.of("vector != 0"),
                    interpretation,
                    source.operatorProperties(),
                    capabilities);
            if (!shiftedOperatorMatches(representation, work)) {
                return Result.withoutRepresentation(
                    Status.INVALID_CERTIFICATE,
                    work.ledger(),
                    "SHIFTED_OPERATOR_RECONSTRUCTION_MISMATCH");
            }
            Certificate certificate = certificate(source, representation);
            return Result.represented(
                representation,
                certificate,
                RELATION,
                work.ledger(),
                "FINITE_DIMENSIONAL_EIGENPROBLEM_RECOGNIZED");
        } catch (BudgetExceeded exception) {
            return Result.withoutRepresentation(
                Status.BUDGET_INCONCLUSIVE,
                work.ledger(),
                "EIGENPROBLEM_RECOGNITION_BUDGET_EXHAUSTED");
        }
    }

    @Override
    public boolean verify(
        Source source,
        Result<EigenproblemRepresentation, Certificate> result
    ) {
        if (source == null
                || result == null
                || result.status() != Status.REPRESENTED
                || result.relation().orElse(null) != RELATION) {
            return false;
        }
        try {
            return analyze(
                source,
                new Budget(result.work().configuredWorkUnits()))
                .equals(result);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean shiftedOperatorMatches(
        EigenproblemRepresentation representation,
        WorkCounter work
    ) {
        Polynomial eigenvalue = Polynomial.variable(
            representation.eigenvalueParameter());
        for (int row = 0; row < representation.dimension(); row++) {
            for (int column = 0;
                    column < representation.dimension();
                    column++) {
                work.consume();
                Polynomial expected = representation.operator().get(
                    row,
                    column);
                if (row == column) {
                    expected = expected.subtract(eigenvalue);
                }
                if (!expected.equals(representation.shiftedOperator().get(
                        row,
                        column))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static ModelInterpretation interpretation(Source source) {
        if (source.modelDomain() == ModelDomain.GENERIC_LINEAR_ALGEBRA) {
            return ModelInterpretation.NONE;
        }
        return source.operatorProperties().contains(OperatorProperty.HERMITIAN)
            ? ModelInterpretation.DECLARED_HERMITIAN_QUANTUM_OBSERVABLE
            : ModelInterpretation.DECLARED_QUANTUM_OPERATOR;
    }

    private static List<String> capabilities(
        ModelInterpretation interpretation,
        Set<OperatorProperty> properties
    ) {
        Set<String> result = new TreeSet<>();
        result.add(EigenproblemRepresentation
            .CAPABILITY_EIGENPROBLEM_RECOGNIZED);
        result.add(EigenproblemRepresentation
            .CAPABILITY_CHARACTERISTIC_POLYNOMIAL);
        result.add(EigenproblemRepresentation
            .CAPABILITY_SINGULAR_SHIFTED_OPERATOR);
        if (interpretation != ModelInterpretation.NONE) {
            result.add(EigenproblemRepresentation
                .CAPABILITY_QUANTUM_OPERATOR_MODEL);
        }
        if (interpretation
                == ModelInterpretation
                    .DECLARED_HERMITIAN_QUANTUM_OBSERVABLE
                && properties.contains(OperatorProperty.HERMITIAN)) {
            result.add(EigenproblemRepresentation
                .CAPABILITY_HERMITIAN_SPECTRAL_MODEL);
        }
        return List.copyOf(result);
    }

    private static Certificate certificate(
        Source source,
        EigenproblemRepresentation representation
    ) {
        String sourceHash = symbolicSystemHash(source.system());
        List<List<String>> operatorRows = canonicalRows(
            representation.operator());
        List<List<String>> shiftedRows = canonicalRows(
            representation.shiftedOperator());
        StringBuilder payload = new StringBuilder();
        append(payload, CERTIFICATE_SCHEMA);
        append(payload, BRIDGE_ID);
        append(payload, RELATION.name());
        append(payload, sourceHash);
        append(payload, representation.eigenvalueParameter());
        append(payload, Integer.toString(representation.dimension()));
        representation.vectorCoordinates().forEach(value ->
            append(payload, value));
        for (List<String> row : operatorRows) {
            append(payload, Integer.toString(row.size()));
            row.forEach(value -> append(payload, value));
        }
        for (List<String> row : shiftedRows) {
            append(payload, Integer.toString(row.size()));
            row.forEach(value -> append(payload, value));
        }
        append(payload, representation.modelInterpretation().name());
        representation.declaredOperatorProperties().stream()
            .map(Enum::name)
            .sorted()
            .forEach(value -> append(payload, value));
        representation.requiredAssumptions().forEach(value ->
            append(payload, value));
        representation.unlockedCapabilities().forEach(value ->
            append(payload, value));
        return new Certificate(
            CERTIFICATE_SCHEMA,
            BRIDGE_ID,
            RELATION,
            sourceHash,
            representation.eigenvalueParameter(),
            representation.vectorCoordinates(),
            operatorRows,
            shiftedRows,
            representation.requiredAssumptions(),
            representation.modelInterpretation(),
            source.modelDomain(),
            source.operatorProperties(),
            representation.unlockedCapabilities(),
            sha256(payload.toString()));
    }

    private static String symbolicSystemHash(SymbolicLinearSystem system) {
        StringBuilder payload = new StringBuilder();
        append(payload, Integer.toString(system.equationCount()));
        append(payload, Integer.toString(system.unknownCount()));
        system.unknowns().forEach(value -> append(payload, value));
        system.scalarParameters().forEach(value -> append(payload, value));
        for (List<String> row : canonicalRows(system.coefficients())) {
            append(payload, Integer.toString(row.size()));
            row.forEach(value -> append(payload, value));
        }
        system.rightHandSide().values().stream()
            .map(Polynomial::toCanonicalString)
            .forEach(value -> append(payload, value));
        system.rowOrigins().forEach(origin -> {
            append(payload, Integer.toString(origin.sourceIndex()));
            append(payload, origin.sourceEquation());
        });
        return sha256(payload.toString());
    }

    private static List<List<String>> canonicalRows(PolynomialMatrix matrix) {
        return matrix.entries().stream()
            .map(row -> row.stream()
                .map(Polynomial::toCanonicalString)
                .toList())
            .toList();
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

    public record Source(
        SymbolicLinearSystem system,
        String eigenvalueParameter,
        boolean nonZeroVectorAssumed,
        ModelDomain modelDomain,
        Set<OperatorProperty> operatorProperties
    ) {
        public Source {
            system = Objects.requireNonNull(system, "system");
            if (eigenvalueParameter == null
                    || eigenvalueParameter.isBlank()) {
                throw new IllegalArgumentException(
                    "eigenvalueParameter must not be blank");
            }
            eigenvalueParameter = eigenvalueParameter.trim();
            modelDomain = Objects.requireNonNull(
                modelDomain,
                "modelDomain");
            operatorProperties = Set.copyOf(Objects.requireNonNull(
                operatorProperties,
                "operatorProperties"));
            if (system.unknowns().contains(eigenvalueParameter)) {
                throw new IllegalArgumentException(
                    "eigenvalue parameter cannot be an unknown coordinate");
            }
        }
    }

    public record Certificate(
        String schema,
        String bridgeId,
        Relation relation,
        String sourceSystemHash,
        String eigenvalueParameter,
        List<String> vectorCoordinates,
        List<List<String>> operatorRows,
        List<List<String>> shiftedOperatorRows,
        List<String> requiredAssumptions,
        ModelInterpretation modelInterpretation,
        ModelDomain declaredModelDomain,
        Set<OperatorProperty> declaredOperatorProperties,
        List<String> unlockedCapabilities,
        String contentHash
    ) {
        public Certificate {
            if (schema == null || schema.isBlank()
                    || bridgeId == null || bridgeId.isBlank()
                    || sourceSystemHash == null || sourceSystemHash.isBlank()
                    || eigenvalueParameter == null
                    || eigenvalueParameter.isBlank()
                    || contentHash == null || contentHash.isBlank()) {
                throw new IllegalArgumentException(
                    "certificate identities must not be blank");
            }
            relation = Objects.requireNonNull(relation, "relation");
            vectorCoordinates = List.copyOf(Objects.requireNonNull(
                vectorCoordinates,
                "vectorCoordinates"));
            operatorRows = immutableRows(operatorRows, "operatorRows");
            shiftedOperatorRows = immutableRows(
                shiftedOperatorRows,
                "shiftedOperatorRows");
            requiredAssumptions = List.copyOf(Objects.requireNonNull(
                requiredAssumptions,
                "requiredAssumptions"));
            modelInterpretation = Objects.requireNonNull(
                modelInterpretation,
                "modelInterpretation");
            declaredModelDomain = Objects.requireNonNull(
                declaredModelDomain,
                "declaredModelDomain");
            declaredOperatorProperties = Set.copyOf(Objects.requireNonNull(
                declaredOperatorProperties,
                "declaredOperatorProperties"));
            unlockedCapabilities = List.copyOf(Objects.requireNonNull(
                unlockedCapabilities,
                "unlockedCapabilities"));
            int dimension = vectorCoordinates.size();
            if (dimension < 1
                    || operatorRows.size() != dimension
                    || shiftedOperatorRows.size() != dimension
                    || operatorRows.stream()
                        .anyMatch(row -> row.size() != dimension)
                    || shiftedOperatorRows.stream()
                        .anyMatch(row -> row.size() != dimension)) {
                throw new IllegalArgumentException(
                    "certificate operator dimensions are inconsistent");
            }
            if (modelInterpretation == ModelInterpretation.NONE
                    && declaredModelDomain
                        != ModelDomain.GENERIC_LINEAR_ALGEBRA) {
                throw new IllegalArgumentException(
                    "missing declared interpretation for quantum domain");
            }
            if (modelInterpretation != ModelInterpretation.NONE
                    && declaredModelDomain
                        != ModelDomain.FINITE_DIMENSIONAL_QUANTUM) {
                throw new IllegalArgumentException(
                    "declared quantum interpretation requires quantum domain");
            }
        }

        private static List<List<String>> immutableRows(
            List<List<String>> rows,
            String field
        ) {
            Objects.requireNonNull(rows, field);
            return rows.stream().map(row -> {
                Objects.requireNonNull(row, field + " row");
                return row.stream().map(value -> {
                    if (value == null || value.isBlank()) {
                        throw new IllegalArgumentException(
                            field + " entries must not be blank");
                    }
                    return value;
                }).toList();
            }).toList();
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
