package de.regelsuche.transform;

import java.util.List;
import java.util.stream.Collectors;

/** SymPy-inspired common-subexpression discovery mapped onto hypothesis candidates. */
public final class CommonSubexpressionDiscoveryOperator implements HypothesisOperator {
    public static final String RULE_ID = "hypothesis_common_subexpression_discovery";
    private static final String PACK_ID = "sympy-polynomial-basic";
    private static final String LICENSE = "BSD-3-Clause";

    private final RepeatedSubexpressionFactorizationHypothesisOperator delegate =
        new RepeatedSubexpressionFactorizationHypothesisOperator();

    @Override
    public List<Transformation> generateCandidates(String expression) {
        return delegate.generateCandidates(expression).stream()
            .map(candidate -> new Transformation(
                RULE_ID,
                candidate.transformedExpression(),
                RewriteKind.NORMALIZE,
                candidate.mayIncreaseComplexity(),
                candidate.estimatedCostDelta(),
                candidate.equivalencePreservingByConstruction(),
                RULE_ID + "|source=sympy-derived|introduce-substitution|from=" + candidate.applicationKey(),
                candidate.assumptions(),
                PACK_ID,
                LICENSE
            ))
            .collect(Collectors.toMap(
                Transformation::transformedExpression,
                candidate -> candidate,
                (left, right) -> left,
                java.util.LinkedHashMap::new))
            .values()
            .stream()
            .toList();
    }
}
