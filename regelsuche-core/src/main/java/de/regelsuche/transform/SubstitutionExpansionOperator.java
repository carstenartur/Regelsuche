package de.regelsuche.transform;

import java.util.List;
import java.util.Map;

/** Expands previously introduced substitution placeholders back to original subexpressions. */
public final class SubstitutionExpansionOperator implements HypothesisOperator {
    public static final String RULE_ID = "sympy.substitution.basic.expansion";
    private static final String PACK_ID = "sympy-polynomial-basic";
    private static final String LICENSE = "BSD-3-Clause";

    @Override
    public List<Transformation> generateCandidates(String expression) {
        String transformed = expression;
        boolean changed = false;
        for (Map.Entry<String, String> entry : SubstitutionRewriteState.snapshot().entrySet()) {
            String placeholder = entry.getKey();
            String replacement = "(" + entry.getValue() + ")";
            String updated = transformed.replace(placeholder, replacement);
            if (!updated.equals(transformed)) {
                transformed = updated;
                changed = true;
            }
        }
        if (!changed) {
            return List.of();
        }
        return List.of(new Transformation(
            RULE_ID,
            transformed,
            RewriteKind.EXPAND,
            true,
            1,
            true,
            RULE_ID + "|source=sympy-derived|pack=" + PACK_ID,
            List.of(),
            PACK_ID,
            LICENSE
        ));
    }
}
