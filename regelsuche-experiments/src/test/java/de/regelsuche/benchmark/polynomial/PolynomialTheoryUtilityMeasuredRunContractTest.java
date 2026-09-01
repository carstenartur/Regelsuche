package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityMeasuredExecution.MeasuredRun;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityMeasuredRunContractTest {
    @Test
    void failsFastWhenAMeasuredRunReturnsNull() {
        MeasuredRun run = new MeasuredRun() {
            @Override
            public PolynomialTheoryUtilityMeasuredCandidate executeMeasured(
                PolynomialTheoryUtilityExecutionInput input,
                PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
            ) {
                return null;
            }

            @Override
            public void close() {
            }
        };

        var failure = assertThrows(
            NullPointerException.class,
            () -> run.execute(null, null)
        );
        assertEquals("measured adapter result", failure.getMessage());
    }
}
