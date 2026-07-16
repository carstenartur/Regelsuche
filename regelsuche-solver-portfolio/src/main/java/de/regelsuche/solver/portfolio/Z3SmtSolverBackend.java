package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverBackend;
import de.regelsuche.solver.ir.SolverExecution;
import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import de.regelsuche.solver.ir.SolverIr.Theory;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.solver.ir.SolverTranslation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** External Z3 backend that confirms unsatisfiability only with a retrieved proof object. */
public final class Z3SmtSolverBackend implements SolverBackend {
    private static final List<Relation> GOAL_RELATIONS = Arrays.stream(Relation.values())
        .filter(relation -> relation != Relation.IS_INTEGER)
        .toList();

    private final BackendDescriptor descriptor;
    private final List<String> command;
    private final long timeoutMillis;
    private final ProcessRunner processRunner;
    private final SmtLibRenderer renderer = new SmtLibRenderer();
    private final String configurationHash;

    public Z3SmtSolverBackend(String backendVersion) {
        this(backendVersion, List.of("z3", "-in", "-smt2"), Duration.ofSeconds(20),
            new DefaultProcessRunner());
    }

    public Z3SmtSolverBackend(
        String backendVersion,
        List<String> command,
        Duration timeout
    ) {
        this(backendVersion, command, timeout, new DefaultProcessRunner());
    }

    Z3SmtSolverBackend(
        String backendVersion,
        List<String> command,
        Duration timeout,
        ProcessRunner processRunner
    ) {
        if (backendVersion == null || backendVersion.isBlank()) {
            throw new IllegalArgumentException("backendVersion must not be blank");
        }
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        this.command = List.copyOf(command);
        this.timeoutMillis = Math.max(100L, Objects.requireNonNull(timeout).toMillis());
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
        this.configurationHash = SolverIr.sha256(
            "command=" + this.command + "\ntimeoutMillis=" + this.timeoutMillis
                + "\nproduceProofs=true");
        this.descriptor = new BackendDescriptor(
            "z3-smt-proof",
            backendVersion,
            List.of(Theory.REAL_ARITHMETIC),
            GOAL_RELATIONS,
            Arrays.asList(RequestedEvidence.values()),
            true);
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
        SmtLibRenderer.Material material = renderer.render(obligation);
        List<String> issues = new ArrayList<>(material.issues());
        obligation.theories().stream()
            .filter(theory -> !descriptor.supportedTheories().contains(theory))
            .forEach(theory -> issues.add("UNSUPPORTED_THEORY:" + theory.name()));
        if (!descriptor.supportedRelations().contains(obligation.goal().relation())) {
            issues.add("UNSUPPORTED_GOAL_RELATION:" + obligation.goal().relation().name());
        }
        if (!descriptor.supportedEvidence().contains(obligation.requestedEvidence())) {
            issues.add("UNSUPPORTED_EVIDENCE:" + obligation.requestedEvidence().name());
        }
        issues = issues.stream().distinct().sorted().toList();
        if (!issues.isEmpty()) {
            return rejected(obligation, material.termMapping(), issues);
        }

        SolverTranslation translation = SolverTranslation.create(
            obligation, descriptor, TranslationStatus.LOSSLESS, List.of(),
            material.termMapping());
        ProcessOutput check = processRunner.run(
            command,
            "(set-option :produce-proofs true)\n"
                + material.scriptPrefix() + "(check-sat)\n",
            timeoutMillis);
        SolverResult result;
        if (!check.available()) {
            result = result(obligation, ResultStatus.ERROR,
                "Z3 executable unavailable: " + check.stderr(), Map.of(), "");
        } else if (check.timedOut()) {
            result = result(obligation, ResultStatus.TIMEOUT,
                "Z3 check-sat timed out", Map.of(), "");
        } else {
            String status = status(check.stdout());
            result = switch (status) {
                case "unsat" -> proofResult(obligation, material);
                case "sat" -> modelResult(obligation, material);
                case "unknown" -> result(obligation, ResultStatus.UNKNOWN,
                    "Z3 returned unknown", Map.of(), "");
                default -> result(obligation, ResultStatus.ERROR,
                    "unrecognized Z3 output: " + normalize(check.stdout() + check.stderr()),
                    Map.of(), "");
            };
        }
        return SolverExecution.create(obligation, translation, result);
    }

    private SolverResult proofResult(
        Obligation obligation,
        SmtLibRenderer.Material material
    ) {
        ProcessOutput proof = processRunner.run(
            command,
            "(set-option :produce-proofs true)\n"
                + material.scriptPrefix() + "(check-sat)\n(get-proof)\n",
            timeoutMillis);
        if (!proof.available()) {
            return result(obligation, ResultStatus.ERROR,
                "Z3 proof retrieval unavailable", Map.of(), "");
        }
        if (proof.timedOut()) {
            return result(obligation, ResultStatus.TIMEOUT,
                "Z3 proof retrieval timed out", Map.of(), "");
        }
        String payload = payloadAfterStatus(proof.stdout(), "unsat");
        if (!"unsat".equals(status(proof.stdout())) || payload.isBlank()) {
            return result(obligation, ResultStatus.ERROR,
                "Z3 confirmed unsat but returned no proof object", Map.of(), "");
        }
        return result(obligation, ResultStatus.CONFIRMED,
            "Z3 returned unsat with a proof object",
            Map.of(), SolverIr.sha256(payload));
    }

    private SolverResult modelResult(
        Obligation obligation,
        SmtLibRenderer.Material material
    ) {
        ProcessOutput model = processRunner.run(
            command,
            material.scriptPrefix() + "(check-sat)\n(get-model)\n",
            timeoutMillis);
        String payload = model.available() && !model.timedOut()
            ? payloadAfterStatus(model.stdout(), "sat") : "";
        Map<String, String> counterexample = payload.isBlank()
            ? Map.of() : Map.of("smtModel", payload);
        return result(obligation, ResultStatus.REFUTED,
            "Z3 found a satisfying countermodel for the negated goal",
            counterexample, "");
    }

    private SolverResult result(
        Obligation obligation,
        ResultStatus status,
        String message,
        Map<String, String> counterexample,
        String certificateHash
    ) {
        List<String> capabilities = new ArrayList<>(List.of(
            "EXTERNAL_Z3", "SMT_LIB_2", "LOSSLESS_STRUCTURED_ASSUMPTIONS"));
        if (status == ResultStatus.CONFIRMED) {
            capabilities.add("SMT_UNSAT_PROOF_OBJECT");
        }
        if (status == ResultStatus.REFUTED) {
            capabilities.add("SMT_COUNTERMODEL");
        }
        return SolverResult.create(
            obligation, descriptor, status, TranslationStatus.LOSSLESS,
            capabilities, List.of(), message, counterexample, certificateHash);
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
            "Z3 translation rejected before execution", Map.of(), "");
        return SolverExecution.create(obligation, translation, result);
    }

    public static Detection detectSystemZ3() {
        ProcessRunner runner = new DefaultProcessRunner();
        ProcessOutput output = runner.run(
            List.of("z3", "-version"), "", 2_000L);
        if (!output.available()) {
            return new Detection(
                new Z3SmtSolverBackend("unavailable", List.of("z3", "-in", "-smt2"),
                    Duration.ofSeconds(20), runner),
                BackendAvailability.UNAVAILABLE,
                normalize(output.stderr()));
        }
        String version = normalize(output.stdout());
        if (version.isBlank()) {
            version = "system";
        }
        return new Detection(
            new Z3SmtSolverBackend(version, List.of("z3", "-in", "-smt2"),
                Duration.ofSeconds(20), runner),
            BackendAvailability.AVAILABLE,
            version);
    }

    private static String status(String output) {
        return output == null ? "" : output.lines()
            .map(String::trim)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .filter(value -> value.equals("sat") || value.equals("unsat")
                || value.equals("unknown"))
            .findFirst().orElse("");
    }

    private static String payloadAfterStatus(String output, String expectedStatus) {
        if (output == null) {
            return "";
        }
        List<String> lines = output.lines().map(String::trim)
            .filter(line -> !line.isBlank()).toList();
        int index = lines.indexOf(expectedStatus);
        if (index < 0 || index + 1 >= lines.size()) {
            return "";
        }
        return String.join("\n", lines.subList(index + 1, lines.size())).trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    public record Detection(
        Z3SmtSolverBackend backend,
        BackendAvailability availability,
        String detail
    ) {
        public Detection {
            Objects.requireNonNull(backend, "backend");
            Objects.requireNonNull(availability, "availability");
            detail = detail == null ? "" : detail;
        }
    }

    @FunctionalInterface
    public interface ProcessRunner {
        ProcessOutput run(List<String> command, String stdin, long timeoutMillis);
    }

    public record ProcessOutput(
        boolean available,
        boolean timedOut,
        int exitCode,
        String stdout,
        String stderr
    ) {
        public ProcessOutput {
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }
    }

    private static final class DefaultProcessRunner implements ProcessRunner {
        @Override
        public ProcessOutput run(
            List<String> command,
            String stdin,
            long timeoutMillis
        ) {
            Process process;
            try {
                process = new ProcessBuilder(command).start();
            } catch (IOException exception) {
                return new ProcessOutput(
                    false, false, -1, "", exception.getMessage());
            }
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "z3-output-reader");
                thread.setDaemon(true);
                return thread;
            };
            ExecutorService readers = Executors.newFixedThreadPool(2, factory);
            Future<String> stdout = readers.submit(
                () -> new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8));
            Future<String> stderr = readers.submit(
                () -> new String(process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8));
            try {
                process.getOutputStream().write(
                    (stdin == null ? "" : stdin).getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().close();
                boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return new ProcessOutput(
                        true, true, -1, getQuietly(stdout), getQuietly(stderr));
                }
                return new ProcessOutput(
                    true, false, process.exitValue(),
                    getQuietly(stdout), getQuietly(stderr));
            } catch (IOException exception) {
                process.destroyForcibly();
                return new ProcessOutput(
                    true, false, -1, getQuietly(stdout), exception.getMessage());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return new ProcessOutput(
                    true, true, -1, getQuietly(stdout), "interrupted");
            } finally {
                readers.shutdownNow();
            }
        }

        private static String getQuietly(Future<String> output) {
            try {
                return output.get(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return "";
            } catch (ExecutionException | java.util.concurrent.TimeoutException exception) {
                return "";
            }
        }
    }
}
