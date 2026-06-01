package de.regelsuche.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.knowledge.KnowledgePackLoader;
import de.regelsuche.knowledge.ValidationExample;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RuleValidationServiceTest {
    @Test
    void validatesExamplesAndGeneratedInstantiations() throws Exception {
        Path packDirectory = Path.of(Thread.currentThread().getContextClassLoader().getResource("rules/packs").toURI());
        var rule = new KnowledgePackLoader().loadAll(packDirectory).stream()
                .flatMap(pack -> pack.rules().stream())
                .filter(candidate -> candidate.id().equals("sympy.poly.factor.diff_squares"))
                .findFirst()
                .orElseThrow();

        RuleValidationReport report = new RuleValidationService().validate(rule, ValidationExamples.fromRule(rule));

        assertTrue(report.passed());
        assertFalse(report.generatedInstantiations().isEmpty());
    }

    @Test
    void rejectsCounterexamplesForValidatedRules() throws Exception {
        Path packDirectory = Path.of(Thread.currentThread().getContextClassLoader().getResource("rules/packs").toURI());
        var rule = new KnowledgePackLoader().loadAll(packDirectory).stream()
                .flatMap(pack -> pack.rules().stream())
                .filter(candidate -> candidate.id().equals("sympy.log.product"))
                .findFirst()
                .orElseThrow();

        RuleValidationReport report = new RuleValidationService().validate(rule, new ValidationExamples(
                rule.descriptor().validationExamples(),
                List.of(),
                List.of(new ValidationExample("log(add(x, y))", "add(log(x), log(y))"))));

        assertFalse(report.passed());
        assertFalse(report.failures().isEmpty());
    }
}
