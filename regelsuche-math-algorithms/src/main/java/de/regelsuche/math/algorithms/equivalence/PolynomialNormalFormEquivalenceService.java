package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.equivalence.PolynomialEquivalenceService;
import de.regelsuche.math.algorithms.equivalence.PolynomialArithmetic.LinearEquation;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import de.regelsuche.validation.OracleValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic polynomial normal-form equivalence for direct polynomial identities.
 *
 * <p>This service expands and canonicalizes direct polynomial identities over the rationals.
 * Nonzero constant denominators become exact rational coefficients.
 */
public class PolynomialNormalFormEquivalenceService implements PolynomialEquivalenceService, OracleValidator {
    private final PolynomialArithmetic arithmetic = new PolynomialArithmetic();
    private final MathematicalAlgorithmRegistry registry;
    private MathematicalAlgorithmRegistry.AlgorithmExecutionResult lastResult =
        MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unknown("not executed");

    public PolynomialNormalFormEquivalenceService(MathematicalAlgorithmRegistry registry) {
        this.registry = registry;
    }

    public MathematicalAlgorithmRegistry.AlgorithmExecutionResult lastResult() {
        return lastResult;
    }

    @Override
    public boolean arePolynomiallyEquivalent(String leftPolynomial, String rightPolynomial) {
        return validateEquivalence(leftPolynomial, rightPolynomial).status() == OracleValidationStatus.AGREE;
    }

    @Override
    public OracleValidation validateEquivalence(String leftPolynomial, String rightPolynomial) {
        var decision = comparePolynomial(leftPolynomial, rightPolynomial);
        lastResult = decision;
        return switch (decision.resultType()) {
            case PROOF -> OracleValidation.agrees(decision.detail());
            case REFUTATION -> OracleValidation.disagrees(decision.detail());
            default -> OracleValidation.unavailable(decision.detail());
        };
    }

    private MathematicalAlgorithmRegistry.AlgorithmExecutionResult comparePolynomial(
        String leftPolynomial, String rightPolynomial
    ) {
        if (!isEnabled()) {
            return MathematicalAlgorithmRegistry.AlgorithmExecutionResult.disabled(
                "polynomialEquivalence must be enabled for normal-form identity checks");
        }

        Optional<Polynomial> left = parsePolynomial(leftPolynomial);
        Optional<Polynomial> right = parsePolynomial(rightPolynomial);
        if (left.isEmpty() || right.isEmpty()) {
            return MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unknown(
                "unsupported non-polynomial expression domain");
        }

        boolean equal = left.orElseThrow().equals(right.orElseThrow());
        return new MathematicalAlgorithmRegistry.AlgorithmExecutionResult(
            MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS,
            equal ? MathematicalAlgorithmRegistry.ResultType.PROOF : MathematicalAlgorithmRegistry.ResultType.REFUTATION,
            equal ? "matching deterministic polynomial normal form" : "normal forms differ",
            Map.of(
                "capability", MathematicalAlgorithmRegistry.POLYNOMIAL_EQUIVALENCE,
                "domain", "direct polynomial identities, not ideals or systems",
                "leftNormalForm", left.orElseThrow().toCanonicalString(),
                "rightNormalForm", right.orElseThrow().toCanonicalString()
            )
        );
    }

    @Override
    public String evidence(String leftExpression, String rightExpression) {
        return validateEquivalence(leftExpression, rightExpression).evidence();
    }

    public Optional<String> normalForm(String polynomialExpression) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        return parsePolynomial(polynomialExpression).map(Polynomial::toCanonicalString);
    }

    public List<String> eliminateLinearVariable(List<String> equationsEqualZero, String variable) {
        if (!isEnabled() || equationsEqualZero == null || equationsEqualZero.isEmpty() || variable == null || variable.isBlank()) {
            return List.of();
        }
        List<Polynomial> parsed = new ArrayList<>();
        for (String equation : equationsEqualZero) {
            Optional<Polynomial> polynomial = parsePolynomial(equation);
            if (polynomial.isEmpty()) {
                return List.of();
            }
            parsed.add(polynomial.orElseThrow());
        }

        Optional<LinearEquation> pivot = parsed.stream()
            .map(polynomial -> polynomial.isolateLinear(variable))
            .filter(Optional::isPresent)
            .map(Optional::orElseThrow)
            .findFirst();
        if (pivot.isEmpty()) {
            return List.of();
        }
        LinearEquation pivotEquation = pivot.orElseThrow();

        List<String> result = new ArrayList<>();
        for (Polynomial equation : parsed) {
            Optional<LinearEquation> linear = equation.isolateLinear(variable);
            Polynomial eliminated;
            if (linear.isPresent()) {
                LinearEquation current = linear.orElseThrow();
                eliminated = current.rest().multiply(pivotEquation.coefficient())
                    .subtract(pivotEquation.rest().multiply(current.coefficient()));
            } else {
                eliminated = equation;
            }
            result.add(eliminated.toCanonicalString());
        }
        return result;
    }

    private boolean isEnabled() {
        return registry.isEnabled(MathematicalAlgorithmRegistry.POLYNOMIAL_EQUIVALENCE);
    }

    private Optional<Polynomial> parsePolynomial(String expression) {
        return arithmetic.parse(expression);
    }
}
