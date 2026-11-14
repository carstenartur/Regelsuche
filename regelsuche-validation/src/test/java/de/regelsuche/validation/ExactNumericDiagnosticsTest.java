package de.regelsuche.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.equivalence.SymPyEquivalenceService;
import org.junit.jupiter.api.Test;

class ExactNumericDiagnosticsTest {
    private final SymPyEquivalenceService service = new SymPyEquivalenceService();
    private final SymPyOracleValidator oracle = new SymPyOracleValidator();

    @Test void distinguishesGroundRationalsThatCoincideAsDoublesOrWithinSamplingTolerance() {
        assertFalse(service.areEquivalent("9007199254740992", "9007199254740993"));
        assertFalse(service.areEquivalent("9007199254740992 + 1", "9007199254740992"));
        assertFalse(service.areEquivalent("1/300000000", "0"));
        assertEquals(OracleValidator.OracleValidationStatus.DISAGREE,
            oracle.validateEquivalence("1/3", "0.3333333333333333333333333333").status());
        assertEquals("different exact rational constants", service.evidence("1/3", "1/4"));
        assertTrue(service.areEquivalent("1/3 + 1/6", "0.5"));
        assertEquals("matching exact rational constants", service.evidence("3/9", "1/3"));
    }

    @Test void equalNonrepresentableResultsCannotProduceACounterexample() {
        var finder = new DeterministicCounterexampleSearchService();
        var huge = "9".repeat(310);
        assertTrue(finder.search(huge, huge).isEmpty());
        assertTrue(finder.search("exp(1000 + x)", "exp(1000 + x)").isEmpty());
        assertTrue(finder.search("9000 + x", "8990 + x").isPresent());
    }

    @Test void counterexampleKeepsLargeFiniteObservationsDistinctInItsText() {
        var finder = new DeterministicCounterexampleSearchService();
        var sample = finder.search("100000000000000000000", "200000000000000000000").orElseThrow();
        assertEquals(1e20, Double.parseDouble(sample.leftValue()));
        assertEquals(2e20, Double.parseDouble(sample.rightValue()));
    }

    @Test void anInjectedBooleanServiceCannotCertifyAnExactDecision() {
        var samples = new SymPyOracleValidator((a, b) -> true);
        assertEquals(OracleValidator.OracleValidationStatus.SAMPLE_AGREEMENT,
            samples.validateEquivalence("a", "b").status());
    }

    @Test void unavailableGroundArithmeticCannotAgreeThroughRoundedSamples() {
        assertEquals(OracleValidator.OracleValidationStatus.UNAVAILABLE,
            oracle.validateEquivalence("1/0", "1/0").status());
        assertEquals(OracleValidator.OracleValidationStatus.UNAVAILABLE,
            oracle.validateEquivalence("2^10000", "2^10000").status());
    }
}
