package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Recorder contracts and retained evidence; no original compiler or benchmark rerun. */
class ExternalPolyhedralContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test
    void replaysTheCompleteRetainedMatrixThroughThePublicEntryPoint() throws Exception {
        Path evidence = retained();
        ExternalPolyhedralComparison.main(new String[]{"--verify", checkout().toString(), evidence.toString()});
        JsonNode rows = JSON.readTree(evidence.resolve("results.json").toFile()).path("rows");
        assertEquals(20, rows.size());
        assertEquals(3, java.util.stream.StreamSupport.stream(rows.spliterator(), false)
            .filter(row -> row.path("judgment").asText().equals("VERIFIED_FINITE_OUTPUTS")).count());
    }

    @Test
    void rejectsMissingAndDuplicateMatrixRowsEvenAfterRehashingTheReport() throws Exception {
        Path evidence = retained();
        ObjectNode original = (ObjectNode) JSON.readTree(evidence.resolve("results.json").toFile());
        for (boolean duplicate : List.of(false, true)) {
            ObjectNode changed = original.deepCopy();
            ArrayNode rows = (ArrayNode) changed.path("rows");
            if (duplicate) rows.add(rows.get(0).deepCopy()); else rows.remove(0);
            replaceEvidence(evidence, "results.json", changed);
            assertThrows(IllegalArgumentException.class, () -> ExternalPolyhedralComparison.audit(checkout(), evidence));
        }
    }

    @Test
    void rejectsConsistentlyForgedRawAndSummarizedNativeValuesAgainstTheSource() throws Exception {
        Path evidence = retained();
        ObjectNode report = (ObjectNode) JSON.readTree(evidence.resolve("results.json").toFile());
        ObjectNode response = (ObjectNode) JSON.readTree(evidence.resolve("prefix_sum/native.json").toFile());
        ((ArrayNode) response.path("outputs").get(0).path("values")).set(0, JSON.getNodeFactory().textNode("999"));
        nativeRow(report).set("response", response);
        replaceEvidence(evidence, "prefix_sum/native.json", response);
        replaceEvidence(evidence, "results.json", report);
        assertThrows(IllegalArgumentException.class, () -> ExternalPolyhedralComparison.audit(checkout(), evidence));
    }

    @Test
    void rejectsAnOverBudgetNativeReceiptEvenWhenRawAndSummaryAgree() throws Exception {
        Path evidence = retained();
        ObjectNode report = (ObjectNode) JSON.readTree(evidence.resolve("results.json").toFile());
        ObjectNode response = (ObjectNode) JSON.readTree(evidence.resolve("prefix_sum/native.json").toFile());
        long limit = ExternalPolyhedralComparison.protocol(checkout()).at("/limits/nativeWork").longValue();
        response.put("totalNativeWork", limit + 1);
        nativeRow(report).set("response", response);
        replaceEvidence(evidence, "prefix_sum/native.json", response);
        replaceEvidence(evidence, "results.json", report);
        assertThrows(IllegalArgumentException.class, () -> ExternalPolyhedralComparison.audit(checkout(), evidence));
    }

    @Test
    void refusesATimedOutOriginalExecutionDespiteRetainedCorrectNumbers() throws Exception {
        Path evidence = retained();
        ObjectNode report = (ObjectNode) JSON.readTree(evidence.resolve("results.json").toFile());
        for (JsonNode row : report.path("rows")) if (row.path("case").asText().equals("prefix_sum")
                && row.path("arm").asText().equals("ORIGINAL_ALPHA_COMPILER")) {
            ((ObjectNode) row.path("executions").get(0)).put("timedOut", true);
        }
        replaceEvidence(evidence, "results.json", report);
        assertThrows(IllegalArgumentException.class, () -> ExternalPolyhedralComparison.audit(checkout(), evidence));
    }

    @Test
    void nativeEntryPointEvaluatesPowersAndSubtractionAndAccountsEveryPhase() throws Exception {
        JsonNode response = nativeResponse("(X[j]-1)^2", 100_000);
        assertEquals("CANDIDATE", response.path("status").asText());
        assertEquals(JSON.valueToTree(List.of(output(2, 0, "100", "149"), output(2, 2, "16", "20"),
            output(3, 0, "100", "149", "174"), output(3, 2, "16", "20", "20"))), response.path("outputs"));
        long total = response.path("searchWork").longValue() + response.path("replayWork").longValue()
            + response.path("evaluationWork").longValue();
        assertEquals(total, response.path("totalNativeWork").longValue());
        assertTrue(total > 0 && total <= 100_000);
        assertTrue(response.path("nativeWallNanos").longValue() >= response.path("evaluationNanos").longValue());
    }

    @Test
    void nativeEntryPointRetainsUnsupportedAndExhaustedOutcomesWithoutInventingOutputs() throws Exception {
        JsonNode unsupported = nativeResponse("1/X[j]", 100_000);
        assertEquals("UNSUPPORTED", unsupported.path("status").asText());
        assertFalse(unsupported.has("outputs"));
        JsonNode searchExhausted = nativeResponse("X[j]", 1);
        assertEquals("NO_VERIFIED_PLAN", searchExhausted.path("status").asText());
        assertFalse(searchExhausted.has("outputs"));
        JsonNode evaluationExhausted = nativeResponse("X[j]", 60);
        assertEquals("ERROR", evaluationExhausted.path("status").asText());
        assertTrue(evaluationExhausted.path("reason").asText().contains("WORK_LIMIT"));
        assertFalse(evaluationExhausted.has("outputs"));
    }

    @Test
    void refusesToOverwriteAnExistingRunBeforeTouchingTheOriginalTool() throws Exception {
        Path output = Files.createDirectory(temporary.resolve("existing"));
        Path marker = output.resolve("retained.txt"); Files.writeString(marker, "retained evidence");
        assertThrows(IllegalArgumentException.class,
            () -> ExternalPolyhedralComparison.run(checkout(), temporary.resolve("absent-tool"), output));
        assertEquals("retained evidence", Files.readString(marker));
        assertFalse(Files.exists(output.resolve("results.json")));
    }

    /** These fixtures test the existing Unix/C adapter, never the original compiler's quality. */
    private void requiresTheAdaptersExistingTools() throws Exception {
        for (String tool : List.of("gcc", "timeout", "git")) {
            boolean available;
            try {
                var process = new ProcessBuilder(tool, "--version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                try { available = process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0; }
                finally { if (process.isAlive()) process.destroyForcibly(); }
            } catch (IOException unavailable) { available = false; }
            org.junit.jupiter.api.Assumptions.assumeTrue(available, "Unix/C adapter requires " + tool);
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void executesGeneratedCAndRetainsBothAgreementAndDisagreement() throws Exception {
        requiresTheAdaptersExistingTools();
        String correct = "void fixture(long n,long*x,long*y){long s=0;for(long i=0;i<=n;i++){s+=x[i];y[i]=s;}}";
        Map<String, Object> accepted = originalStage(correct, true, 0);
        assertEquals("SIMPLIFIED", accepted.get("candidateKind"));
        assertEquals("VERIFIED_FINITE_OUTPUTS", accepted.get("judgment"));
        assertEquals(prefixOutputs(), accepted.get("outputs"));
        Map<String, Object> rejected = originalStage("void fixture(long n,long*x,long*y){for(long i=0;i<=n;i++)y[i]=0;}", false, 0);
        assertEquals("BASELINE_FALLBACK", rejected.get("candidateKind"));
        assertEquals("INVALID_OUTPUT", rejected.get("judgment"));
        assertNotEquals(prefixOutputs(), rejected.get("outputs"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void distinguishesFrontendCompilationAndExecutionFailures() throws Exception {
        requiresTheAdaptersExistingTools();
        Map<String, Object> frontend = originalStage(null, false, 7);
        assertEquals("EXIT_NONZERO", frontend.get("synthesisStatus"));
        assertEquals("NO_GENERATED_CANDIDATE", frontend.get("judgment"));
        assertFalse(frontend.containsKey("outputs"));
        Map<String, Object> compilation = originalStage("not valid C", false, 0);
        assertEquals("COMPILATION_FAILED", compilation.get("judgment"));
        assertFalse(compilation.containsKey("outputs"));
        Map<String, Object> execution = originalStage("#include <stdlib.h>\nvoid fixture(long n,long*x,long*y){exit(17);}", false, 0);
        assertEquals("EXECUTION_FAILED", execution.get("judgment"));
        var attempts = (List<?>) execution.get("executions");
        assertEquals(1, attempts.size());
        assertEquals(17, ((ExternalPolyhedralComparison.Execution) attempts.getFirst()).exit());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void recordsTheActualNativeSubprocessAndChecksItsValues() throws Exception {
        requiresTheAdaptersExistingTools();
        Path directory = Files.createDirectory(temporary.resolve("native-stage"));
        Path source = directory.resolve("input.alpha"); Files.writeString(source, prefix("X[j]"));
        ObjectNode panel = panel(100_000); JSON.writeValue(directory.resolve("request.json").toFile(), panel);
        Map<String, Object> row = invoke("nativeRow",
            new Class<?>[]{JsonNode.class, JsonNode.class, Path.class, Path.class, Path.class, Path.class, List.class, long.class},
            entry(source), panel, checkout(), directory, directory, source, prefixOutputs(), 0L);
        assertEquals("VERIFIED_FINITE_OUTPUTS", row.get("judgment"));
        assertEquals(0, ((ExternalPolyhedralComparison.Execution) row.get("process")).exit());
        assertEquals(JSON.valueToTree(prefixOutputs()), ((JsonNode) row.get("response")).path("outputs"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rejectsAnUnpinnedUpstreamBeforeReadingItsCompilerOrInputs() throws Exception {
        requiresTheAdaptersExistingTools();
        Path upstream = Files.createDirectory(temporary.resolve("upstream"));
        assertEquals(0, new ProcessBuilder("git", "init", "-q", upstream.toString()).start().waitFor());
        assertEquals(0, new ProcessBuilder("git", "-C", upstream.toString(), "-c", "user.name=Fixture",
            "-c", "user.email=fixture@example.invalid", "commit", "-q", "--allow-empty", "-m", "Fixture").start().waitFor());
        Path output = temporary.resolve("rejected-run");
        var error = assertThrows(IllegalArgumentException.class, () -> ExternalPolyhedralComparison.run(checkout(), upstream, output));
        assertEquals("UPSTREAM_REVISION", error.getMessage());
        assertTrue(Files.isRegularFile(output.resolve("upstream-revision.stdout")));
        assertFalse(Files.exists(output.resolve("request.json")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void generatedCOutsideTheCommonSourceFragmentIsNotAComparableCandidate() throws Exception {
        requiresTheAdaptersExistingTools();
        var row = originalStage(prefix("1/X[j]"), "void fixture(long n,long*x,long*y){}", false,
            "exit 0", panel(100_000));
        assertEquals("COMPLETED", row.get("synthesisStatus"));
        assertFalse(((List<?>) row.get("generatedSources")).isEmpty());
        assertEquals("OUTSIDE_COMMON_FRAGMENT", row.get("judgment"));
        assertFalse(row.containsKey("compileProcess"));
        assertFalse(row.containsKey("candidateSha256"));
        assertFalse(row.containsKey("outputs"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void synthesisTimeoutDoesNotPromoteAnAlreadyWrittenCFile() throws Exception {
        requiresTheAdaptersExistingTools();
        ObjectNode panel = panel(100_000); ((ObjectNode) panel.path("limits")).put("synthesisSeconds", 1);
        var row = originalStage(prefix("X[j]"), "void fixture(long n,long*x,long*y){}", false,
            "while :; do :; done", panel);
        assertFalse(((List<?>) row.get("generatedSources")).isEmpty());
        assertTrue(((ExternalPolyhedralComparison.Execution) row.get("synthesisProcess")).timedOut());
        assertEquals("TIMEOUT", row.get("synthesisStatus"));
        assertEquals("TIMEOUT", row.get("judgment"));
        assertFalse(row.containsKey("compileProcess"));
        assertFalse(row.containsKey("outputs"));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void missingNativeSourceRemainsAProcessFailureWithoutAResponse() throws Exception {
        requiresTheAdaptersExistingTools();
        Path directory = Files.createDirectory(temporary.resolve("missing-native-source"));
        Path source = directory.resolve("input.alpha"); Files.writeString(source, prefix("X[j]"));
        JsonNode entry = entry(source); Files.delete(source);
        ObjectNode panel = panel(100_000); JSON.writeValue(directory.resolve("request.json").toFile(), panel);
        var row = invoke("nativeRow",
            new Class<?>[]{JsonNode.class, JsonNode.class, Path.class, Path.class, Path.class, Path.class, List.class, long.class},
            entry, panel, checkout(), directory, directory, source, prefixOutputs(), 0L);
        assertEquals("PROCESS_FAILURE", row.get("judgment"));
        var process = (ExternalPolyhedralComparison.Execution) row.get("process");
        assertNotEquals(0, process.exit());
        assertFalse(process.timedOut());
        assertFalse(row.containsKey("response"));
        assertFalse(Files.exists(directory.resolve("native.json")));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void bindsASyntheticUpstreamToItsRevisionArtifactsSourcesAndInventory() throws Exception {
        requiresTheAdaptersExistingTools();
        Path upstream = Files.createDirectory(temporary.resolve("synthetic-upstream"));
        Path tool = Files.createDirectories(upstream.resolve("lib")).resolve("fixture.jar");
        Path source = Files.createDirectories(upstream.resolve("inputs")).resolve("fixture.alpha");
        Files.writeString(tool, "TEST_ONLY_ADAPTER_FIXTURE: hash input, never an executed compiler");
        Files.writeString(source, prefix("X[j]"));
        assertEquals(0, new ProcessBuilder("git", "init", "-q", upstream.toString()).start().waitFor());
        assertEquals(0, new ProcessBuilder("git", "-C", upstream.toString(), "add", "lib", "inputs").start().waitFor());
        assertEquals(0, new ProcessBuilder("git", "-C", upstream.toString(), "-c", "user.name=Fixture",
            "-c", "user.email=fixture@example.invalid", "commit", "-q", "-m", "Fixture").start().waitFor());
        var revision = new ProcessBuilder("git", "-C", upstream.toString(), "rev-parse", "HEAD").start();
        String head = new String(revision.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, revision.waitFor());
        ObjectNode panel = JSON.createObjectNode().put("revision", head);
        panel.putObject("toolHashes").put("fixture.jar", ExternalPolyhedralComparison.hash(Files.readAllBytes(tool)));
        panel.putArray("cases").addObject().put("path", "inputs/fixture.alpha")
            .put("sha256", ExternalPolyhedralComparison.hash(Files.readAllBytes(source)));
        verifyUpstreamFixture(panel, upstream);
        ObjectNode changedTool = panel.deepCopy();
        ((ObjectNode) changedTool.path("toolHashes")).put("fixture.jar", "0".repeat(64));
        assertEquals("ORIGINAL_JAR_CHANGED", assertThrows(IllegalArgumentException.class,
            () -> verifyUpstreamFixture(changedTool, upstream)).getMessage());
        ObjectNode changedSource = panel.deepCopy();
        ((ObjectNode) changedSource.path("cases").get(0)).put("sha256", "0".repeat(64));
        assertEquals("UPSTREAM_SOURCE_CHANGED", assertThrows(IllegalArgumentException.class,
            () -> verifyUpstreamFixture(changedSource, upstream)).getMessage());
        ObjectNode missingSource = panel.deepCopy(); missingSource.putArray("cases");
        assertEquals("UPSTREAM_INVENTORY_CHANGED", assertThrows(IllegalArgumentException.class,
            () -> verifyUpstreamFixture(missingSource, upstream)).getMessage());
        Files.writeString(source, prefix("X[j]+1"));
        assertTrue(assertThrows(IllegalStateException.class, () -> verifyUpstreamFixture(panel, upstream))
            .getMessage().startsWith("COMMAND_FAILED"));
    }

    private void verifyUpstreamFixture(JsonNode panel, Path upstream) throws Exception {
        invoke("verifyUpstream", new Class<?>[]{JsonNode.class, Path.class, Path.class},
            panel, upstream, Files.createTempDirectory(temporary, "upstream-verification-"));
    }

    private Map<String, Object> originalStage(String c, boolean simplified, int exit) throws Exception {
        return originalStage(prefix("X[j]"), c, simplified, "exit " + exit, panel(100_000));
    }

    private Map<String, Object> originalStage(String alpha, String c, boolean simplified, String finish,
            JsonNode panel) throws Exception {
        Path directory = Files.createTempDirectory(temporary, "original-stage-");
        Path upstream = Files.createDirectory(directory.resolve("tool"));
        Path source = directory.resolve("input.alpha"); Files.writeString(source, alpha);
        String subdirectory = simplified ? "fixture/simplifications/v0" : "fixture";
        String script = "#!/bin/sh\n";
        if (c != null) script += "mkdir -p \"$2/" + subdirectory + "\"\ncat > \"$2/" + subdirectory
            + "/fixture.c\" <<'FIXTURE_C'\n" + c + "\nFIXTURE_C\n";
        Path command = upstream.resolve("acc"); Files.writeString(command, script + finish + "\n");
        assertTrue(command.toFile().setExecutable(true));
        Optional<ExternalPolyhedralComparison.Imported> imported;
        try { imported = Optional.of(ExternalPolyhedralComparison.parse(alpha)); }
        catch (IllegalArgumentException unsupported) { imported = Optional.empty(); }
        return invoke("externalRow", new Class<?>[]{JsonNode.class, JsonNode.class, Path.class, Path.class,
            Path.class, Optional.class, List.class, long.class}, entry(source), panel, upstream,
            directory, source, imported, imported.isPresent() ? prefixOutputs() : List.of(), 0L);
    }

    private JsonNode nativeResponse(String term, long budget) throws Exception {
        Path directory = Files.createTempDirectory(temporary, "native-");
        Path source = directory.resolve("input.alpha"), request = directory.resolve("request.json"), response = directory.resolve("response.json");
        Files.writeString(source, prefix(term)); JSON.writeValue(request.toFile(), panel(budget));
        ExternalPolyhedralComparison.main(new String[]{"--native", source.toString(), request.toString(), response.toString()});
        return JSON.readTree(response.toFile());
    }

    private static ObjectNode panel(long budget) throws Exception {
        ObjectNode panel = (ObjectNode) JSON.readTree("""
            {"limits":{"nativeWork":100000,"nativeTerms":128,"maxStates":4,"synthesisSeconds":30,
             "compileSeconds":15,"executionSeconds":5,"javaHeapMiB":512},"rowCounts":[2,3],"seeds":[0,2],
             "originalOptions":[],"compilerOptions":["-std=c11","-O0","-fsanitize=undefined","-fno-sanitize-recover=all"]}
            """);
        ((ObjectNode) panel.path("limits")).put("nativeWork", budget); return panel;
    }

    private static String prefix(String term) { return ExternalPolyhedralComparisonTest.prefix("fixture", term); }
    private static Map<String, Object> output(int count, int seed, String... values) {
        return Map.of("rows", count, "seed", seed, "values", List.of(values));
    }
    private static List<Map<String, Object>> prefixOutputs() {
        return List.of(output(2, 0, "-9", "-1"), output(2, 2, "5", "8"),
            output(3, 0, "-9", "-1", "5"), output(3, 2, "5", "8", "9"));
    }
    private static JsonNode entry(Path source) throws Exception {
        return JSON.valueToTree(Map.of("id", "fixture", "origin", "TEST_ONLY_ADAPTER_FIXTURE",
            "sha256", ExternalPolyhedralComparison.hash(Files.readAllBytes(source))));
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
        var method = ExternalPolyhedralComparison.class.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return (Map<String, Object>) method.invoke(null, arguments); }
        catch (InvocationTargetException failure) { throw (Exception) failure.getCause(); }
    }
    private static ObjectNode nativeRow(JsonNode report) {
        for (JsonNode row : report.path("rows")) if (row.path("case").asText().equals("prefix_sum")
                && row.path("arm").asText().equals("REGELSUCHE_ORDERED_PREFIX")) return (ObjectNode) row;
        throw new AssertionError("retained native prefix row missing");
    }
    private static void replaceEvidence(Path directory, String name, JsonNode value) throws Exception {
        JSON.writeValue(directory.resolve(name).toFile(), value);
        Path file = directory.resolve("manifest.json"); ObjectNode manifest = (ObjectNode) JSON.readTree(file.toFile());
        manifest.put(name, ExternalPolyhedralComparison.hash(Files.readAllBytes(directory.resolve(name))));
        JSON.writeValue(file.toFile(), manifest);
    }

    /** Only the regular files, directories and GNU long names of this retained fixture are admitted. */
    private Path retained() throws Exception {
        Path target = Files.createTempDirectory(temporary, "retained-"); String longName = null;
        try (var input = new GZIPInputStream(Files.newInputStream(checkout().resolve("docs/research/external-polyhedral-v1/evidence.tar.gz")))) {
            while (true) {
                byte[] header = input.readNBytes(512);
                if (header.length == 0 || header[0] == 0) break;
                if (header.length != 512) throw new IOException("truncated retained header");
                int size = Integer.parseInt(text(header, 124, 12).trim(), 8);
                if (size < 0 || size > 1_048_576) throw new IOException("retained fixture member bound");
                byte[] bytes = input.readNBytes(size);
                if (bytes.length != size) throw new IOException("truncated retained member");
                input.skipNBytes((512 - size % 512) % 512);
                if (header[156] == 'L') { longName = text(bytes, 0, bytes.length); continue; }
                Path file = target.resolve(longName == null ? text(header, 0, 100) : longName).normalize(); longName = null;
                if (!file.startsWith(target)) throw new IOException("retained fixture path escaped");
                if (header[156] == '5') Files.createDirectories(file);
                else if (header[156] == '0') { Files.createDirectories(file.getParent()); Files.write(file, bytes); }
                else throw new IOException("unexpected retained fixture member type");
            }
        }
        return target.resolve("regelsuche-polyhedral-v1-final");
    }
    private static String text(byte[] bytes, int offset, int length) {
        int end = offset; while (end < offset + length && bytes[end] != 0) end++;
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }
    private static Path checkout() { return Path.of(System.getProperty("regelsuche.repositoryRoot", "..")).toAbsolutePath(); }
}
