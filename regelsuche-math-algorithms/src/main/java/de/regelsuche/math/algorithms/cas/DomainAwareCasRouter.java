package de.regelsuche.math.algorithms.cas;

import de.regelsuche.math.algorithms.equivalence.GroebnerBasisEquivalenceService;
import de.regelsuche.math.algorithms.equivalence.PolynomialNormalFormEquivalenceService;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Routes mathematical validation requests to proof-semantics-safe CAS backends. */
public final class DomainAwareCasRouter {
    private final MathematicalAlgorithmRegistry registry;
    private final PolynomialNormalFormEquivalenceService normalForm;
    private final GroebnerBasisEquivalenceService groebner;
    private final SingularWorker singularWorker;

    public DomainAwareCasRouter(MathematicalAlgorithmRegistry registry) {
        this(registry,
            new PolynomialNormalFormEquivalenceService(registry),
            new GroebnerBasisEquivalenceService(registry),
            new DisabledSingularWorker());
    }

    public DomainAwareCasRouter(
        MathematicalAlgorithmRegistry registry,
        PolynomialNormalFormEquivalenceService normalForm,
        GroebnerBasisEquivalenceService groebner,
        SingularWorker singularWorker
    ) {
        this.registry = registry;
        this.normalForm = normalForm;
        this.groebner = groebner;
        this.singularWorker = singularWorker == null ? new DisabledSingularWorker() : singularWorker;
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
            return singularWorker.reduceModuloIdeal(polynomialExpression, generatorExpressions,
                Duration.ofMillis(Math.max(1, budget.maxSteps())));
        }
        groebner.normalFormModuloIdeal(polynomialExpression, generatorExpressions);
        return groebner.lastResult();
    }

    public MathematicalAlgorithmRegistry.AlgorithmExecutionResult discoverNumericRelation(List<Double> samples) {
        if (!registry.isEnabled(MathematicalAlgorithmRegistry.NUMERIC_RELATION_SEARCH)
            && !registry.isEnabled(MathematicalAlgorithmRegistry.PSLQ)) {
            return MathematicalAlgorithmRegistry.AlgorithmExecutionResult.disabled(
                "numeric relation discovery requires numericRelationSearch or pslq"
            );
        }
        return new MathematicalAlgorithmRegistry.AlgorithmExecutionResult(
            MathematicalAlgorithmRegistry.ExecutionStatus.UNKNOWN,
            MathematicalAlgorithmRegistry.ResultType.HYPOTHESIS,
            "numeric relation discovery is hypothesis-only and has no configured backend",
            Map.of("sampleCount", samples == null ? 0 : samples.size())
        );
    }
}
