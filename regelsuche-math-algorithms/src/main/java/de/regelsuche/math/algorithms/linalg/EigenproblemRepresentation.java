package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Exact finite-dimensional representation of {@code A*v=lambda*v}.
 *
 * <p>The mathematical eigenproblem and a possible physical interpretation are
 * retained separately. A quantum interpretation can appear only when the source
 * request declared a quantum model domain; symbol names such as {@code H} or
 * {@code psi} have no interpretive authority.</p>
 */
public record EigenproblemRepresentation(
    PolynomialMatrix operator,
    PolynomialMatrix shiftedOperator,
    List<String> vectorCoordinates,
    String eigenvalueParameter,
    List<String> scalarParameters,
    List<String> requiredAssumptions,
    ModelInterpretation modelInterpretation,
    Set<OperatorProperty> declaredOperatorProperties,
    List<String> unlockedCapabilities
) {
    public static final String CAPABILITY_EIGENPROBLEM_RECOGNIZED =
        "EIGENVALUE_PROBLEM_RECOGNIZED";
    public static final String CAPABILITY_CHARACTERISTIC_POLYNOMIAL =
        "CHARACTERISTIC_POLYNOMIAL_AVAILABLE";
    public static final String CAPABILITY_SINGULAR_SHIFTED_OPERATOR =
        "SINGULAR_SHIFTED_OPERATOR_CONDITION";
    public static final String CAPABILITY_QUANTUM_OPERATOR_MODEL =
        "QUANTUM_OPERATOR_MODEL_INTERPRETATION";
    public static final String CAPABILITY_HERMITIAN_SPECTRAL_MODEL =
        "HERMITIAN_SPECTRAL_MODEL";

    public EigenproblemRepresentation {
        operator = Objects.requireNonNull(operator, "operator");
        shiftedOperator = Objects.requireNonNull(
            shiftedOperator,
            "shiftedOperator");
        vectorCoordinates = names(
            vectorCoordinates,
            "vectorCoordinates",
            false);
        if (eigenvalueParameter == null
                || eigenvalueParameter.isBlank()) {
            throw new IllegalArgumentException(
                "eigenvalueParameter must not be blank");
        }
        eigenvalueParameter = eigenvalueParameter.trim();
        scalarParameters = names(
            scalarParameters,
            "scalarParameters",
            true);
        requiredAssumptions = texts(
            requiredAssumptions,
            "requiredAssumptions",
            false);
        modelInterpretation = Objects.requireNonNull(
            modelInterpretation,
            "modelInterpretation");
        declaredOperatorProperties = Set.copyOf(Objects.requireNonNull(
            declaredOperatorProperties,
            "declaredOperatorProperties"));
        unlockedCapabilities = texts(
            unlockedCapabilities,
            "unlockedCapabilities",
            false);

        int dimension = vectorCoordinates.size();
        if (operator.rows() != dimension
                || operator.columns() != dimension
                || shiftedOperator.rows() != dimension
                || shiftedOperator.columns() != dimension) {
            throw new IllegalArgumentException(
                "operator dimensions must match vector coordinates");
        }
        if (vectorCoordinates.contains(eigenvalueParameter)) {
            throw new IllegalArgumentException(
                "eigenvalue parameter cannot be a vector coordinate");
        }
        if (!scalarParameters.contains(eigenvalueParameter)) {
            throw new IllegalArgumentException(
                "eigenvalue parameter must be retained as scalar parameter");
        }
        if (!requiredAssumptions.contains("vector != 0")) {
            throw new IllegalArgumentException(
                "eigenproblem requires a non-zero-vector assumption");
        }
        requireCapability(CAPABILITY_EIGENPROBLEM_RECOGNIZED, true);
        requireCapability(CAPABILITY_CHARACTERISTIC_POLYNOMIAL, true);
        requireCapability(CAPABILITY_SINGULAR_SHIFTED_OPERATOR, true);
        boolean quantum = modelInterpretation != ModelInterpretation.NONE;
        requireCapability(CAPABILITY_QUANTUM_OPERATOR_MODEL, quantum);
        boolean hermitian = declaredOperatorProperties.contains(
            OperatorProperty.HERMITIAN);
        requireCapability(CAPABILITY_HERMITIAN_SPECTRAL_MODEL,
            quantum && hermitian);
        if (modelInterpretation
                == ModelInterpretation.HERMITIAN_QUANTUM_OBSERVABLE
                && !hermitian) {
            throw new IllegalArgumentException(
                "Hermitian interpretation requires declared Hermiticity");
        }
        if (modelInterpretation
                == ModelInterpretation.QUANTUM_OPERATOR
                && hermitian) {
            throw new IllegalArgumentException(
                "Hermitian quantum operator needs the stronger interpretation");
        }
    }

    public int dimension() {
        return vectorCoordinates.size();
    }

    private void requireCapability(String capability, boolean required) {
        if (unlockedCapabilities.contains(capability) != required) {
            throw new IllegalArgumentException(
                "capability disagrees with eigenproblem evidence: "
                    + capability);
        }
    }

    private static List<String> names(
        List<String> values,
        String field,
        boolean allowEmpty
    ) {
        List<String> retained = texts(values, field, allowEmpty);
        if (new HashSet<>(retained).size() != retained.size()) {
            throw new IllegalArgumentException(
                field + " entries must be unique");
        }
        return retained;
    }

    private static List<String> texts(
        List<String> values,
        String field,
        boolean allowEmpty
    ) {
        Objects.requireNonNull(values, field);
        if (!allowEmpty && values.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return values.stream().map(value -> {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                    field + " entries must not be blank");
            }
            return value.trim();
        }).toList();
    }

    public enum ModelDomain {
        GENERIC_LINEAR_ALGEBRA,
        FINITE_DIMENSIONAL_QUANTUM
    }

    public enum ModelInterpretation {
        NONE,
        QUANTUM_OPERATOR,
        HERMITIAN_QUANTUM_OBSERVABLE
    }

    public enum OperatorProperty {
        HERMITIAN,
        UNITARY,
        NORMAL
    }
}
