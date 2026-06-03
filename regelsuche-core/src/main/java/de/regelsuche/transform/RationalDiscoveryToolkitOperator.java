package de.regelsuche.transform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates conservative cancel/together/apart-style rational discovery candidates. */
public final class RationalDiscoveryToolkitOperator implements HypothesisOperator {
    public static final String RULE_ID = "hypothesis_sympy_rational_discovery";
    private static final String PACK_ID = "sympy-rational-basic";
    private static final String LICENSE = "BSD-3-Clause";

    private final RationalNormalizationHypothesisOperator normalization = new RationalNormalizationHypothesisOperator();
    private final TelescopingFractionHypothesisOperator telescoping = new TelescopingFractionHypothesisOperator();

    @Override
    public List<Transformation> generateCandidates(String expression) {
        Map<String, Transformation> candidates = new LinkedHashMap<>();
        normalization.generateCandidates(expression).forEach(candidate -> map(candidate, candidates, "cancel-together"));
        telescoping.generateCandidates(expression).forEach(candidate -> map(candidate, candidates, "apart-telescoping"));
        return List.copyOf(candidates.values());
    }

    private void map(Transformation candidate, Map<String, Transformation> out, String provider) {
        out.putIfAbsent(candidate.transformedExpression(), new Transformation(
            RULE_ID,
            candidate.transformedExpression(),
            RewriteKind.NORMALIZE,
            candidate.mayIncreaseComplexity(),
            candidate.estimatedCostDelta(),
            candidate.equivalencePreservingByConstruction(),
            RULE_ID + "|source=sympy-derived|provider=" + provider + "|from=" + candidate.applicationKey(),
            candidate.assumptions(),
            PACK_ID,
            LICENSE
        ));
    }
}
