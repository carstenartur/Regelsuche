package de.regelsuche.validation;

import de.regelsuche.knowledge.ValidationExample;
import de.regelsuche.transform.PatternRewriteRule;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RuleInstantiationService {
    private static final List<Map<String, String>> SAMPLES = List.of(
            Map.of("?A", "x", "?B", "y", "?C", "z", "?D", "w", "?n", "3"),
            Map.of("?A", "add(a, b)", "?B", "c", "?C", "d", "?D", "e", "?n", "2"),
            Map.of("?A", "sin(t)", "?B", "z", "?C", "u", "?D", "v", "?n", "4"));

    private RuleInstantiationService() {
    }

    public static List<ValidationExample> generate(PatternRewriteRule rule) {
        return SAMPLES.stream()
                .map(sample -> new ValidationExample(substitute(rule.from().toString(), sample), substitute(rule.to().toString(), sample)))
                .distinct()
                .toList();
    }

    private static String substitute(String expression, Map<String, String> values) {
        String result = expression;
        for (Map.Entry<String, String> entry : new LinkedHashMap<>(values).entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
