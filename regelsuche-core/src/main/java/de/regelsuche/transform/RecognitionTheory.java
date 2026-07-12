package de.regelsuche.transform;

import de.regelsuche.ast.Expr;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Expands expressions with an explicitly allow-listed set of recognition-safe
 * rules. Expansion is deterministic and bounded by profile depth and a hard
 * representative limit.
 */
public final class RecognitionTheory implements EquivalentExpressionProvider {
    private static final int MAX_REPRESENTATIVES = 64;
    private final Map<String, RewriteRule> rules;

    public RecognitionTheory(List<? extends RewriteRule> rules) {
        Map<String, RewriteRule> byId = new LinkedHashMap<>();
        if (rules != null) {
            rules.stream().filter(rule -> rule != null)
                .sorted(java.util.Comparator.comparing(RewriteRule::id))
                .forEach(rule -> byId.put(rule.id(), rule));
        }
        this.rules = Map.copyOf(byId);
    }

    @Override
    public List<Expr> representatives(Expr expression, RecognitionProfile profile) {
        LinkedHashMap<Expr, Integer> seen = new LinkedHashMap<>();
        ArrayDeque<Expr> queue = new ArrayDeque<>();
        seen.put(expression, 0);
        queue.add(expression);
        List<String> sortedRuleIds = profile.recognitionRuleIds().stream().sorted().toList();
        while (!queue.isEmpty() && seen.size() < MAX_REPRESENTATIVES) {
            Expr current = queue.removeFirst();
            int depth = seen.get(current);
            if (depth >= profile.maxEquivalenceDepth()) {
                continue;
            }
            for (String ruleId : sortedRuleIds) {
                RewriteRule rule = rules.get(ruleId);
                if (rule == null || !rule.isEquivalencePreservingByConstruction() || !rule.matches(current)) {
                    continue;
                }
                Expr next = rule.apply(current);
                if (!seen.containsKey(next)) {
                    seen.put(next, depth + 1);
                    queue.addLast(next);
                    if (seen.size() >= MAX_REPRESENTATIVES) {
                        break;
                    }
                }
            }
        }
        return new ArrayList<>(seen.keySet());
    }
}
