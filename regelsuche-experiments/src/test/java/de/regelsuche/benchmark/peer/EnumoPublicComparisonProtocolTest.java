package de.regelsuche.benchmark.peer;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real pipe/JSON/verification boundaries with controlled workers, not another benchmark run. */
@Timeout(30)
class EnumoPublicComparisonProtocolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SOURCE = "x+x+0";
    @TempDir Path temp;

    @Test
    void driftedFrozenInputsAndMissingWorkerInputsCannotCreateAReport() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> EnumoPublicComparison.main(new String[0]));
        var input = frozenInputs();
        Path corpus = input.checkout().resolve("research/benchmarks/enumo-public-v1/rosa.fpcore");
        byte[] originalCorpus = Files.readAllBytes(corpus);
        Files.writeString(corpus, "(FPCore (x) x)");
        var corpusFailure = assertThrows(IllegalArgumentException.class,
            () -> EnumoPublicComparison.main(input.arguments()));
        assertEquals("foreign corpus differs from freeze", corpusFailure.getMessage());
        Files.write(corpus, originalCorpus);
        Path protocol = input.checkout().resolve("research/benchmarks/enumo-public-v1/protocol.json");
        byte[] originalProtocol = Files.readAllBytes(protocol);
        Files.writeString(protocol, "{}");
        var protocolFailure = assertThrows(IllegalArgumentException.class,
            () -> EnumoPublicComparison.main(input.arguments()));
        assertEquals("protocol differs from freeze", protocolFailure.getMessage());
        Files.write(protocol, originalProtocol);
        Files.writeString(input.rules(), "controlled test rules");
        Files.writeString(Path.of(input.rules() + ".receipt.json"), "{\"status\":\"ACQUIRED\",\"rules\":1}");
        var missingBinary = input.arguments();
        missingBinary[1] = temp.resolve("missing-worker").toString();
        assertThrows(IllegalArgumentException.class, () -> EnumoPublicComparison.main(missingBinary));
        Files.delete(input.rules());
        assertThrows(IllegalArgumentException.class, () -> EnumoPublicComparison.main(input.arguments()));
        assertFalse(Files.exists(input.output()));
    }

    @Test
    void acquisitionMustSucceedAndTheWriterMustNeverReplaceAnExistingDirectory() throws Exception {
        var input = frozenInputs();
        Files.writeString(input.rules(), "controlled test rules");
        Path receipt = Path.of(input.rules() + ".receipt.json");
        for (String invalid : List.of("{\"status\":\"FAILED\",\"rules\":1}", "{\"status\":\"ACQUIRED\",\"rules\":0}")) {
            Files.writeString(receipt, invalid);
            assertThrows(IllegalArgumentException.class, () -> EnumoPublicComparison.main(input.arguments()));
            assertFalse(Files.exists(input.output()));
        }
        Files.writeString(receipt, "{\"status\":\"ACQUIRED\",\"rules\":1}");
        Files.createDirectory(input.output());
        Path retained = input.output().resolve("retained.txt");
        Files.writeString(retained, "do not overwrite");
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
            () -> EnumoPublicComparison.main(input.arguments()));
        assertEquals("do not overwrite", Files.readString(retained));
        try (var files = Files.list(input.output())) { assertEquals(List.of(retained), files.toList()); }
    }

    @Test
    void sessionFramesUtf8RequestsAndNeverDispatchesAnExpiredRequest() throws Exception {
        var worker = worker("echo", "");
        try (var session = worker.start()) {
            var request = Map.of("source", "λ\nx", "op", "run");
            assertEquals(JSON.valueToTree(request), session.request(request, seconds(5)));
            assertThrows(TimeoutException.class, () -> session.request(Map.of("op", "expired"), 0));
            assertThrows(TimeoutException.class, () -> session.request(Map.of("op", "expired"), -1));
            assertEquals("next", session.request(Map.of("op", "next"), seconds(5)).path("op").asText());
            var sent = Files.readAllLines(worker.requests());
            assertEquals(2, sent.size(), "Expired requests must not reach the worker");
            assertEquals("λ\nx", JSON.readTree(sent.getFirst()).path("source").asText());
            assertEquals("next", JSON.readTree(sent.getLast()).path("op").asText());
        }
        assertStopped(worker);
    }

    @Test
    void aWorkerWithoutReadyIsRejectedAndTerminated() throws Exception {
        var worker = worker("not-ready", "");
        assertThrows(IOException.class, () -> { try (var unexpected = worker.start()) { } });
        assertStopped(worker);
        assertFalse(Files.exists(worker.requests()));
    }

    @Test
    void duplicateReadyFieldsCannotEstablishASession() throws Exception {
        var worker = worker("duplicate-ready", "");
        var failure = assertThrows(ExecutionException.class, () -> { try (var unexpected = worker.start()) { } });
        assertInstanceOf(IOException.class, failure.getCause());
        assertStopped(worker);
    }

    @Test
    void malformedOrMultipleResponseDocumentsAreRejected() throws Exception {
        for (String response : List.of("{\"status\":\"ok\",\"status\":\"other\"}", "{} {}", "{invalid}")) {
            var worker = worker("response", response);
            try (var session = worker.start()) {
                var failure = assertThrows(ExecutionException.class,
                    () -> session.request(Map.of("op", "run"), seconds(5)));
                assertInstanceOf(IOException.class, failure.getCause());
            }
            assertStopped(worker);
        }
    }

    @Test
    void anUnterminatedResponseCannotExceedTheTransportBound() throws Exception {
        var worker = worker("oversized", "");
        try (var session = worker.start()) {
            var failure = assertThrows(ExecutionException.class,
                () -> session.request(Map.of("op", "run"), seconds(15)));
            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("transport bound"));
        }
        assertStopped(worker);
    }

    @Test
    void nativeRepliesRetainTheSourceProfileWorkAndIndependentCostDecision() throws Exception {
        for (String nativeProfile : List.of("BASE", "LEARNED_RANKED")) {
            String profile = "REGELSUCHE_" + nativeProfile;
            var worker = worker("response", nativeReply(SOURCE, "2*x", true));
            try (var session = worker.start()) {
                var raw = new StringWriter();
                var row = query(session, profile, nativeProfile, raw);
                assertEquals("VERIFIED", row.get("status"));
                assertEquals("2*x", row.get("output"));
                assertEquals(2, row.get("inputCost"));
                assertEquals(1, row.get("outputCost"));
                assertEquals(13L, row.get("nativeWork"));
                var retained = oneRecord(raw);
                assertEquals(profile, retained.path("profile").asText());
                assertEquals(nativeProfile, retained.path("request").path("profile").asText());
                assertEquals(SOURCE, retained.path("request").path("source").asText());
                assertEquals("run", retained.path("request").path("op").asText());
                assertEquals("2*x", retained.path("response").path("output").asText());
                assertEquals(JSON.readTree(JSON.writeValueAsString(row)), retained.path("row"));
            }
            assertStopped(worker);
        }
    }

    @Test
    void originalRepliesUsePrefixWireGrammarAndRetainTheUnmodifiedResponse() throws Exception {
        String response = "{\"status\":\"CANDIDATE\",\"source\":\"(+ (+ x x) 0)\",\"output\":\"(* 2 x)\"}";
        var worker = worker("response", response);
        try (var session = worker.start()) {
            var raw = new StringWriter();
            var row = query(session, "ENUMO_LEARNED", "unused", raw);
            assertEquals("VERIFIED", row.get("status"));
            assertEquals("(2*x)", row.get("output"));
            assertEquals(2, row.get("inputCost"));
            assertEquals(1, row.get("outputCost"));
            assertFalse(row.containsKey("nativeWork"));
            var retained = oneRecord(raw);
            assertEquals("(+ (+ x x) 0)", retained.path("request").path("source").asText());
            assertFalse(retained.path("request").has("profile"));
            assertEquals(JSON.readTree(response), retained.path("response"));
        }
    }

    @Test
    void aForeignSourceIsRejectedBeforeItsBudgetFlagCanReplaceTheStatus() throws Exception {
        for (String profile : List.of("REGELSUCHE_BASE", "ENUMO_LEARNED")) {
            String response = "{\"status\":\"CANDIDATE\",\"input\":\"foreign\",\"source\":\"foreign\","
                + "\"output\":\"0\",\"internalWorkWithinBudget\":false}";
            var worker = worker("response", response);
            try (var session = worker.start()) {
                var raw = new StringWriter();
                var row = query(session, profile, "BASE", raw);
                assertEquals("SOURCE_BINDING_REJECTED", row.get("status"));
                assertFalse(row.containsKey("output"));
                assertFalse(row.containsKey("outputCost"));
                assertEquals(JSON.readTree(response), oneRecord(raw).path("response"));
            }
        }
    }

    @Test
    void anOtherwiseVerifiedNativeCandidateCannotHideItsExceededWorkBudget() throws Exception {
        var worker = worker("response", nativeReply(SOURCE, "2*x", false));
        try (var session = worker.start()) {
            var raw = new StringWriter();
            var row = query(session, "REGELSUCHE_BASE", "BASE", raw);
            assertEquals("NATIVE_BUDGET_REJECTED", row.get("status"));
            assertEquals("2*x", row.get("output"));
            assertEquals(13L, row.get("nativeWork"));
            assertEquals("NATIVE_BUDGET_REJECTED", oneRecord(raw).path("row").path("status").asText());
        }
    }

    @Test
    void nonEquivalentNativeCandidatesRemainRecordedNegativeOutcomes() throws Exception {
        var worker = worker("response", nativeReply(SOURCE, "3*x", true));
        try (var session = worker.start()) {
            var raw = new StringWriter();
            var row = query(session, "REGELSUCHE_BASE", "BASE", raw);
            assertEquals("INVALID", row.get("status"));
            assertEquals("3*x", row.get("output"));
            assertEquals("INVALID", oneRecord(raw).path("row").path("status").asText());
        }
    }

    @Test
    void unsupportedOriginalOutputIsRecordedWithoutPoisoningTheTransport() throws Exception {
        var worker = worker("response", "{\"status\":\"CANDIDATE\",\"source\":\"(+ (+ x x) 0)\",\"output\":\"(if x 1 0)\"}");
        try (var session = worker.start()) {
            var raw = new StringWriter();
            var row = query(session, "ENUMO_LEARNED", "unused", raw);
            assertEquals("OUTSIDE_SHARED_GRAMMAR", row.get("status"));
            assertFalse(row.get("detail").toString().isBlank());
            assertEquals("(if x 1 0)", oneRecord(raw).path("response").path("output").asText());
            var again = query(session, "ENUMO_LEARNED", "unused", new StringWriter());
            assertEquals("OUTSIDE_SHARED_GRAMMAR", again.get("status"));
            assertEquals(2, Files.readAllLines(worker.requests()).size());
        }
    }

    @Test
    void aWorkerErrorRemainsAnAuditableResponseAndDoesNotBecomeACandidate() throws Exception {
        var worker = worker("response", "{\"status\":\"ERROR\",\"detail\":\"controlled rejection\"}");
        try (var session = worker.start()) {
            var raw = new StringWriter();
            var row = query(session, "REGELSUCHE_BASE", "BASE", raw);
            assertEquals("WORKER_ERROR", row.get("status"));
            assertFalse(row.containsKey("output"));
            assertTrue(row.get("detail").toString().contains("controlled rejection"));
            assertEquals("ERROR", oneRecord(raw).path("response").path("status").asText());
        }
    }

    @Test
    void aRecordingFailurePropagatesInsteadOfSilentlyDroppingTheVerifiedResponse() throws Exception {
        var worker = worker("response", nativeReply(SOURCE, "2*x", true));
        try (var session = worker.start()) {
            var sink = new Writer() {
                @Override public void write(char[] chars, int offset, int length) throws IOException {
                    throw new IOException("controlled recording failure");
                }
                @Override public void flush() { }
                @Override public void close() { }
            };
            var failure = assertThrows(IOException.class,
                () -> query(session, "REGELSUCHE_BASE", "BASE", new BufferedWriter(sink)));
            assertEquals("controlled recording failure", failure.getMessage());
        }
    }

    @Test
    void closedStdoutQuarantinesTheSessionAndNeverInventsARawResponse() throws Exception {
        var worker = worker("closed", "");
        try (var session = worker.start()) {
            assertTerminalFailure(session, "TECHNICAL_FAILURE");
        }
        assertEquals(1, Files.readAllLines(worker.requests()).size());
        assertStopped(worker);
    }

    @Test
    void aTimedOutWorkerIsClosedBeforeAnotherQueryCanBeDispatched() throws Exception {
        var worker = worker("silent", "");
        try (var session = worker.start()) {
            assertTerminalFailure(session, "TIMEOUT");
        }
        assertEquals(1, Files.readAllLines(worker.requests()).size());
        assertStopped(worker);
    }

    private static void assertTerminalFailure(EnumoPublicComparison.Session session, String status) throws Exception {
        var raw = new StringWriter();
        var first = query(session, "REGELSUCHE_BASE", "BASE", raw);
        assertEquals(status, first.get("status"));
        assertTrue((long) first.get("requestAndJudgeNanos") > 0);
        assertTrue(raw.toString().isEmpty());
        var blocked = query(session, "REGELSUCHE_BASE", "BASE", raw);
        assertEquals("BLOCKED_WORKER_UNAVAILABLE", blocked.get("status"));
        assertFalse(blocked.containsKey("requestAndJudgeNanos"));
        assertTrue(raw.toString().isEmpty());
    }

    private static JsonNode oneRecord(StringWriter raw) throws IOException {
        assertEquals(1, raw.toString().lines().count());
        assertTrue(raw.toString().endsWith(System.lineSeparator()));
        return JSON.readTree(raw.toString());
    }

    // The production entry point launches a frozen full panel. Invoke its actual
    // per-request boundary here so small controlled workers need no production hook.
    private static Map<String, Object> query(EnumoPublicComparison.Session session, String profileName,
            String nativeProfile, StringWriter raw) throws Exception {
        // Do not close or flush this in-memory writer here: the assertions must
        // observe the production writer's flush, not a test-helper side effect.
        return query(session, profileName, nativeProfile, new BufferedWriter(raw));
    }

    private static Map<String, Object> query(EnumoPublicComparison.Session session, String profileName,
            String nativeProfile, BufferedWriter writer) throws Exception {
        Class<?> profileClass = Class.forName(EnumoPublicComparison.class.getName() + "$Profile");
        Object profile = java.util.Arrays.stream(profileClass.getEnumConstants())
            .filter(value -> value.toString().equals(profileName)).findFirst().orElseThrow();
        var method = EnumoPublicComparison.class.getDeclaredMethod("query", EnumoPublicComparison.Session.class,
            profileClass, String.class, FpcorePolynomialCorpus.Entry.class, Map.class, BufferedWriter.class);
        method.setAccessible(true);
        var entry = new FpcorePolynomialCorpus.Entry(1, "controlled", SOURCE,
            FpcorePolynomialCorpus.Status.SUPPORTED, "EXACT_RATIONAL_PROJECTION");
        var row = new TreeMap<String, Object>();
        row.put("ordinal", 1); row.put("source", SOURCE);
        try { method.invoke(null, session, profile, nativeProfile, entry, row, writer); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
        return row;
    }

    private static String nativeReply(String input, String output, boolean withinBudget) throws IOException {
        return JSON.writeValueAsString(Map.of("status", "CANDIDATE", "input", input, "output", output,
            "totalWork", 13, "internalWorkWithinBudget", withinBudget));
    }

    private Worker worker(String mode, String response) throws IOException {
        Path directory = Files.createTempDirectory(temp, "worker-");
        Path requests = directory.resolve("requests.jsonl");
        Path pid = directory.resolve("worker.pid");
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx64m", "-cp", System.getProperty("java.class.path"), ControlledWorker.class.getName(), mode,
            Base64.getEncoder().encodeToString(response.getBytes(StandardCharsets.UTF_8)),
            requests.toString(), pid.toString());
        return new Worker(command, requests, pid, directory.resolve("stderr"));
    }

    private Inputs frozenInputs() throws IOException {
        Path checkout = Files.createTempDirectory(temp, "checkout-");
        Path relative = Path.of("research/benchmarks/enumo-public-v1");
        Files.createDirectories(checkout.resolve(relative));
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot", "..")).toAbsolutePath();
        for (String file : List.of("rosa.fpcore", "protocol.json")) {
            Files.copy(root.resolve(relative).resolve(file), checkout.resolve(relative).resolve(file),
                StandardCopyOption.REPLACE_EXISTING);
        }
        return new Inputs(checkout, checkout.resolve("rules"), checkout.resolve("new-report"));
    }

    private record Inputs(Path checkout, Path rules, Path output) {
        String[] arguments() {
            return new String[] { checkout.toString(),
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), rules.toString(), output.toString() };
        }
    }

    private record Worker(List<String> command, Path requests, Path pid, Path stderr) {
        EnumoPublicComparison.Session start() throws Exception {
            return new EnumoPublicComparison.Session(command, stderr, System.nanoTime() + seconds(10));
        }
    }

    private static void assertStopped(Worker worker) throws IOException {
        long pid = Long.parseLong(Files.readString(worker.pid()));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Worker must be reaped");
    }

    private static long seconds(long value) { return TimeUnit.SECONDS.toNanos(value); }

    public static final class ControlledWorker {
        private ControlledWorker() { }
        public static void main(String[] args) throws Exception {
            String mode = args[0];
            String response = new String(Base64.getDecoder().decode(args[1]), StandardCharsets.UTF_8);
            Files.writeString(Path.of(args[3]), Long.toString(ProcessHandle.current().pid()));
            System.out.println(switch (mode) {
                case "not-ready" -> "{\"status\":\"STOPPED\"}";
                case "duplicate-ready" -> "{\"status\":\"READY\",\"status\":\"READY\"}";
                default -> "{\"status\":\"READY\"}";
            });
            try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String request;
                while ((request = input.readLine()) != null) {
                    Files.writeString(Path.of(args[2]), request + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    if (mode.equals("closed")) return;
                    if (mode.equals("silent")) { Thread.sleep(30_000); continue; }
                    if (mode.equals("oversized")) {
                        for (int block = 0; block < 1024; block++) System.out.print("x".repeat(8192));
                        System.out.flush();
                        continue;
                    }
                    System.out.println(mode.equals("echo") ? request : response);
                }
            }
        }
    }
}
