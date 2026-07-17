package de.regelsuche.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SourceProvenance;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.solver.ir.SolverObligationFactory;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalSymPySolverBackendTest {
    private final SolverObligationFactory obligations =
        new SolverObligationFactory();

    @Test
    void semanticConfigurationDoesNotDependOnAbsoluteExecutablePath() {
        var first = new ExternalSymPySolverBackend(
            "1.14.0", "/workspace/.venv/bin/python", Duration.ofSeconds(20));
        var second = new ExternalSymPySolverBackend(
            "1.14.0", "/opt/regelsuche-sympy/bin/python", Duration.ofSeconds(20));
        var differentTimeout = new ExternalSymPySolverBackend(
            "1.14.0", "/opt/regelsuche-sympy/bin/python", Duration.ofSeconds(21));

        assertEquals(first.configurationHash(), second.configurationHash());
        assertNotEquals(first.configurationHash(),
            differentTimeout.configurationHash());
    }

    @Test
    void unsupportedFragmentIsRejectedBeforeMissingProcessMatters() {
        var backend = new ExternalSymPySolverBackend(
            "test", "definitely-missing-python", Duration.ofMillis(200));
        var obligation = obligations.equality(
            "sympy-unsupported-division",
            "x / x",
            "1",
            List.of("x != 0"),
            RequestedEvidence.SYMBOLIC_CERTIFICATE,
            provenance("unsupported"));

        var execution = backend.execute(obligation);

        assertEquals(ResultStatus.UNSUPPORTED,
            execution.result().status());
        assertEquals(TranslationStatus.REJECTED,
            execution.translation().status());
        assertTrue(execution.translation().issues().contains(
            "ASSUMPTIONS_NOT_SUPPORTED"));
        assertTrue(execution.translation().issues().contains(
            "UNSUPPORTED_EXPRESSION_FRAGMENT:POLYNOMIAL_ONLY"));
    }

    @Test
    void unavailableProcessRemainsAnExecutionErrorNotUnsupportedMath() {
        var backend = new ExternalSymPySolverBackend(
            "test", "definitely-missing-python", Duration.ofMillis(200));
        var obligation = obligations.equality(
            "sympy-process-unavailable",
            "x + 0",
            "x",
            List.of(),
            RequestedEvidence.SYMBOLIC_CERTIFICATE,
            provenance("unavailable"));

        var execution = backend.execute(obligation);

        assertEquals(ResultStatus.ERROR, execution.result().status());
        assertEquals(TranslationStatus.LOSSLESS,
            execution.translation().status());
        assertTrue(execution.result().usedCapabilities().contains(
            "EXTERNAL_PROCESS_UNAVAILABLE"));
    }

    private static SourceProvenance provenance(String id) {
        return new SourceProvenance(
            "comparative-benchmark-test",
            id,
            SolverIr.sha256("sympy-test:" + id));
    }
}
