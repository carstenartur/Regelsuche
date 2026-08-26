package de.regelsuche.math.sympy;

import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * One-shot CPython control backend. This is retained for isolation and
 * compatibility comparisons; the embedded GraalPy engine is the primary
 * Regelsuche integration.
 */
public final class ProcessSymPyFactorizationEngine<C>
        extends SymPyFactorizationEngine<C> {
    public static final String INTEGER_ENGINE_ID =
        "regelsuche.factorization.sympy-cpython-process.integer/v1";
    public static final String RATIONAL_ENGINE_ID =
        "regelsuche.factorization.sympy-cpython-process.rational/v1";

    private final String pythonExecutable;

    private ProcessSymPyFactorizationEngine(
        String engineId,
        SymPyFactorizationCodec<C> codec,
        SymPyFactorizationPolicy policy,
        String pythonExecutable
    ) {
        super(engineId, codec, policy);
        if (pythonExecutable == null || pythonExecutable.isBlank()) {
            throw new IllegalArgumentException(
                "pythonExecutable must not be blank");
        }
        this.pythonExecutable = pythonExecutable.trim();
    }

    public static ProcessSymPyFactorizationEngine<BigInteger> integers(
        String pythonExecutable
    ) {
        return integers(
            SymPyFactorizationPolicy.pinned(),
            pythonExecutable);
    }

    public static ProcessSymPyFactorizationEngine<BigInteger> integers(
        SymPyFactorizationPolicy policy,
        String pythonExecutable
    ) {
        return new ProcessSymPyFactorizationEngine<>(
            INTEGER_ENGINE_ID,
            SymPyFactorizationCodec.integers(),
            policy,
            pythonExecutable);
    }

    public static ProcessSymPyFactorizationEngine<ExactRational> rationals(
        String pythonExecutable
    ) {
        return rationals(
            SymPyFactorizationPolicy.pinned(),
            pythonExecutable);
    }

    public static ProcessSymPyFactorizationEngine<ExactRational> rationals(
        SymPyFactorizationPolicy policy,
        String pythonExecutable
    ) {
        return new ProcessSymPyFactorizationEngine<>(
            RATIONAL_ENGINE_ID,
            SymPyFactorizationCodec.rationals(),
            policy,
            pythonExecutable);
    }

    public static String configuredPythonExecutable() {
        return System.getenv().getOrDefault(
            "REGELSUCHE_SYMPY_PYTHON",
            "python3");
    }

    @Override
    SymPyInvocation invoke(String payload) {
        SymPyProcessRunner.Output output = SymPyProcessRunner.run(
            List.of(
                pythonExecutable,
                "-I",
                "-c",
                SymPyScript.processProgram()),
            payload.getBytes(StandardCharsets.UTF_8),
            policy().timeout(),
            policy().maxOutputBytes(),
            policy().maxOutputBytes());
        if (!output.available()) {
            return SymPyInvocation.failure(
                SymPyInvocation.Status.UNAVAILABLE,
                "CPYTHON_PROCESS_UNAVAILABLE",
                "cpython-one-shot",
                output.endToEndNanos());
        }
        if (output.timedOut()) {
            return SymPyInvocation.failure(
                SymPyInvocation.Status.TIMEOUT,
                "CPYTHON_FACTORIZATION_TIMEOUT",
                "cpython-one-shot",
                output.endToEndNanos());
        }
        if (output.outputLimitExceeded()) {
            return SymPyInvocation.failure(
                SymPyInvocation.Status.TECHNICAL_FAILURE,
                "CPYTHON_OUTPUT_LIMIT_EXCEEDED",
                "cpython-one-shot",
                output.endToEndNanos());
        }
        if (output.exitCode() != 0) {
            return SymPyInvocation.failure(
                SymPyInvocation.Status.TECHNICAL_FAILURE,
                "CPYTHON_FACTORIZATION_FAILED",
                "cpython-one-shot",
                output.endToEndNanos());
        }
        return SymPyInvocation.completed(
            output.stdout(),
            "cpython-one-shot",
            "external-process",
            true,
            0,
            output.endToEndNanos());
    }
}
