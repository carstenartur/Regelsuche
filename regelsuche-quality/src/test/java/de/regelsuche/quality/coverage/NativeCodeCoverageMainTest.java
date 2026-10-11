package de.regelsuche.quality.coverage;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;

class NativeCodeCoverageMainTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOKEN = "private-main-test-credential";
    @TempDir Path root;
    private final Map<String, String> env = new HashMap<>();
    private final List<JsonNode> uploads = new CopyOnWriteArrayList<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String head, measured, tree;
    private int uploadStatus = 201;
    private String uploadResponse = "{\"id\":17}";

    @BeforeEach void prepareActualSourceAndCoverage() throws Exception {
        write("module-a/src/main/java/p/A.java", "package p; class A {\nint f() { return 1; }\n}\n");
        write(".gitignore", "**/build/\n");
        write("config/quality/coverage-policy.json", "{\"schema\":\"regelsuche.quality.coverage-policy/v1\",\"modules\":{\"module-a\":{}}}");
        git("init", "-q"); git("add", "."); commit();
        head = git("rev-parse", "HEAD");
        git("-c", "user.name=Coverage test", "-c", "user.email=coverage@example.invalid", "-c", "commit.gpgsign=false", "commit", "-q", "--allow-empty", "-m", "same-tree measurement");
        measured = git("rev-parse", "HEAD"); tree = git("rev-parse", "HEAD^{tree}");
        producers();
        // The covered constructor and missed method each contribute one unit of complexity.
        write("module-a/build/reports/jacoco/test/jacocoTestReport.xml", """
            <report name="fixture"><package name="p"><sourcefile name="A.java">
            <line nr="1" mi="0" ci="1" mb="0" cb="0"/><line nr="2" mi="1" ci="0" mb="0" cb="0"/>
            <counter type="LINE" missed="1" covered="1"/><counter type="COMPLEXITY" missed="1" covered="1"/></sourcefile></package>
            <counter type="LINE" missed="1" covered="1"/><counter type="BRANCH" missed="0" covered="0"/>
            <counter type="COMPLEXITY" missed="1" covered="1"/></report>
            """);
        write("build/reports/quality/coverage-report.json", """
            {"schema":"regelsuche.quality.coverage-report/v1","status":"PASSED","violations":[],
            "aggregate":{"line":{"missed":1,"covered":1},"branch":{"missed":0,"covered":0}},
            "modules":[{"module":"module-a","line":{"missed":1,"covered":1},"branch":{"missed":0,"covered":0}}]}
            """);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod());
            boolean put = exchange.getRequestMethod().equals("PUT");
            if (put) uploads.add(JSON.readTree(exchange.getRequestBody()));
            byte[] body = (put ? uploadResponse : "{\"processing_status\":\"succeeded\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(put ? uploadStatus : 200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        env.putAll(Map.of("GITHUB_EVENT_NAME", "pull_request", "GITHUB_REPOSITORY", "owner/repository",
            "GITHUB_SHA", measured, "GITHUB_REF", "refs/pull/1087/merge", "GITHUB_TOKEN", TOKEN,
            "GITHUB_API_URL", "http://127.0.0.1:" + server.getAddress().getPort(), "GITHUB_EVENT_PATH", root.resolve("event.json").toString()));
        event(true);
    }

    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    @Test void uploadsActualExporterOutputForHeadWithIdenticalMeasuredTree() throws Exception {
        write("build/independent-reproduction/module-a/src/main/java/p/A.java", "class GeneratedEvidenceCopy {}\n");
        assertNotEquals(head, measured);
        assertEquals(0, run());
        assertEquals(head, uploads.getFirst().path("commit_oid").asText());
        assertEquals(1087, uploads.getFirst().path("pull_request_number").asInt());
        byte[] transmitted;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(uploads.getFirst().path("coverage_report").asText())))) {
            transmitted = gzip.readAllBytes();
        }
        assertArrayEquals(Files.readAllBytes(output().resolve("coverage.cobertura.xml")), transmitted);
        var xml = DocumentBuilderFactory.newDefaultInstance();
        xml.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var report = xml.newDocumentBuilder().parse(new ByteArrayInputStream(transmitted));
        assertEquals("2", report.getDocumentElement().getAttribute("complexity"));
        assertEquals(1, report.getElementsByTagName("class").getLength());
        assertEquals("module-a/src/main/java/p/A.java", ((Element) report.getElementsByTagName("class").item(0)).getAttribute("filename"));
        var lines = report.getElementsByTagName("line");
        assertEquals(2, lines.getLength());
        assertEquals("1", ((Element) lines.item(0)).getAttribute("number"));
        assertEquals("1", ((Element) lines.item(0)).getAttribute("hits"));
        assertEquals("2", ((Element) lines.item(1)).getAttribute("number"));
        assertEquals("0", ((Element) lines.item(1)).getAttribute("hits"));
        assertEquals("PROCESSED", outcome().path("outcome").asText());
        assertEquals(measured, outcome().path("measuredCommit").asText());
        assertEquals(tree, outcome().path("measuredTree").asText());
        assertEquals(head, outcome().path("commit").asText());
        assertEquals(1, outcome().path("coveredLines").asLong());
        assertEquals(2, outcome().path("validLines").asLong());
        assertTrue(Files.isRegularFile(output().resolve("coverage.cobertura.xml")));
        assertTrue(Files.isRegularFile(output().resolve("coverage-manifest.json")));
        assertFalse(Files.readString(output().resolve("native-coverage-outcome.json")).contains(TOKEN));
    }

    @Test void mainPushUsesPushedCommitAndDefaultBranchRef() throws Exception {
        env.put("GITHUB_EVENT_NAME", "push"); env.put("GITHUB_REF", "refs/heads/main"); event(false);
        assertEquals(0, run());
        assertEquals(measured, uploads.getFirst().path("commit_oid").asText());
        assertEquals("refs/heads/main", uploads.getFirst().path("ref").asText());
        assertFalse(uploads.getFirst().has("pull_request_number"));
    }

    @ParameterizedTest @ValueSource(strings = {"gradle-source.txt", "sympy-source.txt"})
    void staleProducerCommitCannotBeRelabeled(String receipt) throws Exception {
        write(receipt, head + "\n" + tree + "\n");
        rejected();
        assertTrue(outcome().path("error").asText().contains("producer"));
    }

    @Test void distinctHeadTreeCannotReceiveMergeCoverage() throws Exception {
        Files.writeString(root.resolve("module-a/src/main/java/p/A.java"), "package p; class A { int f() { return 2; } }\n");
        git("add", "."); commit(); measured = git("rev-parse", "HEAD"); tree = git("rev-parse", "HEAD^{tree}");
        env.put("GITHUB_SHA", measured); producers();
        rejected();
        assertTrue(outcome().path("error").asText().contains("tree"));
    }

    @Test void changedTrackedSourceCannotUsePreviouslyMeasuredCoverage() throws Exception {
        Files.writeString(root.resolve("module-a/src/main/java/p/A.java"), "package p; class A { int f() { return 2; } }\n");
        rejected();
        assertTrue(outcome().path("error").asText().contains("inputs"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void untrackedCoveredSourceCannotBeAttributedToMeasuredCommit(boolean ignored) throws Exception {
        write("module-a/src/main/java/p/B.java", "package p; class B {\nint f() { return 1; }\n}\n");
        if (ignored) write(".git/info/exclude", "module-a/src/main/java/p/B.java\n");
        Path report = root.resolve("module-a/build/reports/jacoco/test/jacocoTestReport.xml");
        Files.writeString(report, Files.readString(report).replace("A.java", "B.java"));
        rejected();
        assertTrue(outcome().path("error").asText().contains("Untracked Java"));
    }

    @ParameterizedTest @ValueSource(strings = {"GITHUB_SHA", "GITHUB_EVENT_PATH", "GITHUB_TOKEN"})
    void missingRequiredMetadataFailsBeforeRequest(String key) throws Exception {
        env.remove(key); rejected();
        assertTrue(outcome().path("error").asText().contains(key));
    }

    @ParameterizedTest @ValueSource(strings = {"create", "merge_group", "workflow_dispatch", "pull_request_target"})
    void unsupportedContextsRetainExplicitSkipWithoutCredentials(String event) throws Exception {
        env.put("GITHUB_EVENT_NAME", event); env.remove("GITHUB_TOKEN"); env.remove("GITHUB_EVENT_PATH");
        assertEquals(0, run());
        assertEquals("SKIPPED", outcome().path("outcome").asText());
        assertTrue(uploads.isEmpty());
        assertFalse(Files.exists(output().resolve("coverage.cobertura.xml")));
    }

    @Test void forkContextIsSkippedBeforeProducerFilesOrCredentialsAreUsed() throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree(root.resolve("event.json").toFile());
        ((ObjectNode) event.path("pull_request").path("head").path("repo")).put("full_name", "fork/repository");
        JSON.writeValue(root.resolve("event.json").toFile(), event);
        env.remove("GITHUB_TOKEN"); Files.delete(root.resolve("gradle-source.txt"));
        assertEquals(0, run());
        assertEquals("SKIPPED", outcome().path("outcome").asText());
        assertTrue(uploads.isEmpty());
    }

    @Test void nativeStorageSkipCannotPassTheCurrentHeadGate() throws Exception {
        uploadStatus = 200; uploadResponse = "{\"message\":\"no longer latest\"}";
        assertEquals(1, run());
        assertEquals("SKIPPED", outcome().path("outcome").asText());
        assertEquals(1, uploads.size());
    }

    @Test void nativeFailureRetainsSanitizedOutcomeAndDiagnostics() throws Exception {
        uploadStatus = 403; uploadResponse = "{\"message\":\"denied " + TOKEN + "\"}";
        var diagnostics = new ByteArrayOutputStream(); PrintStream previous = System.err;
        try (var capture = new PrintStream(diagnostics, true, StandardCharsets.UTF_8)) {
            System.setErr(capture); assertEquals(1, run());
        } finally { System.setErr(previous); }
        assertEquals("FAILED", outcome().path("outcome").asText());
        assertFalse(Files.readString(output().resolve("native-coverage-outcome.json")).contains(TOKEN));
        assertFalse(diagnostics.toString(StandardCharsets.UTF_8).contains(TOKEN));
    }

    private int run() throws Exception {
        long[] clock = {0};
        return NativeCodeCoverageMain.run(new String[]{"--root", root.toString(), "--gradle-source", "gradle-source.txt",
            "--sympy-source", "sympy-source.txt", "--output", "native-output"}, env,
            new NativeCodeCoverageClient(() -> clock[0], delay -> clock[0] += delay.toNanos()));
    }
    private void rejected() throws Exception {
        assertEquals(1, run()); assertTrue(requests.isEmpty()); assertEquals("FAILED", outcome().path("outcome").asText());
    }
    private Path output() { return root.resolve("native-output"); }
    private JsonNode outcome() throws Exception { return JSON.readTree(output().resolve("native-coverage-outcome.json").toFile()); }
    private void producers() throws Exception { write("gradle-source.txt", measured + "\n" + tree + "\n"); write("sympy-source.txt", measured + "\n" + tree + "\n"); }
    private void event(boolean pr) throws Exception {
        ObjectNode event = JSON.createObjectNode();
        event.putObject("repository").put("full_name", "owner/repository").put("default_branch", "main");
        if (pr) {
            var request = event.putObject("pull_request").put("number", 1087);
            request.putObject("head").put("sha", head).putObject("repo").put("full_name", "owner/repository");
        }
        JSON.writeValue(root.resolve("event.json").toFile(), event);
    }
    private void write(String path, String contents) throws Exception {
        Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, contents);
    }
    private void commit() throws Exception { git("-c", "user.name=Coverage test", "-c", "user.email=coverage@example.invalid", "-c", "commit.gpgsign=false", "commit", "-qm", "fixture"); }
    private String git(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString())); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.waitFor(), result); return result;
    }
}
