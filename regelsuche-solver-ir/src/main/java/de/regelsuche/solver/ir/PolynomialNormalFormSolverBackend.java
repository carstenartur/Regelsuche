package de.regelsuche.solver.ir;

import de.regelsuche.math.algorithms.equivalence.PolynomialNormalFormEquivalenceService;
import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.Binary;
import de.regelsuche.solver.ir.SolverIr.Expression;
import de.regelsuche.solver.ir.SolverIr.Literal;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import de.regelsuche.solver.ir.SolverIr.Theory;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.validation.MathematicalAlgorithmRegistry.ExecutionStatus;
import de.regelsuche.validation.MathematicalAlgorithmRegistry.ResultType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Exact polynomial-identity backend over deterministic rational normal forms. */
public final class PolynomialNormalFormSolverBackend implements SolverBackend {
    private static final BackendDescriptor DESCRIPTOR = new BackendDescriptor(
        "polynomial-normal-form",
        "1",
        List.of(Theory.REAL_ARITHMETIC),
        List.of(Relation.EQUALS),
        List.of(RequestedEvidence.DECISION,
            RequestedEvidence.SYMBOLIC_CERTIFICATE),
        true);

    private final CoreExpressionIrAdapter expressions = new CoreExpressionIrAdapter();
    private final PolynomialNormalFormEquivalenceService service =
        new PolynomialNormalFormEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry());

    @Override
    public BackendDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public SolverExecution execute(Obligation obligation) {
        Objects.requireNonNull(obligation, "obligation");
        String left = expressions.render(obligation.goal().left());
        String right = expressions.render(obligation.goal().right());
        Map<String, String> terms = Map.of(
            "goal.left", left,
            "goal.right", right);
        List<String> issues = new ArrayList<>(SolverBackendSupport.issues(
            obligation, DESCRIPTOR, false));
        if (!isPolynomial(obligation.goal().left())
                || !isPolynomial(obligation.goal().right())) {
            issues.add("UNSUPPORTED_EXPRESSION_FRAGMENT:POLYNOMIAL_ONLY");
        }
        issues = issues.stream().distinct().sorted().toList();
        if (!issues.isEmpty()) {
            return SolverBackendSupport.rejectedExecution(
                obligation, DESCRIPTOR, issues, terms);
        }

        SolverTranslation translation = SolverTranslation.create(
            obligation,
            DESCRIPTOR,
            TranslationStatus.LOSSLESS,
            List.of(),
            terms);
        SolverResult result;
        try {
            service.arePolynomiallyEquivalent(left, right);
            var evidence = service.lastResult();
            if (evidence.status() == ExecutionStatus.DISABLED
                    || evidence.status() == ExecutionStatus.UNAVAILABLE) {
                result = SolverResult.create(
                    obligation,
                    DESCRIPTOR,
                    ResultStatus.ERROR,
                    TranslationStatus.LOSSLESS,
                    List.of("DETERMINISTIC_POLYNOMIAL_NORMAL_FORM"),
                    List.of(),
                    "backend " + evidence.status().name().toLowerCase(),
                    Map.of(),
                    "");
            } else {
                ResultStatus status = status(
                    evidence.status(), evidence.resultType());
                String certificate = status == ResultStatus.CONFIRMED
                        || status == ResultStatus.REFUTED
                    ? SolverIr.sha256(canonicalPayload(evidence.payload()))
                    : "";
                result = SolverResult.create(
                    obligation,
                    DESCRIPTOR,
                    status,
                    TranslationStatus.LOSSLESS,
                    List.of(
                        "DETERMINISTIC_POLYNOMIAL_NORMAL_FORM",
                        "EXACT_RATIONAL_COEFFICIENTS"),
                    List.of(),
                    evidence.detail(),
                    Map.of(),
                    certificate);
            }
        } catch (RuntimeException exception) {
            result = SolverResult.create(
                obligation,
                DESCRIPTOR,
                ResultStatus.ERROR,
                TranslationStatus.LOSSLESS,
                List.of("DETERMINISTIC_POLYNOMIAL_NORMAL_FORM"),
                List.of(),
                exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                Map.of(),
                "");
        }
        return SolverExecution.create(obligation, translation, result);
    }

    private static String canonicalPayload(Map<String, Object> payload) {
        TreeMap<String, String> ordered = new TreeMap<>();
        payload.forEach((key, value) -> ordered.put(
            key, value == null ? "" : value.toString()));
        return ordered.entrySet().stream()
            .map(entry -> entry.getKey() + '=' + entry.getValue())
            .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static ResultStatus status(
        ExecutionStatus executionStatus,
        ResultType resultType
    ) {
        if (executionStatus != ExecutionStatus.SUCCESS) {
            return switch (executionStatus) {
                case BUDGET_EXHAUSTED -> ResultStatus.TIMEOUT;
                case UNKNOWN -> ResultStatus.UNKNOWN;
                case DISABLED, UNAVAILABLE, SUCCESS -> throw new IllegalStateException(
                    "execution status must be handled before result classification");
            };
        }
        return switch (resultType) {
            case PROOF -> ResultStatus.CONFIRMED;
            case REFUTATION -> ResultStatus.REFUTED;
            case HYPOTHESIS, DIAGNOSTIC -> ResultStatus.UNKNOWN;
        };
    }

    private static boolean isPolynomial(Expression expression) {
        if (expression instanceof Literal || expression instanceof SolverIr.Symbol) {
            return true;
        }
        if (!(expression instanceof Binary binary)) {
            return false;
        }
        if (!isPolynomial(binary.left()) || !isPolynomial(binary.right())) {
            return false;
        }
        return switch (binary.operator()) {
            case ADD, SUBTRACT, MULTIPLY -> true;
            case DIVIDE -> false;
            case POWER -> nonNegativeIntegerExponent(binary.right());
        };
    }

    private static boolean nonNegativeIntegerExponent(Expression expression) {
        if (!(expression instanceof Literal literal)) {
            return false;
        }
        try {
            BigDecimal value = new BigDecimal(literal.value()).stripTrailingZeros();
            return value.scale() <= 0
                && value.signum() >= 0
                && value.compareTo(BigDecimal.valueOf(20)) <= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
