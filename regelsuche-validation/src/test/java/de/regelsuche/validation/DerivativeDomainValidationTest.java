package de.regelsuche.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import de.regelsuche.calculus.CalculusDerivativeRules;
import de.regelsuche.equivalence.AssumptionAwareEquivalenceService;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

class DerivativeDomainValidationTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void formulaOnlyValidationCannotClassifyGuardedDerivativeEquivalence(boolean formulaEquivalent) {
        var validator = new RewriteRuleValidationService(
            new AstRewriteTransformationEngine(CalculusDerivativeRules.rules()),
            (left, right) -> formulaEquivalent);

        var result = validator.validate(List.of("diff(log(x), x)")).getFirst();

        assertEquals(RewriteValidationStatus.UNKNOWN, result.status());
        assertEquals(List.of("x > 0"), result.assumptions());
        assertFalse(validator.allValidated(List.of("diff(log(x), x)")));
    }
    @ParameterizedTest
    @CsvSource({"CONFIRMED,VALIDATED", "REFUTED,REJECTED", "MISSING_ASSUMPTION,UNKNOWN", "UNSUPPORTED,UNKNOWN"})
    void assumptionAwareValidationBindsSourceFormulaAndDomain(
        AssumptionAwareEquivalenceService.Status status, RewriteValidationStatus expected
    ) {
        var validator = new RewriteRuleValidationService(
            new AstRewriteTransformationEngine(CalculusDerivativeRules.rules()),
            (left, right) -> { throw new AssertionError("unguarded checker cannot decide this rewrite"); },
            (left, right, assumptions) -> {
                assertEquals("diff(log(y), y)", left);
                assertEquals("1 / (y * ln(10))", right);
                assertEquals(List.of("y > 0"), assumptions);
                return new AssumptionAwareEquivalenceService.Evaluation(status,
                    status == AssumptionAwareEquivalenceService.Status.CONFIRMED, "", "",
                    List.of("y > 0"), assumptions, List.of(), List.of(), "domain-aware check");
            });

        var result = validator.validate(List.of("diff(log(y), y)")).getFirst();

        assertEquals(expected, result.status());
        assertEquals(List.of("y > 0"), result.assumptions());
    }
}
