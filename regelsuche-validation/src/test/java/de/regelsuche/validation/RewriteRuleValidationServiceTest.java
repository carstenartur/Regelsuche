package de.regelsuche.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RewriteRuleValidationServiceTest {
    @Test
    void validatesAtomicRewriteRulesAgainstCasOrNumericSamples() {
        RewriteRuleValidationService validator = new RewriteRuleValidationService(
            new AstRewriteTransformationEngine(),
            new SymPyEquivalenceService()
        );
        List<String> expressions = new ArrayList<>(List.of(
            "x + 0",
            "0 + x",
            "x * 1",
            "1 * x",
            "x * 0",
            "0 * x",
            "x - 0",
            "x / 1",
            "x + x",
            "x * x",
            "x^2",
            "x^2 * x^3",
            "(x^2)^3",
            "x * (y + 1)",
            "(y + 1) * x",
            "x * (y - 1)",
            "(y - 1) * x",
            "x*y + x*z",
            "y*x + z*x"
        ));
        expressions.addAll(new RandomExpressionGenerator(42, List.of("x", "y"), 3).generate(10, 2));

        List<RewriteRuleValidationResult> results = validator.validate(expressions);

        assertFalse(results.isEmpty());
        assertTrue(results.stream().allMatch(result -> result.status() == RewriteValidationStatus.VALIDATED), results::toString);
        assertTrue(results.stream().map(RewriteRuleValidationResult::ruleId).distinct().count() >= 10);
    }

    @Test
    void canonicalizationDoesNotChangeMeaningForRandomExpressions() {
        SymPyEquivalenceService equivalence = new SymPyEquivalenceService();
        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
        for (String expression : new RandomExpressionGenerator(7, List.of("x", "y", "z"), 4).generate(20, 3)) {
            assertTrue(equivalence.areEquivalent(expression, canonicalizer.canonicalize(expression)), expression);
        }
    }
}
