package de.regelsuche.build;

import static de.regelsuche.build.MavenPomTestSupport.repositoryRoot;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Standalone SDK archive and transitional public API checks. */
class MavenOptimizationSdkPackagingContractTest {
    @TempDir
    Path temporary;

    @Test
    void apiGateRejectsMissingFacadeAndUnsupportedRevision() throws Exception {
        String policy = """
            {"optimizationApiVersion":"1",
             "optimizationSemanticsRevision":"java25-numeric/v1",
             "stablePackages":["de.regelsuche.sdk.optimization"],
             "baselineCompatibilityPackages":[]}
            """;
        String invoke = """
            import json, runpy, sys
            adapter = runpy.run_path(sys.argv[1])
            adapter['validate_optimization_revision'](json.loads(sys.argv[2]), [])
            """;

        ProcessResult missing = runAdapter("verify-sdk-api-compatibility.py", invoke, policy);
        assertNotEquals(0, missing.exitCode());
        assertTrue(missing.output().contains("exactly one optimizer binary API artifact is required"),
            missing.output());

        ProcessResult unsupported = runAdapter("verify-sdk-api-compatibility.py", invoke,
            policy.replace("\"optimizationApiVersion\":\"1\"", "\"optimizationApiVersion\":\"999\""));
        assertNotEquals(0, unsupported.exitCode());
        assertTrue(unsupported.output().contains("optimizer API/semantics revision missing or unsupported"),
            unsupported.output());
    }

    @Test
    void shadingIsReproducibleMergesServicesAndRejectsConflictingClasses() throws Exception {
        Path first = temporary.resolve("first.jar");
        Path second = temporary.resolve("second.jar");
        Map<String, String> firstEntries = new LinkedHashMap<>();
        firstEntries.put("example/First.class", "first");
        firstEntries.put("META-INF/services/example.Service", "example.First\n");
        firstEntries.put("META-INF/TEST.SF", "stale signature");
        writeJar(first, firstEntries);
        Map<String, String> secondEntries = new LinkedHashMap<>();
        secondEntries.put("example/Second.class", "second");
        secondEntries.put("META-INF/services/example.Service", "example.Second\n");
        writeJar(second, secondEntries);

        Path a = temporary.resolve("a.jar");
        Path b = temporary.resolve("b.jar");
        OptimizationSdkArchive.writeZip(a, OptimizationSdkArchive.shade(List.of(first, second)));
        OptimizationSdkArchive.writeZip(b, OptimizationSdkArchive.shade(List.of(second, first)));
        assertArrayEquals(Files.readAllBytes(a), Files.readAllBytes(b));
        try (ZipFile archive = new ZipFile(a.toFile())) {
            assertEquals("example.First\nexample.Second\n", new String(
                archive.getInputStream(archive.getEntry("META-INF/services/example.Service")).readAllBytes(),
                StandardCharsets.UTF_8));
            assertFalse(archive.stream().anyMatch(entry -> entry.getName().equals("META-INF/TEST.SF")));
        }

        secondEntries.put("example/First.class", "conflicting bytecode");
        writeJar(second, secondEntries);
        IOException conflict = assertThrows(IOException.class, () -> OptimizationSdkArchive.shade(List.of(first, second)));
        assertTrue(conflict.getMessage().contains("duplicate class with different bytecode"), conflict.getMessage());
    }

    @Test
    void shadingRejectsUnsafeMembersAndApplicationDependencies() throws IOException {
        Path input = temporary.resolve("input.jar");
        writeJar(input, Map.of("../outside", "entry"));
        assertTrue(assertThrows(IOException.class, () -> OptimizationSdkArchive.shade(List.of(input)))
            .getMessage().contains("unsafe archive entry"));
        writeJar(input, Map.of("org/eclipse/Unsafe.class", "entry"));
        assertTrue(assertThrows(IOException.class, () -> OptimizationSdkArchive.shade(List.of(input)))
            .getMessage().contains("forbidden SDK runtime dependency"));
        writeJar(input, Map.of("META-INF/versions/25/org/eclipse/Unsafe.class", "entry"));
        assertTrue(assertThrows(IOException.class, () -> OptimizationSdkArchive.shade(List.of(input)))
            .getMessage().contains("forbidden SDK runtime dependency"));
    }

    @Test
    void shadingRejectsInvalidUtf8ServiceDescriptors() throws IOException {
        Path input = temporary.resolve("input.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(input))) {
            zip.putNextEntry(new ZipEntry("META-INF/services/example.Service"));
            zip.write(new byte[] {(byte) 0xc3});
        }
        assertThrows(MalformedInputException.class, () -> OptimizationSdkArchive.shade(List.of(input)));
    }

    private ProcessResult runAdapter(String script, String invocation, String... arguments)
            throws Exception {
        Path root = repositoryRoot();
        String python = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")
            ? "python" : "python3";
        List<String> command = new ArrayList<>(List.of(python, "-B", "-c", invocation,
            root.resolve("scripts").resolve(script).toString()));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile(temporary, "adapter-", ".log");
        Process process = new ProcessBuilder(command).directory(root.toFile())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "build adapter exceeded 30 seconds");
            return new ProcessResult(process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void writeJar(Path path, Map<String, String> entries) throws IOException {
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                archive.putNextEntry(new ZipEntry(entry.getKey()));
                archive.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                archive.closeEntry();
            }
        }
    }

    private record ProcessResult(int exitCode, String output) { }
}
