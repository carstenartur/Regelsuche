package de.regelsuche.benchmarks;

import de.regelsuche.solver.ir.CoreExpressionIrAdapter;
import de.regelsuche.solver.ir.SolverBackend;
import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.Binary;
import de.regelsuche.solver.ir.SolverIr.Call;
import de.regelsuche.solver.ir.SolverIr.Expression;
import de.regelsuche.solver.ir.SolverIr.Literal;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import de.regelsuche.solver.ir.SolverIr.Symbol;
import de.regelsuche.solver.ir.SolverIr.Theory;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.solver.ir.SolverTranslation;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * External SymPy baseline for the explicitly shared polynomial-equality fragment.
 *
 * <p>This adapter is validation-only. It never claims discovery or formal proof,
 * rejects assumptions rather than silently dropping them, and records the exact
 * SymPy version and symbolic normal-form payload in the certificate hash.</p>
 */
public final class ExternalSymPySolverBackend implements SolverBackend {
    private static final String SCRIPT = """
        import sys
        import sympy
        from sympy.parsing.sympy_parser import (
            parse_expr,
            standard_transformations,
            convert_xor,
        )

        transformations = standard_transformations + (convert_xor,)
        left = parse_expr(sys.argv[1], transformations=transformations, evaluate=False)
        right = parse_expr(sys.argv[2], transformations=transformations, evaluate=False)
        difference = sympy.expand(left - right)
        simplified = sympy.simplify(difference)

        if simplified == 0:
            verdict = "CONFIRMED"
        elif simplified.is_zero is False:
            verdict = "REFUTED"
        else:
            verdict = "UNKNOWN"

        print(verdict)
        print(sympy.srepr(simplified))
        """;

    private final String pythonExecutable;
    private final long timeoutMillis;
    private final BackendDescriptor descriptor;
    private final String configurationHash;
    private final CoreExpressionIrAdapter expressions = new CoreExpressionIrAdapter();

    public ExternalSymPySolverBackend(
        String backendVersion,
        String pythonExecutable,
        Duration timeout
    ) {
        if (backendVersion == null || backendVersion.isBlank()) {
            throw new IllegalArgumentException("backendVersion must not be blank");
        }
        if (pythonExecutable == null || pythonExecutable.isBlank()) {
            throw new IllegalArgumentException("pythonExecutable must not be blank");
        }
        this.pythonExecutable = pythonExecutable;
        this.timeoutMillis = Math.max(100L, Objects.requireNonNull(timeout).toMillis());
        this.descriptor = new BackendDescriptor(
            "sympy-cas-equality",
            backendVersion.trim(),
            List.of(Theory.REAL_ARITHMETIC),
            List.of(Relation.EQUALS),
            List.of(RequestedEvidence.DECISION, RequestedEvidence.SYMBOLIC_CERTIFICATE),
            true);
        this.configurationHash = SolverIr.sha256(
            "python=" + pythonExecutable
                + "\ntimeoutMillis=" + timeoutMillis
                + "\nfragment=polynomial-equality/v1"
                + "\nscript=" + SCRIPT);
    }

    @Override
    public BackendDescriptor descriptor() {
        return descriptor;
    }

    public String configurationHash() {
        return configurationHash;
    }

    @Override
    public SolverExecution execute(Obligation obligation) {
        Objects.requireNonNull(obligation, "obligation");
        Map<String, String> terms = termMapping(obligation);
        List<String> issues = compatibilityIssues(obligation);
        if (!issues.isEmpty()) {
            return rejected(obligation, terms, issues);
        }

        SolverTranslation translation = SolverTranslation.create(
            obligation,
            descriptor,
            TranslationStatus.LOSSLESS,
            List.of(),
            terms);
        ProcessOutput output = run(
            List.of(
                pythonExecutable,
                "-c",
                SCRIPT,
                terms.get("goal.left"),
                terms.get("goal.right")),
            timeoutMillis);
        SolverResult result;
        if (!output.available()) {
            result = result(
                obligation,
                ResultStatus.ERROR,
                "SymPy Python process unavailable: " + normalize(output.stderr()),
                "",
                List.of("EXTERNAL_PROCESS_UNAVAILABLE"));
        } else if (output.timedOut()) {
            result = result(
                obligation,
                ResultStatus.TIMEOUT,
                "SymPy equality validation timed out",
                "",
                List.of("EXTERNAL_PROCESS_TIMEOUT"));
        } else if (output.exitCode() != 0) {
            result = result(
                obligation,
                ResultStatus.ERROR,
                "SymPy equality validation failed: "
                    + normalize(output.stdout() + " " + output.stderr()),
                "",
                List.of("EXTERNAL_PROCESS_FAILED"));
        } else {
            List<String> lines = output.stdout().lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
            String verdict = lines.isEmpty() ? "" : lines.getFirst().toUpperCase(Locale.ROOT);
            String normalForm = lines.size() <= 1
                ? ""
                : String.join("\n", lines.subList(1, lines.size()));
            result = switch (verdict) {
                case "CONFIRMED" -> result(
                    obligation,
                    ResultStatus.CONFIRMED,
                    "SymPy reduced the polynomial difference to zero",
                    certificate(verdict, normalForm),
                    List.of("EXTERNAL_SYMPY", "POLYNOMIAL_DIFFERENCE_NORMAL_FORM"));
                case "REFUTED" -> result(
                    obligation,
                    ResultStatus.REFUTED,
                    "SymPy produced a non-zero polynomial difference normal form",
                    certificate(verdict, normalForm),
                    List.of("EXTERNAL_SYMPY", "POLYNOMIAL_DIFFERENCE_NORMAL_FORM"));
                case "UNKNOWN" -> result(
                    obligation,
                    ResultStatus.UNKNOWN,
                    "SymPy could not decide whether the polynomial difference is zero",
                    "",
                    List.of("EXTERNAL_SYMPY"));
                default -> result(
                    obligation,
                    ResultStatus.ERROR,
                    "Unrecognized SymPy output: " + normalize(output.stdout()),
                    "",
                    List.of("EXTERNAL_SYMPY", "UNRECOGNIZED_OUTPUT"));
            };
        }
        return SolverExecution.create(obligation, translation, result);
    }

    private Map<String, String> termMapping(Obligation obligation) {
        return Map.of(
            "goal.left", expressions.render(obligation.goal().left()),
            "goal.right", expressions.render(obligation.goal().right()));
    }

    private List<String> compatibilityIssues(Obligation obligation) {
        List<String> issues = new ArrayList<>();
        obligation.theories().stream()
            .filter(theory -> !descriptor.supportedTheories().contains(theory))
            .forEach(theory -> issues.add("UNSUPPORTED_THEORY=" + theory.name()));
        if (!descriptor.supportedRelations().contains(obligation.goal().relation())) {
            issues.add("UNSUPPORTED_RELATION:" + obligation.goal().relation().name());
        }
        if (!descriptor.supportedEvidence().contains(obligation.requestedEvidence())) {
            issues.add("UNSUPPORTED_EVIDENCE:" + obligation.requestedEvidence().name());
        }
        if (!obligation.assumptions().isEmpty()) {
            issues.add("ASSUMPTIONS_NOT_SUPPORTED");
        }
        if (!isPolynomial(obligation.goal().left())
                || !isPolynomial(obligation.goal().right())) {
            issues.add("UNSUPPORTED_EXPRESSION_FRAGMENT:POLYNOMIAL_ONLY");
        }
        return issues.stream().distinct().sorted().toList();
    }

    private SolverExecution rejected(
        Obligation obligation,
        Map<String, String> terms,
        List<String> issues
    ) {
        SolverTranslation translation = SolverTranslation.create(
            obligation,
            descriptor,
            TranslationStatus.REJECTED,
            issues,
            terms);
        SolverResult result = SolverResult.create(
            obligation,
            descriptor,
            ResultStatus.UNSUPPORTED,
            TranslationStatus.REJECTED,
            List.of(),
            issues,
            "SymPy baseline rejected the obligation before execution",
            Map.of(),
            "");
        return SolverExecution.create(obligation, translation, result);
    }

    private SolverResult result(
        Obligation obligation,
        ResultStatus status,
        String message,
        String certificateHash,
        List<String> capabilities
    ) {
        return SolverResult.create(
            obligation,
            descriptor,
            status,
            TranslationStatus.LOSSLESS,
            capabilities,
            List.of(),
            message,
            Map.of(),
            certificateHash);
    }

    private String certificate(String verdict, String normalForm) {
        return SolverIr.sha256(
            "backend=" + descriptor.backendId() + '@' + descriptor.backendVersion()
                + "\
configuration=" + configurationHash
                + "\
verdict=" + verdict
                + "\nnormalForm=" + normalForm);
    }

    private static boolean isPolynomial(Expression expression) {
        if (expression instanceof Literal || expression instanceof Symbol) {
            return true;
        }
        if (expression instanceof Call) {
            return false;
        }
        Binary binary = (Binary) expression;
        return switch (binary.operator()) {
            case ADD, SUBTRACT, MULTIPLY ->
                isPolynomial(binary.left()) && isPolynomial(binary.right());
            case DIVIDE -> false;
            case POWER -> isPolynomial(binary.left()) && nonNegativeInteger(binary.right());
        };
    }

    private static boolean nonNegativeInteger(Expression expression) {
        if (!(expression instanceof Literal literal)) {
            return false;
        }
        try {
            BigDecimal value = new BigDecimal(literal.value()).stripTrailingZeros();
            return value.signum() >= 0 && value.scale() <= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    public static Detection detectSystemSymPy() {
        String python = System.getenv().getOrDefault(
            "REGELSUCHE_SYMPY_PYTHON",
            "python3");
        ProcessOutput output = run(
            List.of(
                python,
                "-c",
                "import sympy; print(sympy.__version__)"),
            5_000L);
        if (!output.available() || output.timedOut() || output.exitCode() != 0) {
            return new Detection(
                new ExternalSymPySolverBackend(
                    "unavailable",
                    python,
                    Duration.ofSeconds(20)),
                false,
                normalize(output.stdout() + " " + output.stderr()));
        }
        String version = normalize(output.stdout());
        if (version.isBlank()) {
            version = "system";
        }
        return new Detection(
            new ExternalSymPySolverBackend(
                version,
                python,
                Duration.ofSeconds(20)),
            true,
            version);
    }

    private static ProcessOutput run(List<String> command, long timeoutMillis) {
        Process process;
        try {
            process = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .start();
        } catch (IOException exception) {
            return new ProcessOutput(
                false,
                false,
                -1,
                "",
                exception.getMessage());
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // The process does not use stdin.
        }
        boolean finished;
        try {
            finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new ProcessOutput(
                true,
                false,
                -1,
                "",
                "Interrupted while waiting for SymPy");
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return new ProcessOutput(
                true,
                true,
                -1,
                read(process.getInputStream()),
                read(process.getErrorStream()));
        }
        return new ProcessOutput(
            true,
            false,
            process.exitValue(),
            read(process.getInputStream()),
            read(process.getErrorStream()));
    }

    private static String read(java.io.InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return "";
      }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    public record Detection(
        ExternalSymPySolverBackend backend,
        boolean available,
        String detail
    ) {
        public Detection {
            Objects.requireNonNull(backend, "backend");
            detail = detail == null ? "" : detail;
        }
    }

    private record ProcessOutput(
        boolean available,
        boolean timedOut,
        int exitCode,
        String stdout,
        String stderr
    ) {
        private ProcessOutput {
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }
    }
}
