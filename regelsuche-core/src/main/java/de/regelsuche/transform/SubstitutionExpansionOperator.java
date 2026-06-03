package de.regelsuche.transform;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
            // Use word-boundary-aware replacement so that e.g. "B" does not
            // match inside "B2" or "AB".
            Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(placeholder) + "(?![A-Za-z0-9_])");
            String updated = pattern.matcher(transformed).replaceAll(replacement.replace("$", "\\$"));
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
