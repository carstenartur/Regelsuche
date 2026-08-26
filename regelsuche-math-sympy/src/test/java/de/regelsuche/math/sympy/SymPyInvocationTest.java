package de.regelsuche.math.sympy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SymPyInvocationTest {
    @Test
    void failureRetainsABoundedNoncanonicalCauseChain() {
        IllegalStateException failure = new IllegalStateException(
            "outer failure",
            new UnsupportedOperationException("root\n failure"));

        SymPyInvocation invocation = SymPyInvocation.failure(
            SymPyInvocation.Status.TECHNICAL_FAILURE,
            "GRAALPY_ILLEGALSTATEEXCEPTION",
            "graalpy-embedded",
            17,
            failure);

        assertEquals(
            SymPyInvocation.Status.TECHNICAL_FAILURE,
            invocation.status());
        assertEquals(
            "GRAALPY_ILLEGALSTATEEXCEPTION",
            invocation.detailCode());
        assertTrue(invocation.output().isEmpty());
        assertTrue(invocation.failureDiagnostic().contains(
            "java.lang.IllegalStateException: outer failure"));
        assertTrue(invocation.failureDiagnostic().contains(
            "java.lang.UnsupportedOperationException: root failure"));
        assertFalse(invocation.failureDiagnostic().contains("\n"));
        assertTrue(invocation.failureDiagnostic().length() <= 4_096);
    }

    @Test
    void completedInvocationCannotCarryFailureDiagnostics() {
        SymPyInvocation invocation = SymPyInvocation.completed(
            "{}",
            "graalpy-embedded",
            "graalpy-3.12/sympy-1.14.0",
            true,
            1,
            2);

        assertTrue(invocation.failureDiagnostic().isEmpty());
    }
}
