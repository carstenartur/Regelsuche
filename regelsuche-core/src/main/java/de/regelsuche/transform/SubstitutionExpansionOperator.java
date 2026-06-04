package de.regelsuche.transform;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Expands previously introduced substitution placeholders back to original subexpressions. */
public final class SubstitutionExpansionOperator implements HypothesisOperator {
    public static final String RULE_ID = "sympy.substitution.basic.expansion";
    private static final String PACK_ID = "sympy-polynomial-basic";
    private static final String LICENSE = "BSD-3-Clause";

    @Override
    public List<Transformation> generateCandidates(String expression) {
        String transformed = expression;
        Map<String, String> mappings = SubstitutionRewriteState.snapshot();
        if (mappings.isEmpty()) {
            return List.of();
        }
        boolean changed = false;
        int maxPasses = Math.max(2, mappings.size() * 2 + 2);
        for (int pass = 0; pass < maxPasses; pass++) {
            boolean passChanged = false;
            for (Map.Entry<String, String> entry : mappings.entrySet()) {
                String placeholder = entry.getKey();
                String replacement = "(" + entry.getValue() + ")";
                Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(placeholder) + "(?![A-Za-z0-9_])");
                String updated = pattern.matcher(transformed).replaceAll(Matcher.quoteReplacement(replacement));
                if (!updated.equals(transformed)) {
                    transformed = updated;
                    passChanged = true;
                    changed = true;
                }
            }
            if (!passChanged) {
                break;
            }
        }
        if (!changed || transformed.equals(expression)) {
            return List.of();
        }
        List<String> assumptions = mappings.entrySet().stream()
            .flatMap(entry -> java.util.stream.Stream.of(
                "substitution.placeholder." + entry.getKey() + "=" + entry.getValue(),
                "substitution.expanded." + entry.getKey() + "=true"
            ))
            .toList();
        return List.of(new Transformation(
            RULE_ID,
            transformed,
            RewriteKind.EXPAND,
            true,
            1,
            true,
            RULE_ID + "|source=sympy-derived|pack=" + PACK_ID,
            assumptions,
            PACK_ID,
            LICENSE
        ));
    }
}
