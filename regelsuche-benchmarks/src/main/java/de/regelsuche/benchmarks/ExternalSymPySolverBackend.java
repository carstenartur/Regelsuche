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
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * External SymPy baseline for the shared polynomial-equality fragment.
 *
 * <p>The adapter is validation-only. It rejects assumptions and unsupported
 * syntax instead of silently changing the problem and binds the deterministic
 * normal-form payload to the exact SymPy revision.</p>
 */
public final class ExternalSymPySolverBackend implements SolverBackend {
    private static final String SCRIPT =
        "import sys,sympy\n"
            + "from sympy.parsing.sympy_parser import parse_expr,"
            + "standard_transformations,convert_xor\n"
            + "t=standard_transformations+(convert_xor,)\n"
            + "a=parse_expr(sys.argv[1],transformations=t,evaluate=False)\n"
            + "b=parse_expr(sys.argv[2],transformations=t,evaluate=False)\n"
            + "d=sympy.simplify(sympy.expand(a-b))\n"
            + "print('CONFIRMED' if d==0 else "
            + "('REFUTED' if d.is_zero is False else 'UNKNOWN'))\n"
            + "print(sympy.srepr(d))\n";

    private final String python;
    private final Duration timeout;
    private final BackendDescriptor descriptor;
    private final String configurationHash;
    private final CoreExpressionIrAdapter expressions = new CoreExpressionIrAdapter();

    public ExternalSymPySolverBackend(
        String backendVersion,
        String python,
        Duration timeout
    ) {
        if (backendVersion == null || backendVersion.isBlank()) {
            throw new IllegalArgumentException("backendVersion must not be blank");
        }
        if (python == null || python.isBlank()) {
            throw new IllegalArgumentException("python must not be blank");
        }
        this.python = python;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.descriptor = new BackendDescriptor(
            "sympy-cas-equality",
            backendVersion.trim(),
            List.of(Theory.REAL_ARITHMETIC),
            List.of(Relation.EQUALS),
            List.of(RequestedEvidence.DECISION,
                RequestedEvidence.SYMBOLIC_CERTIFICATE),
            true);
        this.configurationHash = SolverIr.sha256(
            "python=" + python
                + "\ntimeoutMillis=" + Math.max(100L, timeout.toMillis())
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
        Map<String, String> terms = Map.of(
            "goal.left", expressions.render(obligation.goal().left()),
            "goal.right", expressions.render(obligation.goal().right()));
        List<String> issues = issues(obligation);
        if (!issues.isEmpty()) {
            return rejected(obligation, terms, issues);
        }

        SolverTranslation translation = SolverTranslation.create(
            obligation, descriptor, TranslationStatus.LOSSLESS,
            List.of(), terms);
        ExternalCommand.Output output = ExternalCommand.run(
            List.of(python, "-c", SCRIPT,
                terms.get("goal.left"), terms.get("goal.right")),
            timeout);
        SolverResult result = interpret(obligation, output);
        return SolverExecution.create(obligation, translation, result);
    }

    private SolverResult interpret(
        Obligation obligation,
        ExternalCommand.Output output
    ) {
        if (!output.available()) {
            return result(obligation, ResultStatus.ERROR,
                "SymPy process unavailable: " + normalize(output.stderr()),
                "", List.of("EXTERNAL_PROCESS_UNAVAILABLE"));
        }
        if (output.timedOut()) {
            return result(obligation, ResultStatus.TIMEOUT,
                "SymPy equality validation timed out",
                "", List.of("EXTERNAL_PROCESS_TIMEOUT"));
        }
        if (output.exitCode() != 0) {
            return result(obligation, ResultStatus.ERROR,
                "SymPy equality validation failed: "
                    + normalize(output.stdout() + " " + output.stderr()),
                "", List.of("EXTERNAL_PROCESS_FAILED"));
        }

        List<String> lines = output.stdout().lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .toList();
        String verdict = lines.isEmpty()
            ? ""
            : lines.getFirst().toUpperCase(Locale.ROOT);
        String normalForm = lines.size() <= 1
            ? ""
            : String.join("\n", lines.subList(1, lines.size()));
        return switch (verdict) {
            case "CONFIRMED" -> result(obligation, ResultStatus.CONFIRMED,
                "SymPy reduced the polynomial difference to zero",
                certificate(verdict, normalForm),
                List.of("EXTERNAL_SYMPY",
                    "POLYNOMIAL_DIFFERENCE_NORMAL_FORM"));
            case "REFUTED" -> result(obligation, ResultStatus.REFUTED,
                "SymPy produced a non-zero polynomial difference normal form",
                certificate(verdict, normalForm),
                List.of("EXTERNAL_SYMPY",
                    "POLYNOMIAL_DIFFERENCE_NORMAL_FORM"));
            case "UNKNOWN" -> result(obligation, ResultStatus.UNKNOWN,
                "SymPy could not decide the polynomial difference",
                "", List.of("EXTERNAL_SYMPY"));
            default -> result(obligation, ResultStatus.ERROR,
                "Unrecognized SymPy output: " + normalize(output.stdout()),
                "", List.of("EXTERNAL_SYMPY", "UNRECOGNIZED_OUTPUT"));
        };
    }

    private List<String> issues(Obligation obligation) {
        List<String> issues = new ArrayList<>();
        if (!obligation.theories().stream()
                .allMatch(descriptor.supportedTheories()::contains)) {
            issues.add("UNSUPPORTED_THEORY");
        }
        if (!descriptor.supportedRelations()
                .contains(obligation.goal().relation())) {
            issues.add("UNSUPPORTED_RELATION:"
                + obligation.goal().relation().name());
        }
        if (!descriptor.supportedEvidence()
                .contains(obligation.requestedEvidence())) {
            issues.add("UNSUPPORTED_EVIDENCE:"
                + obligation.requestedEvidence().name());
        }
        if (!obligation.assumptions().isEmpty()) {
            issues.add("ASSUMPTIONS_NOT_SUPPORTED");
        }
        if (!polynomial(obligation.goal().left())
                || !polynomial(obligation.goal().right())) {
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
            obligation, descriptor, TranslationStatus.REJECTED, issues, terms);
        SolverResult result = SolverResult.create(
            obligation, descriptor, ResultStatus.UNSUPPORTED,
            TranslationStatus.REJECTED, List.of(), issues,
            "SymPy baseline rejected the obligation before execution",
            Map.of(), "");
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
            obligation, descriptor, status, TranslationStatus.LOSSLESS,
            capabilities, List.of(), message, Map.of(), certificateHash);
    }

    private String certificate(String verdict, String normalForm) {
        return SolverIr.sha256(
            "backend=" + descriptor.backendId() + '@'
                + descriptor.backendVersion()
                + "\nconfiguration=" + configurationHash
                + "\nverdict=" + verdict
                + "\nnormalForm=" + normalForm);
    }

    private static boolean polynomial(Expression expression) {
        if (expression instanceof Literal || expression instanceof Symbol) {
            return true;
        }
        if (expression instanceof Call) {
            return false;
        }
        Binary binary = (Binary) expression;
        return switch (binary.operator()) {
            case ADD, SUBTRACT, MULTIPLY ->
                polynomial(binary.left()) && polynomial(binary.right());
            case DIVIDE -> false;
            case POWER -> polynomial(binary.left())
                && nonNegativeInteger(binary.right());
        };
    }

    private static boolean nonNegativeInteger(Expression expression) {
        if (!(expression instanceof Literal literal)) {
            return false;
        }
        try {
            BigDecimal value =
                new BigDecimal(literal.value()).stripTrailingZeros();
            return value.signum() >= 0 && value.scale() <= 0;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    public static Detection detectSystemSymPy() {
        String python = System.getenv().getOrDefault(
            "REGELSUCHE_SYMPY_PYTHON", "python3");
        ExternalCommand.Output output = ExternalCommand.run(
            List.of(python, "-c",
                "import sympy; print(sympy.__version__)"),
            Duration.ofSeconds(5));
        if (!output.available() || output.timedOut()
                || output.exitCode() != 0) {
            return new Detection(
                new ExternalSymPySolverBackend(
                    "unavailable", python, Duration.ofSeconds(20)),
                false,
                normalize(output.stdout() + " " + output.stderr()));
        }
        String version = normalize(output.stdout());
        return new Detection(
            new ExternalSymPySolverBackend(
                version.isBlank() ? "system" : version,
                python,
                Duration.ofSeconds(20)),
            true,
            version);
    }

    private static String normalize(String value) {
        return value == null ? ""
            : value.trim().replaceAll("\\s+", " ");
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
}
