package de.regelsuche.math.sympy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class GraalPySymPyRuntimeBootstrapTest {
    private static final String FACTORABLE_INTEGER_POLYNOMIAL = """
        {"protocol":"regelsuche.sympy-factorization/v1",\
        "domain":"ZZ","variableCount":1,"terms":[\
        {"exponents":[2],"numerator":"1","denominator":"1"},\
        {"exponents":[0],"numerator":"-1","denominator":"1"}]}
        """.replace("\n", "");

    @Test
    void managedLanguageHomeAndModuleVfsBootstrapPinnedSymPy() {
        try (GraalPySymPyRuntime runtime = new GraalPySymPyRuntime()) {
            SymPyInvocation invocation = runtime.invoke(
                FACTORABLE_INTEGER_POLYNOMIAL,
                Duration.ofSeconds(20));

            assertEquals(
                SymPyInvocation.Status.COMPLETED,
                invocation.status(),
                invocation.toString());
            assertEquals(
                "SYMPY_INVOCATION_COMPLETED",
                invocation.detailCode());
            assertTrue(invocation.coldStart());
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
        }
    }
}
