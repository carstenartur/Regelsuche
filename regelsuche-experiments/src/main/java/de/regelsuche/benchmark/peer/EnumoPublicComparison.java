package de.regelsuche.benchmark.peer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Executes the frozen public source-only comparison, with retained failures. */
public final class EnumoPublicComparison {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final String CORPUS_SHA = "17caef3eb6065513600918f56f294a5c2401cf5593026d30aefe1bf4e62db09f";
    static final String PROTOCOL_SHA = "5ed4b7a7fff5dcd023dfd31d1bddcff92cad7392b4a6eca4ce4272929a6e3137";
    static final String HORNER_PROTOCOL_SHA = "3e042d3e4915c5a42ec957f4967f9f8f26540477506979b69893a66d53a6f551";
    static final String V1 = "regelsuche.enumo-public-result/v1";
    static final String HORNER_V2 = "regelsuche.enumo-public-result/v2-horner";
    static final String HORNER_TRANSFER = "regelsuche.enumo-transfer-result/v2-horner";
    static final String ROOT_ROSA = "regelsuche.enumo-public-result/v3-root-horner";
    static final String ROOT_TRANSFER = "regelsuche.enumo-transfer-result/v3-root-horner";
    static final String HORNER_ARTIFACTS = "research/benchmarks/enumo-horner-v2";
    static final String ROOT_PROTOCOL_SHA = "f4484a4055e49f740b2868d842f3c8e6bfb9791dd130b8d7bcbcecc561bbe218";
    static final String TRANSFER_PROTOCOL_SHA = "9da63810060e023f6e8cd760d6e74527d293a4434a72d5abfabd354be5d7b5ca";
    static final String TRANSFER_CORPUS_SHA = "34eafa5b61de5b1cadfe5305459ee564fbae568ad5d821991af592126fcc9921";
    private static final long REQUEST_NANOS = TimeUnit.SECONDS.toNanos(2);
    enum Profile {
        REGELSUCHE_BASE("BASE"), REGELSUCHE_LEARNED_RANKED("LEARNED_RANKED"),
        REGELSUCHE_POLYNOMIAL_HORNER("POLYNOMIAL_HORNER_V1"),
        REGELSUCHE_POLYNOMIAL_HORNER_ROOT("POLYNOMIAL_HORNER_ROOT_V1"), ENUMO_LEARNED("");

        final String nativeProfile;
        Profile(String nativeProfile) { this.nativeProfile = nativeProfile; }
    }

    static List<Profile> profiles(boolean horner, boolean rootHorner) {
        if (rootHorner) return List.of(Profile.values());
        if (horner) return List.of(Profile.REGELSUCHE_BASE, Profile.REGELSUCHE_LEARNED_RANKED,
            Profile.REGELSUCHE_POLYNOMIAL_HORNER, Profile.ENUMO_LEARNED);
        return List.of(Profile.REGELSUCHE_BASE, Profile.REGELSUCHE_LEARNED_RANKED, Profile.ENUMO_LEARNED);
    }
    private EnumoPublicComparison() { }

    static String judge(String source, String candidate) {
        try {
            // Both arms must pass the identical frozen surface grammar before
            // coefficient comparison; native powers or cancelled variable
            // denominators must not receive a cheaper, one-sided cost model.
            FpcorePolynomialCorpus.fromPrefix(FpcorePolynomialCorpus.toPrefix(source));
            FpcorePolynomialCorpus.fromPrefix(FpcorePolynomialCorpus.toPrefix(candidate));
            var analysis = new ExactPolynomialAnalysis();
            var expected = analysis.normalForm(source);
            var actual = analysis.normalForm(candidate);
            return expected.equals(actual) ? "VERIFIED" : "INVALID";
        } catch (IllegalArgumentException | ArithmeticException unsupported) {
            return "OUTSIDE_SHARED_GRAMMAR";
        }
    }

    /** Args: checkout root, original bridge, acquired rules, new report directory, optional panel selector. */
    public static void main(String[] args) throws Exception {
        String panel = args.length == 5 ? args[4] : "";
        boolean rootHorner = panel.equals("--root-horner-rosa") || panel.equals("--root-horner-transfer");
        boolean transfer = panel.equals("--horner-transfer") || panel.equals("--root-horner-transfer");
        boolean horner = rootHorner || transfer || panel.equals("--horner-v2");
        if (args.length != 4 && !horner) {
            throw new IllegalArgumentException("expected checkout, Enumo binary, acquired rules, output directory, optional Horner panel");
        }
        Path checkout = Path.of(args[0]).toAbsolutePath();
        Path binary = Path.of(args[1]).toAbsolutePath();
        Path rules = Path.of(args[2]).toAbsolutePath();
        Path output = Path.of(args[3]).toAbsolutePath();
        var entries = inventory(checkout, horner, transfer, rootHorner);
        if (!Files.isExecutable(binary) || !Files.isRegularFile(rules)) throw new IllegalArgumentException("original tool and acquired rules required");
        JsonNode acquisition = JSON.readTree(Files.readString(Path.of(rules + ".receipt.json")));
        if (!acquisition.path("status").asText().equals("ACQUIRED") || acquisition.path("rules").asInt() < 1) {
            throw new IllegalArgumentException("original acquisition receipt required");
        }
        Files.createDirectory(output);
        var rows = new ArrayList<Map<String, Object>>();
        var setups = new ArrayList<Map<String, Object>>();
        var profiles = profiles(horner, rootHorner);
        try (var raw = Files.newBufferedWriter(output.resolve("responses.jsonl"), StandardCharsets.UTF_8)) {
            for (int repetition = 1; repetition <= 3; repetition++) {
                for (Profile profile : profiles) {
                    int before = rows.size();
                    try {
                        runProfile(profile, repetition, entries, binary, rules, output, raw, rows, setups);
                    } catch (Exception unavailable) {
                        setups.add(Map.of("profile", profile, "repetition", repetition,
                            "status", "SETUP_OR_TRANSPORT_FAILURE", "detail", unavailable.toString()));
                        for (int index = rows.size() - before; index < entries.size(); index++) {
                            var entry = entries.get(index);
                            rows.add(Map.of("profile", profile, "repetition", repetition,
                                "ordinal", entry.ordinal(), "name", entry.name(), "source", entry.source(),
                                "status", entry.status() == FpcorePolynomialCorpus.Status.UNSUPPORTED
                                    ? "UNSUPPORTED_INPUT" : "BLOCKED_WORKER_UNAVAILABLE",
                                "detail", entry.status() == FpcorePolynomialCorpus.Status.UNSUPPORTED
                                    ? entry.detail() : unavailable.toString()));
                        }
                    }
                }
            }
        }
        if (rows.size() != entries.size() * 3 * profiles.size()) throw new IllegalStateException("incomplete comparison matrix");
        var report = new TreeMap<String, Object>();
        report.put("schema", rootHorner ? (transfer ? ROOT_TRANSFER : ROOT_ROSA)
            : transfer ? HORNER_TRANSFER : horner ? HORNER_V2 : V1);
        report.put("semantics", "EXACT_RATIONAL_PROJECTION_NOT_IEEE_EQUIVALENCE");
        report.put("protocolSha256", rootHorner ? ROOT_PROTOCOL_SHA
            : transfer ? TRANSFER_PROTOCOL_SHA : horner ? HORNER_PROTOCOL_SHA : PROTOCOL_SHA);
        report.put("corpusSha256", transfer ? TRANSFER_CORPUS_SHA : CORPUS_SHA);
        report.put("enumoBinarySha256", hash(Files.readAllBytes(binary)));
        report.put("acquiredRulesSha256", hash(Files.readAllBytes(rules)));
        report.put("acquisition", acquisition);
        report.put("nativeWorkerClassSha256", nativeWorkerHash());
        report.put("javaVersion", Runtime.version().toString());
        report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        report.put("inventory", entries); report.put("setups", setups); report.put("rows", rows);
        report.put("rawResponsesSha256", hash(Files.readAllBytes(output.resolve("responses.jsonl"))));
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("results.json").toFile(), report);
        var counts = new TreeMap<String, Integer>();
        rows.forEach(row -> counts.merge(row.get("profile") + ":" + row.get("status"), 1, Integer::sum));
        System.out.println(JSON.writeValueAsString(counts));
    }

    static List<FpcorePolynomialCorpus.Entry> inventory(Path checkout, boolean horner, boolean transfer, boolean rootHorner) throws Exception {
        Path original = checkout.resolve("research/benchmarks/enumo-public-v1");
        Path corpus = original.resolve("rosa.fpcore");
        checkHash(corpus, CORPUS_SHA, "foreign corpus differs from freeze");
        checkHash(original.resolve("protocol.json"), PROTOCOL_SHA, "protocol differs from freeze");
        Path hornerArtifacts = checkout.resolve(HORNER_ARTIFACTS);
        if (horner) checkHash(hornerArtifacts.resolve("protocol.json"), HORNER_PROTOCOL_SHA, "Horner protocol differs from freeze");
        if (rootHorner) checkHash(hornerArtifacts.resolve("root-protocol.json"), ROOT_PROTOCOL_SHA, "root Horner protocol differs from freeze");
        if (!transfer) return completeInventory(Files.readString(corpus), 37);
        checkHash(hornerArtifacts.resolve("transfer-protocol.json"), TRANSFER_PROTOCOL_SHA, "transfer protocol differs from freeze");
        String first = Files.readString(hornerArtifacts.resolve("transfer/fptaylor-extra.fpcore"));
        String second = Files.readString(hornerArtifacts.resolve("transfer/hamming-ch3.fpcore"));
        String combined = first + second;
        if (!hash(combined.getBytes(StandardCharsets.UTF_8)).equals(TRANSFER_CORPUS_SHA)) {
            throw new IllegalArgumentException("transfer input bytes differ from freeze");
        }
        return completeInventory(combined, 46);
    }

    private static List<FpcorePolynomialCorpus.Entry> completeInventory(String text, int count) {
        var entries = FpcorePolynomialCorpus.read(text);
        if (entries.size() != count) throw new IllegalArgumentException("incomplete foreign inventory");
        return entries;
    }

    private static void checkHash(Path path, String expected, String detail) throws Exception {
        if (!hash(Files.readAllBytes(path)).equals(expected)) throw new IllegalArgumentException(detail);
    }

    private static void runProfile(Profile profile, int repetition, List<FpcorePolynomialCorpus.Entry> entries,
            Path binary, Path rules, Path output, BufferedWriter raw, List<Map<String, Object>> rows,
            List<Map<String, Object>> setups) throws Exception {
        String nativeProfile = profile.nativeProfile;
        List<String> command = profile == Profile.ENUMO_LEARNED
            ? List.of(binary.toString(), "worker", rules.toString())
            : List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m",
                "-cp", System.getProperty("java.class.path"), "de.regelsuche.evolution.TypedExternalPolynomialComparisonWorker");
        long setupStart = System.nanoTime();
        long setupDeadline = setupStart + TimeUnit.SECONDS.toNanos(60);
        try (var session = new Session(command, output.resolve(profile + "-" + repetition + ".stderr"),
                setupDeadline)) {
            JsonNode initialization = profile == Profile.ENUMO_LEARNED ? session.ready
                : session.request(Map.of("op", "initialize", "profile", nativeProfile), setupDeadline - System.nanoTime());
            if (System.nanoTime() > setupDeadline) throw new TimeoutException("startup and initialization exceeded their shared budget");
            if (profile != Profile.ENUMO_LEARNED && !initialization.path("status").asText().equals("INITIALIZED")) {
                throw new IOException("native profile initialization failed: " + initialization);
            }
            setups.add(Map.of("profile", profile, "repetition", repetition,
                "startupAndInitializationNanos", System.nanoTime() - setupStart, "initialization", initialization));
            for (var entry : entries) {
                var row = new TreeMap<String, Object>();
                row.put("profile", profile); row.put("repetition", repetition);
                row.put("ordinal", entry.ordinal()); row.put("name", entry.name());
                row.put("source", entry.source());
                if (entry.status() == FpcorePolynomialCorpus.Status.UNSUPPORTED) {
                    row.put("status", "UNSUPPORTED_INPUT"); row.put("detail", entry.detail());
                } else {
                    query(session, profile, nativeProfile, entry, row, raw);
                }
                rows.add(row);
            }
        }
    }

    private static void query(Session session, Profile profile, String nativeProfile,
            FpcorePolynomialCorpus.Entry entry, Map<String, Object> row, BufferedWriter raw) throws IOException {
        if (session.failed) { row.put("status", "BLOCKED_WORKER_UNAVAILABLE"); return; }
        long started = System.nanoTime();
        Object retainedRequest = null;
        JsonNode retainedResponse = null;
        try {
            String wire = profile == Profile.ENUMO_LEARNED ? FpcorePolynomialCorpus.toPrefix(entry.source()) : entry.source();
            Object request = profile == Profile.ENUMO_LEARNED ? Map.of("op", "run", "source", wire)
                : Map.of("op", "run", "profile", nativeProfile, "source", wire);
            JsonNode response = session.request(request, REQUEST_NANOS - (System.nanoTime() - started));
            retainedRequest = request; retainedResponse = response;
            if (!response.path("status").asText().equals("CANDIDATE")) {
                row.put("status", "WORKER_ERROR"); row.put("detail", response.toString());
            } else if (!(profile == Profile.ENUMO_LEARNED ? response.path("source") : response.path("input")).asText().equals(wire)) {
                row.put("status", "SOURCE_BINDING_REJECTED");
            } else {
                String candidate = profile == Profile.ENUMO_LEARNED
                    ? FpcorePolynomialCorpus.fromPrefix(response.path("output").asText()) : response.path("output").asText();
                row.put("output", candidate);
                row.put("status", judge(entry.source(), candidate));
                row.put("inputCost", cost(entry.source())); row.put("outputCost", cost(candidate));
                if (response.has("totalWork")) row.put("nativeWork", response.path("totalWork").asLong());
                if (response.has("internalWorkWithinBudget") && !response.path("internalWorkWithinBudget").asBoolean()) {
                    row.put("status", "NATIVE_BUDGET_REJECTED");
                }
            }
        } catch (TimeoutException timeout) {
            row.put("status", "TIMEOUT"); session.failed = true; session.close();
        } catch (IllegalArgumentException unsupported) {
            row.put("status", "OUTSIDE_SHARED_GRAMMAR"); row.put("detail", unsupported.toString());
        } catch (Exception error) {
            row.put("status", "TECHNICAL_FAILURE"); row.put("detail", error.toString());
            session.failed = true; session.close();
        }
        long elapsed = System.nanoTime() - started;
        row.put("requestAndJudgeNanos", elapsed);
        if (elapsed > REQUEST_NANOS && "VERIFIED".equals(row.get("status"))) row.put("status", "TIMEOUT_AFTER_JUDGE");
        if (retainedResponse != null) {
            raw.write(JSON.writeValueAsString(Map.of("profile", profile, "row", row,
                "request", retainedRequest, "response", retainedResponse)));
            raw.newLine(); raw.flush();
        }
    }

    static int cost(String expression) { return cost(new ExpressionParser().parseExactTerm(expression).expression()); }
    private static int cost(Expr expression) {
        return expression instanceof BinaryExpr binary ? 1 + cost(binary.left()) + cost(binary.right()) : 0;
    }
    private static String hash(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String nativeWorkerHash() throws Exception {
        try (var stream = ClassLoader.getSystemResourceAsStream("de/regelsuche/evolution/TypedExternalPolynomialComparisonWorker.class")) {
            if (stream == null) throw new IllegalArgumentException("native worker absent from runtime classpath");
            return hash(stream.readAllBytes());
        }
    }

    static final class Session implements AutoCloseable {
        private final Process process;
        private final BufferedReader output;
        private final BufferedWriter input;
        private final ExecutorService io = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        private final JsonNode ready;
        private boolean failed;
        Session(List<String> command, Path stderr, long startupDeadlineNanos) throws Exception {
            process = new ProcessBuilder(command).redirectError(stderr.toFile()).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            try {
                long remaining = startupDeadlineNanos - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("startup budget exhausted before READY");
                ready = io.submit(this::receive).get(remaining, TimeUnit.NANOSECONDS);
                if (!ready.path("status").asText().equals("READY")) throw new IOException("worker did not become ready");
            } catch (Exception error) { close(); throw error; }
        }
        JsonNode request(Object value, long nanos) throws Exception {
            if (nanos <= 0) throw new TimeoutException("request budget exhausted before dispatch");
            return io.submit(() -> {
                input.write(JSON.writeValueAsString(value)); input.newLine(); input.flush();
                return receive();
            }).get(nanos, TimeUnit.NANOSECONDS);
        }
        private JsonNode receive() throws IOException {
            var line = new StringBuilder();
            for (int count = 0; count < 8 * 1024 * 1024; count++) {
                int next = output.read();
                if (next < 0) throw new IOException("worker closed stdout");
                if (next == '\n') return JSON.readTree(line.toString());
                line.append((char) next);
            }
            throw new IOException("worker response exceeds transport bound");
        }
        @Override public void close() {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly(); io.shutdownNow();
            try { process.waitFor(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { input.close(); } catch (IOException ignored) { }
            try { output.close(); } catch (IOException ignored) { }
        }
    }
}
