package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.equivalence.PolynomialEquivalenceService;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Gröbner-basis service for small polynomial ideals/systems over exact rational coefficients.
 *
 * <p>This class is intentionally separate from {@link PolynomialNormalFormEquivalenceService}: normal forms prove
 * direct polynomial identities, while this service computes a basis for generators and reduces a polynomial modulo
 * the generated ideal. If a concrete external backend such as JAS is requested but unavailable, the service reports
 * {@code UNAVAILABLE} instead of falling back to normal-form equivalence.
 */
public class GroebnerBasisEquivalenceService implements PolynomialEquivalenceService {
    private static final MathematicalAlgorithmRegistry.AlgorithmBudget SAFE_DEFAULT_BUDGET =
        MathematicalAlgorithmRegistry.AlgorithmBudget.bounded(200, 1_000, 0, 0.0, 256, 20, 8);
    private final PolynomialArithmetic arithmetic = new PolynomialArithmetic();
    private final MathematicalAlgorithmRegistry registry;
    private final boolean jasBackendAvailable;
    private final MonomialOrder monomialOrder;
    private MathematicalAlgorithmRegistry.AlgorithmExecutionResult lastResult =
        MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unknown("not executed");

    public GroebnerBasisEquivalenceService(MathematicalAlgorithmRegistry registry) {
        this(registry, false);
    }

    public GroebnerBasisEquivalenceService(MathematicalAlgorithmRegistry registry, boolean jasBackendAvailable) {
        this(registry, jasBackendAvailable, new GradedReverseLexOrder());
    }

    public GroebnerBasisEquivalenceService(
        MathematicalAlgorithmRegistry registry,
        boolean jasBackendAvailable,
        MonomialOrder monomialOrder
    ) {
        this.registry = registry;
        this.jasBackendAvailable = jasBackendAvailable;
        this.monomialOrder = monomialOrder;
    }

    public MathematicalAlgorithmRegistry.AlgorithmExecutionResult lastResult() {
        return lastResult;
    }

    @Override
    public boolean arePolynomiallyEquivalent(String leftPolynomial, String rightPolynomial) {
        lastResult = MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unknown(
            "Gröbner equivalence requires explicit ideal generators; use reducesToZeroModuloIdeal");
        return false;
    }

    @Override
    public String evidence(String leftExpression, String rightExpression) {
        arePolynomiallyEquivalent(leftExpression, rightExpression);
        return lastResult.detail();
    }

    public boolean reducesToZeroModuloIdeal(String polynomialExpression, List<String> generatorExpressions) {
        Optional<String> remainder = normalFormModuloIdeal(polynomialExpression, generatorExpressions);
        return remainder.isPresent() && "0".equals(remainder.orElseThrow());
    }

    public Optional<String> normalFormModuloIdeal(String polynomialExpression, List<String> generatorExpressions) {
        if (!registry.isEnabled(MathematicalAlgorithmRegistry.GROEBNER_BASIS)) {
            lastResult = MathematicalAlgorithmRegistry.AlgorithmExecutionResult.disabled(
                "groebnerBasis must be enabled for ideal reduction");
            return Optional.empty();
        }
        if (registry.isEnabled(MathematicalAlgorithmRegistry.JAS_BACKEND) && !jasBackendAvailable) {
            lastResult = MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unavailable(
                "jasBackend is enabled, but no JAS Gröbner adapter is available");
            return Optional.empty();
        }
        if (generatorExpressions == null || generatorExpressions.isEmpty()) {
            lastResult = unsupported("ideal reduction requires at least one generator", "missing-generators");
            return Optional.empty();
        }

        Optional<Polynomial> polynomial = arithmetic.parse(polynomialExpression);
        if (polynomial.isEmpty()) {
            lastResult = unsupported("unsupported polynomial expression", "unsupported-polynomial-syntax");
            return Optional.empty();
        }

        List<Polynomial> generators = new ArrayList<>();
        for (String generatorExpression : generatorExpressions) {
            Optional<Polynomial> generator = arithmetic.parse(generatorExpression);
            if (generator.isEmpty()) {
                lastResult = unsupported("unsupported ideal generator", "unsupported-generator-syntax");
                return Optional.empty();
            }
            if (!generator.orElseThrow().isZero()) {
                generators.add(generator.orElseThrow());
            }
        }
        if (generators.isEmpty()) {
            lastResult = unsupported("ideal generators reduce to zero", "zero-generators");
            return Optional.empty();
        }

        MathematicalAlgorithmRegistry.AlgorithmBudget budget = registry.find(MathematicalAlgorithmRegistry.GROEBNER_BASIS)
            .map(MathematicalAlgorithmRegistry.AlgorithmDescriptor::budget)
            .orElse(SAFE_DEFAULT_BUDGET);
        Optional<String> unsupportedReason = unsupportedByHardLimits(polynomial.orElseThrow(), generators, budget);
        if (unsupportedReason.isPresent()) {
            lastResult = unsupported("polynomial outside configured Gröbner limits", unsupportedReason.orElseThrow());
            return Optional.empty();
        }
        GroebnerBasisEngine engine = new GroebnerBasisEngine(monomialOrder);
        GroebnerBasisEngine.EngineResult result = engine.normalFormModuloIdeal(
            polynomial.orElseThrow(),
            generators,
            budget.maxSteps(),
            budget.maxStates()
        );
        if (result.budgetExhausted()) {
            lastResult = new MathematicalAlgorithmRegistry.AlgorithmExecutionResult(
                MathematicalAlgorithmRegistry.ExecutionStatus.BUDGET_EXHAUSTED,
                MathematicalAlgorithmRegistry.ResultType.DIAGNOSTIC,
                "Gröbner basis computation exceeded budget",
                resultPayload(result)
            );
            return Optional.empty();
        }

        boolean zero = result.remainder().isZero();
        lastResult = new MathematicalAlgorithmRegistry.AlgorithmExecutionResult(
            MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS,
            zero ? MathematicalAlgorithmRegistry.ResultType.PROOF : MathematicalAlgorithmRegistry.ResultType.REFUTATION,
            zero ? "polynomial reduces to 0 modulo Gröbner basis" : "non-zero remainder modulo Gröbner basis",
            resultPayload(result)
        );
        return Optional.of(result.remainder().toCanonicalString(monomialOrder));
    }

    private Map<String, Object> resultPayload(GroebnerBasisEngine.EngineResult result) {
        Map<String, Object> payload = basePayload("");
        payload.put("basis", result.basis().stream().map(polynomial -> polynomial.toCanonicalString(monomialOrder)).toList());
        payload.put("reducedBasis", result.reducedBasis().stream()
            .map(polynomial -> polynomial.toCanonicalString(monomialOrder))
            .toList());
        payload.put("remainder", result.remainder().toCanonicalString(monomialOrder));
        payload.put("monomialOrder", result.monomialOrder());
        payload.put("steps", result.steps());
        payload.put("budgetStatus", result.budgetStatus());
        return Map.copyOf(payload);
    }

    private Optional<String> unsupportedByHardLimits(
        Polynomial polynomial,
        List<Polynomial> generators,
        MathematicalAlgorithmRegistry.AlgorithmBudget budget
    ) {
        List<Polynomial> all = new ArrayList<>();
        all.add(polynomial);
        all.addAll(generators);
        int termCount = all.stream().mapToInt(Polynomial::termCount).sum();
        if (termCount > budget.maxTerms()) {
            return Optional.of("maxTerms");
        }
        int degree = all.stream().mapToInt(Polynomial::totalDegree).max().orElse(0);
        if (degree > budget.maxDegree()) {
            return Optional.of("maxDegree");
        }
        Set<String> variables = new TreeSet<>();
        all.forEach(value -> variables.addAll(value.variables()));
        if (variables.size() > budget.maxVariables()) {
            return Optional.of("maxVariables");
        }
        if (budget.maxCoefficient() > 0 && all.stream().anyMatch(value -> value.coefficientMagnitudeExceeds(budget.maxCoefficient()))) {
            return Optional.of("maxCoefficient");
        }
        return Optional.empty();
    }

    private MathematicalAlgorithmRegistry.AlgorithmExecutionResult unsupported(String detail, String reason) {
        return new MathematicalAlgorithmRegistry.AlgorithmExecutionResult(
            MathematicalAlgorithmRegistry.ExecutionStatus.UNKNOWN,
            MathematicalAlgorithmRegistry.ResultType.DIAGNOSTIC,
            detail,
            basePayload(reason)
        );
    }

    private Map<String, Object> basePayload(String unsupportedReason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("capability", MathematicalAlgorithmRegistry.GROEBNER_BASIS);
        payload.put("backend", registry.isEnabled(MathematicalAlgorithmRegistry.JAS_BACKEND) ? "jas" : "pureJavaSmallGroebner");
        MathematicalAlgorithmRegistry.AlgorithmBudget budget = registry.find(MathematicalAlgorithmRegistry.GROEBNER_BASIS)
            .map(MathematicalAlgorithmRegistry.AlgorithmDescriptor::budget)
            .orElse(SAFE_DEFAULT_BUDGET);
        payload.put("maxTerms", budget.maxTerms());
        payload.put("maxDegree", budget.maxDegree());
        payload.put("maxVariables", budget.maxVariables());
        payload.put("maxPairs", registry.find(MathematicalAlgorithmRegistry.GROEBNER_BASIS)
            .map(MathematicalAlgorithmRegistry.AlgorithmDescriptor::budget)
            .map(MathematicalAlgorithmRegistry.AlgorithmBudget::maxStates)
            .orElse(SAFE_DEFAULT_BUDGET.maxStates()));
        payload.put("timeoutMillis", 0);
        payload.put("unsupportedReason", unsupportedReason == null ? "" : unsupportedReason);
        return payload;
    }
}
