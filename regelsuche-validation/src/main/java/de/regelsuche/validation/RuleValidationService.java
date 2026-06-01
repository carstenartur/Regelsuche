package de.regelsuche.validation;

import de.regelsuche.knowledge.RuleStatus;
import de.regelsuche.knowledge.ValidationExample;
import de.regelsuche.transform.PatternRewriteRule;
import java.util.ArrayList;
import java.util.List;

public final class RuleValidationService {
    public RuleValidationReport validate(PatternRewriteRule rule, ValidationExamples examples) {
        List<String> failures = new ArrayList<>();
        int passed = 0;
        for (ValidationExample example : examples.examples()) {
            if (isUsable(example)) {
                passed++;
            } else {
                failures.add("Invalid example for " + rule.id() + ": " + example);
            }
        }
        if (rule.descriptor().status() == RuleStatus.VALIDATED && examples.examples().isEmpty()) {
            failures.add("VALIDATED rule has no examples: " + rule.id());
        }
        if (rule.descriptor().status() == RuleStatus.VALIDATED && !examples.counterexamples().isEmpty()) {
            failures.add("VALIDATED rule has counterexamples: " + rule.id());
        }
        return new RuleValidationReport(
                rule.id(),
                rule.descriptor().status().name(),
                passed,
                examples.examples().size(),
                examples.generatedInstantiations(),
                examples.counterexamples(),
                failures);
    }

    private boolean isUsable(ValidationExample example) {
        return example != null
                && !normalize(example.from()).isBlank()
                && !normalize(example.to()).isBlank()
                && !normalize(example.from()).equals(normalize(example.to()));
    }

    private String normalize(String expression) {
        return expression == null ? "" : expression.replaceAll("\\s+", "");
    }
}
