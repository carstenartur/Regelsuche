package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.equivalence.PolynomialEquivalenceService;
import de.regelsuche.math.algorithms.equivalence.PolynomialArithmetic.LinearEquation;
import de.regelsuche.math.algorithms.equivalence.PolynomialArithmetic.Polynomial;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic polynomial normal-form equivalence for direct polynomial identities.
 *
 * <p>This service expands and canonicalizes supported polynomial expressions. It is not a Gröbner-basis
 * implementation and does not decide ideal membership or polynomial systems.
 */
public class PolynomialNormalFormEquivalenceService implements PolynomialEquivalenceService {
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
        if (!isEnabled()) {
            lastResult = MathematicalAlgorithmRegistry.AlgorithmExecutionResult.disabled(
                "polynomialEquivalence must be enabled for normal-form identity checks");
            return false;
        }

        Optional<Polynomial> left = parsePolynomial(leftPolynomial);
        Optional<Polynomial> right = parsePolynomial(rightPolynomial);
        if (left.isEmpty() || right.isEmpty()) {
            lastResult = MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unknown(
                "unsupported non-polynomial expression domain");
            return false;
        }

        boolean equal = left.orElseThrow().equals(right.orElseThrow());
        lastResult = new MathematicalAlgorithmRegistry.AlgorithmExecutionResult(
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
        return equal;
    }

    @Override
    public String evidence(String leftExpression, String rightExpression) {
        arePolynomiallyEquivalent(leftExpression, rightExpression);
        return lastResult.detail();
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
