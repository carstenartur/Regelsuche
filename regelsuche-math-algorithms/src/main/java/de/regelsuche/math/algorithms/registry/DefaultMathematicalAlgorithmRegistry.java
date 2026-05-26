package de.regelsuche.math.algorithms.registry;

import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class DefaultMathematicalAlgorithmRegistry implements MathematicalAlgorithmRegistry {
    private final Map<String, AlgorithmDescriptor> algorithms;

    public DefaultMathematicalAlgorithmRegistry() {
        this(Map.of(), Map.of());
    }

    public DefaultMathematicalAlgorithmRegistry(Map<String, Boolean> enabledOverrides,
                                                Map<String, AlgorithmBudget> budgetOverrides) {
        Map<String, AlgorithmDescriptor> defaults = new LinkedHashMap<>();
        defaults.put(POLYNOMIAL_EQUIVALENCE, descriptor(
            POLYNOMIAL_EQUIVALENCE,
            "Deterministic polynomial normal form",
            true,
            AlgorithmBudget.bounded(200, 1_000, 0, 0.0),
            ProofSemantics.PROOF_FOR_SUPPORTED_DOMAIN,
            ResultType.PROOF,
            ResultType.REFUTATION
        ));
        defaults.put(GROEBNER_BASIS, descriptor(
            GROEBNER_BASIS,
            "Gröbner-basis reduction for polynomial ideals/systems",
            false,
            AlgorithmBudget.bounded(200, 2_000, 0, 0.0, 256, 20, 8),
            ProofSemantics.PROOF_FOR_SUPPORTED_DOMAIN,
            ResultType.PROOF,
            ResultType.REFUTATION
        ));
        defaults.put(JAS_BACKEND, descriptor(
            JAS_BACKEND,
            "Optional JAS backend adapter for Gröbner-basis computation",
            false,
            AlgorithmBudget.bounded(500, 5_000, 0, 0.0),
            ProofSemantics.PROOF_FOR_SUPPORTED_DOMAIN,
            ResultType.PROOF,
            ResultType.REFUTATION,
            ResultType.DIAGNOSTIC
        ));
        defaults.put(SINGULAR_BACKEND, descriptor(
            SINGULAR_BACKEND,
            "External Singular backend adapter",
            false,
            AlgorithmBudget.bounded(500, 5_000, 0, 0.0),
            ProofSemantics.PROOF_FOR_SUPPORTED_DOMAIN,
            ResultType.PROOF,
            ResultType.REFUTATION,
            ResultType.DIAGNOSTIC
        ));
        defaults.put(KNUTH_BENDIX, descriptor(
            KNUTH_BENDIX,
            "Knuth-Bendix completion candidates",
            false,
            AlgorithmBudget.bounded(12, 150, 0, 0.0),
            ProofSemantics.DIAGNOSTIC_ONLY,
            ResultType.DIAGNOSTIC
        ));
        defaults.put(CRITICAL_PAIRS, descriptor(
            CRITICAL_PAIRS,
            "Critical pair analysis",
            true,
            AlgorithmBudget.bounded(8, 120, 0, 0.0),
            ProofSemantics.DIAGNOSTIC_ONLY,
            ResultType.DIAGNOSTIC
        ));
        defaults.put(PSLQ, descriptor(
            PSLQ,
            "PSLQ-style integer relation search",
            false,
            AlgorithmBudget.bounded(0, 50_000, 8, 1e-8),
            ProofSemantics.HYPOTHESIS_ONLY,
            ResultType.HYPOTHESIS,
            ResultType.DIAGNOSTIC
        ));
        defaults.put(NUMERIC_RELATION_SEARCH, descriptor(
            NUMERIC_RELATION_SEARCH,
            "Generic numeric relation search",
            false,
            AlgorithmBudget.bounded(0, 50_000, 8, 1e-8),
            ProofSemantics.HYPOTHESIS_ONLY,
            ResultType.HYPOTHESIS,
            ResultType.DIAGNOSTIC
        ));

        enabledOverrides.forEach((id, enabled) -> {
            AlgorithmDescriptor descriptor = defaults.get(id);
            if (descriptor != null) {
                defaults.put(id, new AlgorithmDescriptor(
                    descriptor.id(),
                    descriptor.capability(),
                    enabled,
                    descriptor.budget(),
                    descriptor.proofSemantics(),
                    descriptor.resultTypes()
                ));
            }
        });

        budgetOverrides.forEach((id, budget) -> {
            AlgorithmDescriptor descriptor = defaults.get(id);
            if (descriptor != null) {
                defaults.put(id, new AlgorithmDescriptor(
                    descriptor.id(),
                    descriptor.capability(),
                    descriptor.enabled(),
                    budget,
                    descriptor.proofSemantics(),
                    descriptor.resultTypes()
                ));
            }
        });

        this.algorithms = Map.copyOf(defaults);
    }

    private static AlgorithmDescriptor descriptor(String id,
                                                  String capability,
                                                  boolean enabled,
                                                  AlgorithmBudget budget,
                                                  ProofSemantics semantics,
                                                  ResultType... resultTypes) {
        return new AlgorithmDescriptor(id, capability, enabled, budget, semantics, EnumSet.of(resultTypes[0], resultTypes));
    }

    @Override
    public Collection<AlgorithmDescriptor> algorithms() {
        return algorithms.values();
    }

    @Override
    public Optional<AlgorithmDescriptor> find(String algorithmId) {
        return Optional.ofNullable(algorithms.get(algorithmId));
    }
}
