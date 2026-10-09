package de.regelsuche.solver.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.solver.ir.*;
import de.regelsuche.solver.ir.SolverIr.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real Lean execution for generated typed requests. The configured toolchain is trusted code. */
public final class LeanSolverBackend implements SolverBackend {
    public static final String ENVELOPE = "-- regelsuche-obligation-base64: ";
    private static final String AUDIT = "REGELSUCHE_LEAN_AUDIT ";
    private static final Set<String> AXIOMS = Set.of("propext", "Classical.choice", "Quot.sound");
    private static final BackendDescriptor DESCRIPTOR = new BackendDescriptor(
        "lean-kernel-checked", "1", List.of(Theory.REAL_ARITHMETIC, Theory.TRANSCENDENTAL_FUNCTIONS),
        Arrays.stream(Relation.values()).filter(r -> r != Relation.IS_INTEGER).toList(),
        List.of(RequestedEvidence.DECISION, RequestedEvidence.SYMBOLIC_CERTIFICATE,
                RequestedEvidence.FORMAL_PROOF), true);
    private final Path project;
    private final Path evidenceRoot;
    private final List<String> command;
    private final Duration timeout;
    private final LeanSourceRenderer renderer = new LeanSourceRenderer();

    public LeanSolverBackend(Path project, Path evidenceRoot) {
        this(project, evidenceRoot, List.of("lake", "env", "lean"), Duration.ofSeconds(90));
    }
    public LeanSolverBackend(Path project, Path evidenceRoot, List<String> command, Duration timeout) {
        this.project = Objects.requireNonNull(project).toAbsolutePath().normalize();
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
        this.command = List.copyOf(command);
        this.timeout = Objects.requireNonNull(timeout);
        if (command.isEmpty() || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException();
    }
    @Override public BackendDescriptor descriptor() { return DESCRIPTOR; }
    public String configurationHash() {
        return SolverIr.sha256("lean-policy/v1\n" + command + "\n" + project + "\n" + timeout
            + "\n" + fingerprint(project.resolve("lean-toolchain"))
            + "\n" + fingerprint(project.resolve("lake-manifest.json")));
    }
    private static String fingerprint(Path path) {
        try { return SolverIr.sha256(Files.readString(path)); }
        catch (IOException e) { return "unavailable"; }
    }

    /** This is an unexecuted script, not proof evidence. The header contains the native IR. */
    public static String artifact(Obligation obligation) {
        String nonce = "0".repeat(32);
        return ENVELOPE + Base64.getEncoder().encodeToString(
            obligation.toCanonicalJson().getBytes(StandardCharsets.UTF_8)) + "\n"
            + new LeanSourceRenderer().source(obligation, nonce);
    }
    /** Refuse edited or arbitrary Lean source rather than trusting its printed output. */
    public static Obligation readArtifact(String artifact) {
        if (artifact == null || artifact.length() > 2_000_000 || !artifact.startsWith(ENVELOPE))
            throw new IllegalArgumentException("expected generated typed Lean artifact");
        int end = artifact.indexOf('\n');
        if (end < 0) throw new IllegalArgumentException("incomplete artifact");
        String json = new String(Base64.getDecoder().decode(artifact.substring(ENVELOPE.length(),end)),
            StandardCharsets.UTF_8);
        Obligation request = new SolverIrJsonCodec().readObligation(json);
        if (!artifact.equals(artifact(request))) throw new IllegalArgumentException("edited Lean artifact");
        return request;
    }

    @Override public SolverExecution execute(Obligation obligation) {
        return executeWithEvidence(obligation).execution();
    }
    public record Attempt(SolverExecution execution, Path directory) { }

    public Attempt executeWithEvidence(Obligation obligation) {
        Objects.requireNonNull(obligation);
        LeanSourceRenderer.Material material = renderer.render(obligation);
        List<String> issues = new ArrayList<>(material.issues());
        if (!DESCRIPTOR.supportedEvidence().contains(obligation.requestedEvidence()))
            issues.add("UNSUPPORTED_EVIDENCE:" + obligation.requestedEvidence());
        boolean supported = issues.isEmpty();
        SolverTranslation translation = SolverTranslation.create(obligation, DESCRIPTOR,
            supported ? TranslationStatus.LOSSLESS : TranslationStatus.REJECTED,
            issues, material.mapping());
        Path directory = null;
        try {
            Files.createDirectories(evidenceRoot);
            directory = Files.createTempDirectory(evidenceRoot, "lean-");
            Files.writeString(directory.resolve("obligation.json"), obligation.toCanonicalJson());
            Files.writeString(directory.resolve("translation.json"), translation.toCanonicalJson());
            if (!supported) return finish(obligation, translation, ResultStatus.UNSUPPORTED,
                "unsupported Lean translation", "", directory);
            if (!Files.isRegularFile(project.resolve("lean-toolchain"))
                    || !Files.isRegularFile(project.resolve("lake-manifest.json")))
                return finish(obligation, translation, ResultStatus.ERROR,
                    "a pinned Lean project and lake-manifest.json are required", "", directory);
            String before = configurationHash();
            Files.copy(project.resolve("lean-toolchain"), directory.resolve("lean-toolchain"));
            Files.copy(project.resolve("lake-manifest.json"), directory.resolve("lake-manifest.json"));
            ProcessOutput version = run(List.of("--version"), directory, "version");
            if (!version.success() || !version.stdout().startsWith("Lean (version "))
                return finish(obligation, translation, version.timedOut() ? ResultStatus.TIMEOUT : ResultStatus.ERROR,
                    "Lean toolchain version could not be established", "", directory);
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String source = renderer.source(obligation, nonce);
            Files.writeString(directory.resolve("proof.lean"), source);
            ProcessOutput proof = run(List.of("--root=" + directory, "-o", directory.resolve("proof.olean").toString(),
                directory.resolve("proof.lean").toString()), directory, "proof");
            if (!proof.success()) return finish(obligation, translation,
                proof.timedOut() ? ResultStatus.TIMEOUT : proof.outputLimit() ? ResultStatus.ERROR : ResultStatus.UNKNOWN,
                "Lean did not produce a checked theorem; inspect retained diagnostics", "", directory);
            String audit = checkedAudit(proof.stdout(), obligation.contentHash(), nonce);
            if (!before.equals(configurationHash()) || !Files.isRegularFile(directory.resolve("proof.olean")))
                throw new IOException("toolchain changed or compiled proof missing");
            Files.writeString(directory.resolve("audit.json"), audit);
            Files.writeString(directory.resolve("semantics.txt"),
                "The theorem is conditional on exactly the declared premises.\n"
                + "Assumption satisfiability is NOT established by proving an implication.\n"
                + "Only generated code in a trusted pinned Lean/mathlib installation is accepted.\n");
            String compiled = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(directory.resolve("proof.olean"))));
            String manifest = "obligation=" + obligation.contentHash() + "\nsource=" + SolverIr.sha256(source)
                + "\ncompiled=sha256:" + compiled + "\naudit=" + SolverIr.sha256(audit)
                + "\nconfiguration=" + before + "\nversion=" + version.stdout().strip() + "\n";
            Files.writeString(directory.resolve("certificate.txt"), manifest);
            return finish(obligation, translation, ResultStatus.CONFIRMED,
                "Lean checked the requested theorem and standard-axiom closure; premises remain explicit",
                SolverIr.sha256(manifest), directory);
        } catch (Exception error) {
            if (directory != null) {
                try { Files.writeString(directory.resolve("FAILED.txt"), error.toString()); }
                catch (IOException ignored) { /* returned status remains an error */ }
            }
            return new Attempt(execution(obligation, translation, ResultStatus.ERROR,
                error.getClass().getSimpleName() + ": " + error.getMessage(), ""), directory);
        }
    }

    static String checkedAudit(String stdout, String hash, String nonce) throws IOException {
        List<String> lines = stdout.lines().filter(l -> l.startsWith(AUDIT)).toList();
        if (lines.size() != 1) throw new IOException("missing or ambiguous kernel audit");
        String text = lines.get(0).substring(AUDIT.length());
        JsonNode json = new ObjectMapper().readTree(text);
        if (!json.isObject() || json.size() != 5
                || !json.path("schema").asText().equals("regelsuche.lean-audit/v1")
                || !json.path("obligationHash").asText().equals(hash)
                || !json.path("nonce").asText().equals(nonce)
                || !json.path("theorem").asText().equals("regelsuche_bound")
                || !json.path("axioms").isArray()) throw new IOException("wrong audit binding");
        Set<String> seen = new java.util.HashSet<>();
        for (JsonNode axiom : json.path("axioms")) {
            if (!axiom.isTextual() || !AXIOMS.contains(axiom.asText()) || !seen.add(axiom.asText()))
                throw new IOException("unapproved or duplicated proof axiom");
        }
        return text;
    }
    private Attempt finish(Obligation o, SolverTranslation t, ResultStatus status,
                           String message, String hash, Path directory) throws IOException {
        SolverExecution execution = execution(o,t,status,message,hash);
        Files.writeString(directory.resolve("result.json"),execution.result().toCanonicalJson());
        Files.writeString(directory.resolve("execution.json"),execution.toCanonicalJson());
        return new Attempt(execution,directory);
    }
    private SolverExecution execution(Obligation o, SolverTranslation t, ResultStatus status,
                                      String message, String hash) {
        List<String> capabilities = status == ResultStatus.CONFIRMED
            ? List.of("LEAN_KERNEL", "EXACT_CLOSED_THEOREM_TYPE", "TRANSITIVE_STANDARD_AXIOM_AUDIT",
                      "DECLARED_PREMISES_ONLY", "ASSUMPTION_SATISFIABILITY_NOT_CHECKED") : List.of();
        return SolverExecution.create(o, t, SolverResult.create(o, DESCRIPTOR, status, t.status(),
            capabilities,t.issues(),message,Map.of(),hash));
    }

    record ProcessOutput(int exitCode, boolean timedOut, boolean outputLimit, String stdout) {
        boolean success() { return exitCode == 0 && !timedOut && !outputLimit; }
    }
    private ProcessOutput run(List<String> arguments, Path directory, String name) throws Exception {
        List<String> actual = new ArrayList<>(command); actual.addAll(arguments);
        Process process = new ProcessBuilder(actual).directory(project.toFile()).start();
        process.getOutputStream().close();
        var pool = Executors.newFixedThreadPool(2);
        var terminatedForOutputLimit = new AtomicBoolean();
        try {
            Future<Capture> out = pool.submit(() -> drain(process.getInputStream(), process, terminatedForOutputLimit));
            Future<Capture> err = pool.submit(() -> drain(process.getErrorStream(), process, terminatedForOutputLimit));
            boolean done = process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS);
            if (!done) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly(); process.waitFor(5,TimeUnit.SECONDS);
            }
            Capture stdout = out.get(5,TimeUnit.SECONDS), stderr = err.get(5,TimeUnit.SECONDS);
            Files.writeString(directory.resolve(name+".stdout"),stdout.text());
            Files.writeString(directory.resolve(name+".stderr"),stderr.text());
            return new ProcessOutput(done ? process.exitValue() : -1, !done,
                stdout.limit() || stderr.limit(), stdout.text());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            pool.shutdownNow();
        }
    }
    private record Capture(String text, boolean limit) { }
    private static Capture drain(InputStream stream, Process process, AtomicBoolean terminatedForOutputLimit) throws IOException {
        try (stream; var output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; boolean over = false;
            while (true) {
                int n;
                try { n = stream.read(buffer); }
                catch (IOException closed) {
                    // Our output-limit termination can close either process pipe. Retain
                    // both bounded captures; unrelated transport failures still propagate.
                    if (!terminatedForOutputLimit.get()) throw closed;
                    break;
                }
                if (n == -1) break;
                int remaining = 4_000_000-output.size();
                if (remaining > 0) output.write(buffer,0,Math.min(remaining,n));
                if (n > remaining) {
                    over=true; terminatedForOutputLimit.set(true); process.destroyForcibly();
                }
            }
            return new Capture(output.toString(StandardCharsets.UTF_8),over);
        }
    }
}
