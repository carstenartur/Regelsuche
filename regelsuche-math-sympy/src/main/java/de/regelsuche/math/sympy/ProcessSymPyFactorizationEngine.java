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
    public static final String PYTHON_EXECUTABLE_PROPERTY =
        "regelsuche.sympy.python";
    public static final String PYTHON_EXECUTABLE_ENVIRONMENT =
        "REGELSUCHE_SYMPY_PYTHON";

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

    /**
     * Resolves the explicitly pinned control interpreter for tests and JMH.
     * A JVM property is used by benchmark forks, which cannot inherit a Gradle
     * task environment through the JMH plugin. The environment variable remains
     * the normal test and command-line configuration. Both override `python3`.
     */
    public static String configuredPythonExecutable() {
        return resolvePythonExecutable(
            System.getProperty(PYTHON_EXECUTABLE_PROPERTY),
            System.getenv(PYTHON_EXECUTABLE_ENVIRONMENT));
    }

    /**
     * Returns whether the caller explicitly selected the CPython control
     * interpreter. Qualification tests use this distinction so a standalone
     * Maven reactor without the Gradle-prepared verification environment can
     * skip the optional control transport instead of silently using an
     * unpinned system installation.
     */
    public static boolean hasConfiguredPythonExecutable() {
        return hasConfiguredPythonExecutable(
            System.getProperty(PYTHON_EXECUTABLE_PROPERTY),
            System.getenv(PYTHON_EXECUTABLE_ENVIRONMENT));
    }

    static String resolvePythonExecutable(
        String property,
        String environment
    ) {
        if (hasText(property)) {
            return property.trim();
        }
        return hasText(environment)
            ? environment.trim()
            : "python3";
    }

    static boolean hasConfiguredPythonExecutable(
        String property,
        String environment
    ) {
        return hasText(property) || hasText(environment);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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
            return failure(
                SymPyInvocation.Status.UNAVAILABLE,
                "CPYTHON_PROCESS_UNAVAILABLE",
                output);
        }
        if (output.timedOut()) {
            return failure(
                SymPyInvocation.Status.TIMEOUT,
                "CPYTHON_FACTORIZATION_TIMEOUT",
                output);
        }
        if (output.outputLimitExceeded()) {
            return failure(
                SymPyInvocation.Status.TECHNICAL_FAILURE,
                "CPYTHON_OUTPUT_LIMIT_EXCEEDED",
                output);
        }
        if (output.exitCode() != 0) {
            return failure(
                SymPyInvocation.Status.TECHNICAL_FAILURE,
                "CPYTHON_FACTORIZATION_FAILED",
                output);
        }
        return SymPyInvocation.completed(
            output.stdout(),
            "cpython-one-shot",
            "external-process",
            true,
            0,
            output.endToEndNanos());
    }

    @Override
    String adapterProgramHash() {
        return SymPyScript.processProgramHash();
    }

    private static SymPyInvocation failure(
        SymPyInvocation.Status status,
        String detailCode,
        SymPyProcessRunner.Output output
    ) {
        return SymPyInvocation.failure(
            status,
            detailCode,
            "cpython-one-shot",
            output.endToEndNanos(),
            processDiagnostic(output));
    }

    private static String processDiagnostic(
        SymPyProcessRunner.Output output
    ) {
        StringBuilder diagnostic = new StringBuilder();
        diagnostic.append("available=")
            .append(output.available())
            .append(", timedOut=")
            .append(output.timedOut())
            .append(", exitCode=")
            .append(output.exitCode())
            .append(", stdoutBytes=")
            .append(output.stdoutBytes())
            .append(", stderrBytes=")
            .append(output.stderrBytes())
            .append(", outputLimitExceeded=")
            .append(output.outputLimitExceeded());
        if (!output.stderr().isBlank()) {
            diagnostic.append(", stderr=")
                .append(output.stderr());
        }
        return diagnostic.toString();
    }
}
