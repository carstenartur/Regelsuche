package de.regelsuche.math.sympy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Packaging/bootstrap correctness, not a cold-start performance threshold. */
class GraalPySymPyRuntimeBootstrapTest {
    // Match the cold completion allowance of GraalPySymPyRuntimeTest. Importing
    // the pinned environment is part of the first invocation, not a warm call.
    // These are test termination bounds; production invocation policy is unchanged.
    private static final Duration BOOTSTRAP_COMPLETION_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration WARM_INVOCATION_TIMEOUT = Duration.ofSeconds(20);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FACTORABLE_INTEGER_POLYNOMIAL = """
        {"protocol":"regelsuche.sympy-factorization/v1",\
        "domain":"ZZ","variableCount":1,"terms":[\
        {"exponents":[2],"numerator":"1","denominator":"1"},\
        {"exponents":[0],"numerator":"-1","denominator":"1"}]}
        """.replace("\n", "");

    @Test
    void managedLanguageHomeAndModuleVfsBootstrapPinnedSymPy() throws Exception {
        try (GraalPySymPyRuntime runtime = new GraalPySymPyRuntime()) {
            SymPyInvocation invocation = runtime.invoke(
                FACTORABLE_INTEGER_POLYNOMIAL,
                BOOTSTRAP_COMPLETION_TIMEOUT);

            assertEquals(
                SymPyInvocation.Status.COMPLETED,
                invocation.status(),
                invocation.toString());
            assertEquals(
                "SYMPY_INVOCATION_COMPLETED",
                invocation.detailCode());
            assertTrue(invocation.coldStart());
            assertTrue(invocation.initializationNanos() > 0, invocation.toString());
            assertTrue(
                invocation.runtimeVersion().startsWith("graalpy-"),
                invocation.runtimeVersion());
            assertTrue(
                invocation.runtimeVersion().endsWith(
                    "/sympy-"
                        + SymPyFactorizationPolicy.PINNED_SYMPY_VERSION),
                invocation.runtimeVersion());
            assertTrue(
                invocation.output().contains(
                    "\"protocol\":\"regelsuche.sympy-factorization/v1\""),
                invocation.output());

            // Exercise the initialized runtime under the original 20-second
            // bound. No retry is allowed to turn a failed cold call into success.
            SymPyInvocation warm = runtime.invoke(
                FACTORABLE_INTEGER_POLYNOMIAL,
                WARM_INVOCATION_TIMEOUT);
            assertEquals(SymPyInvocation.Status.COMPLETED, warm.status(), warm.toString());
            assertEquals("SYMPY_INVOCATION_COMPLETED", warm.detailCode());
            assertFalse(warm.coldStart(), warm.toString());
            assertEquals(0L, warm.initializationNanos(), warm.toString());
            assertEquals(invocation.runtimeVersion(), warm.runtimeVersion());

            // Backend timing fields legitimately differ. Compare mathematical
            // payload fields rather than requiring byte-identical timing data.
            JsonNode coldPayload = JSON.readTree(invocation.output());
            JsonNode warmPayload = JSON.readTree(warm.output());
            assertEquals("ZZ", coldPayload.path("domain").asText());
            assertTrue(coldPayload.path("factors").isArray());
            assertEquals(2, coldPayload.path("factors").size());
            assertEquals(JSON.readTree("{\"numerator\":\"1\",\"denominator\":\"1\"}"),
                coldPayload.path("unit"));
            for (String field : new String[]{"protocol", "domain", "unit", "factors",
                    "pythonImplementation", "pythonVersion", "sympyVersion"}) {
                assertTrue(coldPayload.hasNonNull(field), "Missing cold payload field: " + field);
                assertEquals(coldPayload.get(field), warmPayload.get(field), field);
            }
            System.out.println("graalPyBootstrapInitializationNanos=" + invocation.initializationNanos());
            System.out.println("graalPyColdInvocationNanos=" + invocation.invocationNanos());
            System.out.println("graalPyWarmInvocationNanos=" + warm.invocationNanos());
        }
    }
}
