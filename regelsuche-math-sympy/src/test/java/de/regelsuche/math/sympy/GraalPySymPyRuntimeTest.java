package de.regelsuche.math.sympy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class GraalPySymPyRuntimeTest {
    private static final Duration COMPLETION_TIMEOUT = Duration.ofMinutes(1);
    private static final String FACTORABLE_INTEGER_POLYNOMIAL = """
        {"protocol":"regelsuche.sympy-factorization/v1",\
        "domain":"ZZ","variableCount":1,"terms":[\
        {"exponents":[2],"numerator":"1","denominator":"1"},\
        {"exponents":[0],"numerator":"-1","denominator":"1"}]}
        """.replace("\n", "");

    @Test
    void aTimedOutGenerationCannotRetireTheRecoveredWarmWorker()
            throws InterruptedException {
        try (GraalPySymPyRuntime runtime = new GraalPySymPyRuntime()) {
            SymPyInvocation initial = runtime.invoke(
                FACTORABLE_INTEGER_POLYNOMIAL,
                COMPLETION_TIMEOUT);
            assertEquals(SymPyInvocation.Status.COMPLETED, initial.status());
            assertTrue(initial.coldStart());

            SymPyInvocation timedOut = runtime.invoke(
                FACTORABLE_INTEGER_POLYNOMIAL,
                Duration.ofNanos(1));
            assertEquals(SymPyInvocation.Status.TIMEOUT, timedOut.status());

            SymPyInvocation recovered = runtime.invoke(
                FACTORABLE_INTEGER_POLYNOMIAL,
                COMPLETION_TIMEOUT);
            assertEquals(
                SymPyInvocation.Status.COMPLETED,
                recovered.status());
            assertTrue(recovered.coldStart());

            // Give the cancelled old task time to report its terminal
            // PolyglotException. Its retired generation must not touch the
            // worker that the recovery call just installed.
            Thread.sleep(100);

            SymPyInvocation warm = runtime.invoke(
                FACTORABLE_INTEGER_POLYNOMIAL,
                COMPLETION_TIMEOUT);
            assertEquals(SymPyInvocation.Status.COMPLETED, warm.status());
            assertFalse(warm.coldStart());
            assertEquals(
                recovered.runtimeVersion(),
                warm.runtimeVersion());
        }
    }
}
