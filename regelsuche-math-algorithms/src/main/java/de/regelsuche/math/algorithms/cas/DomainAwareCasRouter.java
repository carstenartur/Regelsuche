package de.regelsuche.math.algorithms.cas;

import de.regelsuche.math.algorithms.equivalence.GroebnerBasisEquivalenceService;
import de.regelsuche.math.algorithms.equivalence.PolynomialNormalFormEquivalenceService;
import de.regelsuche.math.algorithms.numeric.PslqNumericRelationService;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import de.regelsuche.validation.NumericRelationService;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Routes mathematical validation requests to proof-semantics-safe CAS backends. */
public final class DomainAwareCasRouter {
    private final MathematicalAlgorithmRegistry registry;
    private final PolynomialNormalFormEquivalenceService normalForm;
    private final GroebnerBasisEquivalenceService groebner;
    private final SingularWorker singularWorker;
    private final NumericRelationService numericRelations;

    public DomainAwareCasRouter(MathematicalAlgorithmRegistry registry) {
        this(registry,
            new PolynomialNormalFormEquivalenceService(registry),
            new GroebnerBasisEquivalenceService(registry),
            new DisabledSingularWorker(),
            new PslqNumericRelationService(registry));
    }

    public DomainAwareCasRouter(
        MathematicalAlgorithmRegistry registry,
        PolynomialNormalFormEquivalenceService normalForm,
        GroebnerBasisEquivalenceService groebner,
        SingularWorker singularWorker
    ) {
        this(registry, normalForm, groebner, singularWorker, new PslqNumericRelationService(registry));
    }

    public DomainAwareCasRouter(
        MathematicalAlgorithmRegistry registry,
        PolynomialNormalFormEquivalenceService normalForm,
        GroebnerBasisEquivalenceService groebner,
        SingularWorker singularWorker,
        NumericRelationService numericRelations
    ) {
        this.registry = registry;
        this.normalForm = normalForm;
        this.groebner = groebner;
        this.singularWorker = singularWorker == null ? new DisabledSingularWorker() : singularWorker;
        this.numericRelations = numericRelations == null ? new PslqNumericRelationService(registry) : numericRelations;
    }

    public MathematicalAlgorithmRegistry.AlgorithmExecutionResult provePolynomialIdentity(String left, String right) {
        normalForm.arePolynomiallyEquivalent(left, right);
        return normalForm.lastResult();
    }

    public MathematicalAlgorithmRegistry.AlgorithmExecutionResult proveIdealMembership(
        String polynomialExpression,
        List<String> generatorExpressions
    ) {
        if (registry.isEnabled(MathematicalAlgorithmRegistry.SINGULAR_BACKEND)) {
            MathematicalAlgorithmRegistry.AlgorithmBudget budget = registry.find(MathematicalAlgorithmRegistry.SINGULAR_BACKEND)
                .map(MathematicalAlgorithmRegistry.AlgorithmDescriptor::budget)
                .orElse(MathematicalAlgorithmRegistry.AlgorithmBudget.unbounded());
            MathematicalAlgorithmRegistry.AlgorithmExecutionResult singular = singularWorker.reduceModuloIdeal(polynomialExpression, generatorExpressions,
                singularTimeout(budget));
            if (singular.status() == MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS
                || !registry.isEnabled(MathematicalAlgorithmRegistry.GROEBNER_BASIS)) {
                return singular;
            }
        }
        groebner.normalFormModuloIdeal(polynomialExpression, generatorExpressions);
        return groebner.lastResult();
    }

    public MathematicalAlgorithmRegistry.AlgorithmExecutionResult discoverNumericRelation(List<Double> samples) {
        NumericRelationService.NumericRelationResult result = numericRelations.findIntegerRelation(samples);
        Map<String, Object> payload = new java.util.LinkedHashMap<>(result.result().payload());
        payload.put("sampleCount", samples == null ? 0 : samples.size());
        payload.put("coefficients", result.coefficients());
        payload.put("residual", result.residual());
        return new MathematicalAlgorithmRegistry.AlgorithmExecutionResult(
            result.result().status(),
            result.result().resultType(),
            result.result().detail(),
            payload
        );
    }

    private static Duration singularTimeout(MathematicalAlgorithmRegistry.AlgorithmBudget budget) {
        long timeoutSeconds = Math.max(1L, budget.maxSteps());
        return Duration.ofSeconds(timeoutSeconds);
    }
}
