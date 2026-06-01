package de.regelsuche.validation;

import de.regelsuche.knowledge.ValidationExample;
import java.util.List;

public record RuleValidationReport(
        String ruleId,
        String status,
        int examplesPassed,
        int examplesTotal,
        List<ValidationExample> generatedInstantiations,
        List<ValidationExample> counterexamples,
        List<String> failures) {

    public RuleValidationReport {
        generatedInstantiations = generatedInstantiations == null ? List.of() : List.copyOf(generatedInstantiations);
        counterexamples = counterexamples == null ? List.of() : List.copyOf(counterexamples);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public boolean passed() {
        return failures.isEmpty() && counterexamples.isEmpty() && examplesPassed == examplesTotal;
    }

    public double passRate() {
        return examplesTotal == 0 ? 0.0d : (double) examplesPassed / examplesTotal;
    }
}
