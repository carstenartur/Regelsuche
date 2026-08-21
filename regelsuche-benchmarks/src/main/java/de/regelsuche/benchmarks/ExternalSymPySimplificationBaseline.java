package de.regelsuche.benchmarks;

import de.regelsuche.solver.ir.SolverIr;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * External SymPy baseline in the competing-simplifier role.
 *
 * <p>This adapter is deliberately different from
 * {@link ExternalSymPySolverBackend}. The solver backend is a validator: it
 * receives both sides of a statement and decides equality. This adapter
 * receives the input expression only and applies exactly one declared native
 * SymPy operation, like the untargeted Regelsuche search it is compared
 * against. Neither competitor sees the reference form.</p>
 *
 * <p>Each native operation is a separate configuration with its own backend
 * identity and content hash. This prevents an opaque CAS pipeline from choosing
 * among simplify, factor, cancel, together, apart or trigsimp after seeing a
 * case result.</p>
 *
 * <p>A native operation that explicitly reports that the expression class is
 * not implemented contributes the parsed input unchanged, together with the
 * issue {@code NATIVE_OPERATION_NOT_APPLICABLE_RETURNS_INPUT}. It is therefore
 * an executed negative result, not a crash and not an invitation to substitute
 * another native operation after observing the case.</p>
 *
 * <p>The produced expression is rendered in Regelsuche surface syntax so that a
 * single shared judge can canonicalize all competitors' outputs. Rendering is
 * a printing concern only; it never changes the native operation result.</p>
 *
 * <p>The declared case assumptions travel through the same
 * {@link SimplificationAssumptionContract} as for every other competitor.
 * Symbol-scoped declarations are bound as SymPy symbol assumptions before
 * parsing, which is the mechanism SymPy's own operations consult. A declaration
 * whose subject is a composite expression has no symbol to bind to and is
 * therefore passed but unused; that residue remains an explicit limitation.</p>
 */
final class ExternalSymPySimplificationBaseline {
    private static final String NOT_APPLICABLE_MARKER =
        "__REGELSUCHE_NATIVE_OPERATION_NOT_APPLICABLE__";
    private static final String SCRIPT_PREFIX =
        "import sys,sympy\n"
            + "from sympy.parsing.sympy_parser import parse_expr,"
            + "standard_transformations,convert_xor\n"
            + "t=standard_transformations+(convert_xor,)\n"
            + "d={}\n"
            + "for a in sys.argv[2:]:\n"
            + "    s,_,k=a.rpartition('|')\n"
            + "    if not s.isidentifier() or not k:\n"
            + "        continue\n"
            + "    d[s]=sympy.Symbol(s,**{k:True})\n"
            + "a=parse_expr(sys.argv[1],transformations=t,evaluate=False,"
            + "local_dict=d)\n";
    static final String SIMPLIFY_SCRIPT = script(Operation.SIMPLIFY);

    private final String backendId;
    private final Operation operation;
    private final String backendVersion;
    private final String python;
    private final Duration timeout;
    private final boolean available;
    private final String detail;
    private final String script;
    private final String configurationHash;

    private ExternalSymPySimplificationBaseline(
        Operation operation,
        String backendVersion,
        String python,
        Duration timeout,
        boolean available,
        String detail
    ) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.backendId = operation.backendId();
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
        this.script = script(operation);
        // The interpreter path is runtime placement, not semantic
        // configuration. Evidence must stay stable when the same SymPy
        // revision runs from a different absolute path.
        this.configurationHash = SolverIr.sha256(
            "timeoutMillis=" + Math.max(100L, timeout.toMillis())
                + "\nrole=TARGET_FREE_SIMPLIFICATION"
                + "\noperation=" + operation.functionName()
                + "\nassumptionContract="
                    + SimplificationAssumptionContract.CONTRACT_ID
                + "\nscript=" + script);
    }

    String backendId() {
        return backendId;
    }

    Operation operation() {
        return operation;
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
        return "sympy=" + backendVersion
            + "\nrole=target-free-simplifier"
            + "\noperation=" + operation.functionName();
    }

    /**
     * Applies the configured operation without any knowledge of a target.
     *
     * @param inputExpression the only expression handed to the baseline
     * @param contract the declared case assumptions, injected identically for
     *     every configured competitor
     * @return the produced operation outcome, never {@code null}
     */
    Simplification simplify(
        String inputExpression,
        SimplificationAssumptionContract contract
    ) {
        Objects.requireNonNull(inputExpression, "inputExpression");
        Objects.requireNonNull(contract, "contract");
        if (!available) {
            return new Simplification(
                Outcome.UNAVAILABLE, "", List.of("BACKEND_UNAVAILABLE"));
        }
        List<String> command = new ArrayList<>(
            List.of(python, "-c", script, inputExpression));
        List<String> unbound = new ArrayList<>();
        for (SimplificationAssumptionContract.Declaration declaration
                : contract.declarations()) {
            if (declaration.symbolScoped()) {
                command.add(declaration.subject() + '|'
                    + declaration.kind().externalAssumptionName());
            } else {
                unbound.add(declaration.canonicalText());
            }
        }
        ExternalCommand.Output output = ExternalCommand.run(command, timeout);
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
        List<String> lines = output.stdout().lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .toList();
        boolean notApplicable = lines.contains(NOT_APPLICABLE_MARKER);
        String produced = lines.stream()
            .filter(line -> !NOT_APPLICABLE_MARKER.equals(line))
            .reduce((first, second) -> second)
            .orElse("");
        if (produced.isEmpty()) {
            return new Simplification(
                Outcome.ERROR, "", List.of("EMPTY_EXTERNAL_OUTPUT"));
        }
        List<String> issues = new ArrayList<>();
        if (notApplicable) {
            issues.add("NATIVE_OPERATION_NOT_APPLICABLE_RETURNS_INPUT");
        }
        if (!unbound.isEmpty()) {
            issues.add(
                "COMPOSITE_SIDE_CONDITIONS_NOT_BINDABLE_AS_SYMBOL_ASSUMPTIONS");
        }
        return new Simplification(
            Outcome.PRODUCED,
            produced,
            issues);
    }

    static ExternalSymPySimplificationBaseline detectSystemSymPy() {
        return detectSystemSymPy(Operation.SIMPLIFY);
    }

    static ExternalSymPySimplificationBaseline detectSystemSymPy(
        Operation operation
    ) {
        RuntimeDetection detection = detectRuntime();
        return create(operation, detection);
    }

    static List<ExternalSymPySimplificationBaseline>
            detectSystemSymPyOperations() {
        RuntimeDetection detection = detectRuntime();
        return Arrays.stream(Operation.values())
            .map(operation -> create(operation, detection))
            .toList();
    }

    private static ExternalSymPySimplificationBaseline create(
        Operation operation,
        RuntimeDetection detection
    ) {
        return new ExternalSymPySimplificationBaseline(
            operation,
            detection.version(),
            detection.python(),
            Duration.ofSeconds(20),
            detection.available(),
            detection.detail());
    }

    private static RuntimeDetection detectRuntime() {
        String python = System.getenv().getOrDefault(
            "REGELSUCHE_SYMPY_PYTHON", "python3");
        ExternalCommand.Output output = ExternalCommand.run(
            List.of(python, "-c", "import sympy; print(sympy.__version__)"),
            Duration.ofSeconds(5));
        if (!output.available() || output.timedOut() || output.exitCode() != 0) {
            return new RuntimeDetection(
                "unavailable",
                python,
                false,
                normalize(output.stdout() + " " + output.stderr()));
        }
        String version = normalize(output.stdout());
        return new RuntimeDetection(
            version.isBlank() ? "system" : version,
            python,
            true,
            version);
    }

    private static String script(Operation operation) {
        return SCRIPT_PREFIX
            + "try:\n"
            + "    r=sympy." + operation.functionName() + "(a)\n"
            + "except NotImplementedError:\n"
            + "    print('" + NOT_APPLICABLE_MARKER + "')\n"
            + "    r=a\n"
            + "print(str(r).replace('**','^'))\n";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    enum Operation {
        SIMPLIFY("simplify", "sympy-cas-simplifier"),
        FACTOR("factor", "sympy-cas-factor"),
        CANCEL("cancel", "sympy-cas-cancel"),
        TOGETHER("together", "sympy-cas-together"),
        APART("apart", "sympy-cas-apart"),
        TRIGSIMPLIFY("trigsimp", "sympy-cas-trigsimp");

        private final String functionName;
        private final String backendId;

        Operation(String functionName, String backendId) {
            this.functionName = functionName;
            this.backendId = backendId;
        }

        String functionName() {
            return functionName;
        }

        String backendId() {
            return backendId;
        }

        String limitationId() {
            return "EXTERNAL_CAS_NATIVE_OPERATION_" + name();
        }
    }

    private record RuntimeDetection(
        String version,
        String python,
        boolean available,
        String detail
    ) {
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
