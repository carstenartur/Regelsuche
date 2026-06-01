package de.regelsuche.validation;

import de.regelsuche.knowledge.ValidationExample;
import de.regelsuche.transform.PatternRewriteRule;
import java.util.List;

public record ValidationExamples(
        List<ValidationExample> examples,
        List<ValidationExample> generatedInstantiations,
        List<ValidationExample> counterexamples) {

    public ValidationExamples {
        examples = examples == null ? List.of() : List.copyOf(examples);
        generatedInstantiations = generatedInstantiations == null ? List.of() : List.copyOf(generatedInstantiations);
        counterexamples = counterexamples == null ? List.of() : List.copyOf(counterexamples);
    }

    public static ValidationExamples fromRule(PatternRewriteRule rule) {
        return new ValidationExamples(rule.descriptor().validationExamples(), RuleInstantiationService.generate(rule), rule.descriptor().counterExamples());
    }
}
