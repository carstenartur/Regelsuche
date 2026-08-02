package de.regelsuche.benchmarks;

import de.regelsuche.solver.ir.SolverIr;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * External SymPy baseline in the competing-simplifier role.
 *
 * <p>This adapter is deliberately different from
 * {@link ExternalSymPySolverBackend}. The solver backend is a validator: it
 * receives both sides of a statement and decides equality. This adapter
 * receives the input expression only and must produce a simplified expression
 * on its own, exactly like the untargeted Regelsuche search it is compared
 * against. Neither competitor sees the reference simplest form.</p>
 *
 * <p>The produced expression is rendered in Regelsuche surface syntax so that a
 * single shared judge can canonicalize both competitors' outputs. Rendering is
 * a printing concern only; it never changes the simplification result.</p>
 */
final class ExternalSymPySimplificationBaseline {
    static final String SIMPLIFY_SCRIPT =
        "import sys,sympy\n"
            + "from sympy.parsing.sympy_parser import parse_expr,"
            + "standard_transformations,convert_xor\n"
            + "t=standard_transformations+(convert_xor,)\n"
            + "a=parse_expr(sys.argv[1],transformations=t,evaluate=False)\n"
            + "print(str(sympy.simplify(a)).replace('**','^'))\n";

    private final String backendId = "sympy-cas-simplifier";
    private final String backendVersion;
    private final String python;
    private final Duration timeout;
    private final boolean available;
    private final String detail;
    private final String configurationHash;

    private ExternalSymPySimplificationBaseline(
        String backendVersion,
        String python,
        Duration timeout,
        boolean available,
        String detail
    ) {
        if (backendVersion == null || backendVersion.isBlank()) {
            throw new IllegalArgumentException("backendVersion must not be blank");
        }
        if (python == null || python.isBlank()) {
            throw new IllegalArgumentException("python must not be blank");
        }
        this.backendVersion = backendVersion.trim();
        this.python = python;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.available = available;
        this.detail = detail == null ? "" : detail;
        // The interpreter path is runtime placement, not semantic
        // configuration. Evidence must stay stable when the same SymPy
        // revision runs from a different absolute path.
        this.configurationHash = SolverIr.sha256(
            "timeoutMillis=" + Math.max(100L, timeout.toMillis())
                + "\nrole=TARGET_FREE_SIMPLIFICATION"
                + "\nscript=" + SIMPLIFY_SCRIPT);
    }

    String backendId() {
        return backendId;
    }

    String backendVersion() {
        return backendVersion;
    }

    boolean available() {
        return available;
    }

    String detail() {
        return detail;
    }

    String configurationHash() {
        return configurationHash;
    }

    String environmentIdentity() {
        return "sympy=" + backendVersion + "\nrole=target-free-simplifier";
    }

    /**
     * Simplifies {@code inputExpression} without any knowledge of a target.
     *
     * @param inputExpression the only information handed to the baseline
     * @return the produced simplification outcome, never {@code null}
     */
    Simplification simplify(String inputExpression) {
        Objects.requireNonNull(inputExpression, "inputExpression");
        if (!available) {
            return new Simplification(
                Outcome.UNAVAILABLE, "", List.of("BACKEND_UNAVAILABLE"));
        }
        ExternalCommand.Output output = ExternalCommand.run(
            List.of(python, "-c", SIMPLIFY_SCRIPT, inputExpression), timeout);
        if (!output.available()) {
            return new Simplification(
                Outcome.ERROR, "", List.of("EXTERNAL_PROCESS_UNAVAILABLE"));
        }
        if (output.timedOut()) {
            return new Simplification(
                Outcome.TIMEOUT, "", List.of("EXTERNAL_PROCESS_TIMEOUT"));
        }
        if (output.exitCode() != 0) {
            return new Simplification(
                Outcome.ERROR, "", List.of("EXTERNAL_PROCESS_FAILED"));
        }
        String produced = output.stdout().lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .reduce((first, second) -> second)
            .orElse("");
        if (produced.isEmpty()) {
            return new Simplification(
                Outcome.ERROR, "", List.of("EMPTY_EXTERNAL_OUTPUT"));
        }
        return new Simplification(Outcome.PRODUCED, produced, List.of());
    }

    static ExternalSymPySimplificationBaseline detectSystemSymPy() {
        String python = System.getenv().getOrDefault(
            "REGELSUCHE_SYMPY_PYTHON", "python3");
        ExternalCommand.Output output = ExternalCommand.run(
            List.of(python, "-c", "import sympy; print(sympy.__version__)"),
            Duration.ofSeconds(5));
        if (!output.available() || output.timedOut() || output.exitCode() != 0) {
            return new ExternalSymPySimplificationBaseline(
                "unavailable",
                python,
                Duration.ofSeconds(20),
                false,
                normalize(output.stdout() + " " + output.stderr()));
        }
        String version = normalize(output.stdout());
        return new ExternalSymPySimplificationBaseline(
            version.isBlank() ? "system" : version,
            python,
            Duration.ofSeconds(20),
            true,
            version);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    enum Outcome {
        PRODUCED,
        UNAVAILABLE,
        TIMEOUT,
        ERROR
    }

    record Simplification(
        Outcome outcome,
        String producedExpression,
        List<String> issues
    ) {
        Simplification {
            Objects.requireNonNull(outcome, "outcome");
            producedExpression = producedExpression == null
                ? "" : producedExpression.trim();
            issues = issues == null ? List.of() : issues.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .sorted()
                .toList();
        }
    }
}
