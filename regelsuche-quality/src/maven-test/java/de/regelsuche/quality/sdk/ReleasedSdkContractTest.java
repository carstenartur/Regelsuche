package de.regelsuche.quality.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleasedSdkContractTest {
    @TempDir Path tmp;

    @Test void checksumAndMemberBoundariesFailBeforeAnyExtraction() throws Exception {
        Path archive = tmp.resolve("sdk.zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("sdk/../outside"));
            output.write(1);
        }
        Path extracted = tmp.resolve("extracted");
        assertThrows(Exception.class, () -> ReleasedSdkArchive.extract(archive, extracted, "not-the-hash", 1, "sdk"));
        assertThrows(Exception.class, () -> ReleasedSdkArchive.extract(archive, extracted, ReleasedSdkArchive.digest(archive), 1, "sdk"));
        assertFalse(Files.exists(extracted));
        assertFalse(Files.exists(tmp.resolve("outside")));
    }

    @Test void missingAndSkippedSurefireCasesCannotQualify() throws Exception {
        Path xml = tmp.resolve("TEST-x.xml");
        Files.writeString(xml, "<testsuite tests='1' failures='0' errors='0' skipped='0'><testcase classname='x' name='works'/></testsuite>");
        assertEquals(1, ReleasedSdkChecks.surefire(tmp, Set.of("x#works")).get("tests"));
        assertThrows(Exception.class, () -> ReleasedSdkChecks.surefire(tmp, Set.of("x#other")));
        Files.writeString(xml, Files.readString(xml).replace("/></testsuite>", "><skipped/></testcase></testsuite>"));
        assertThrows(Exception.class, () -> ReleasedSdkChecks.surefire(tmp, Set.of("x#works")));
    }

    @Test void completeInternalChecksumInventoryBindsEveryExtractedFile() throws Exception {
        Path source = Files.writeString(tmp.resolve("source"), "released");
        String digest = ReleasedSdkArchive.digest(source);
        byte[] manifest = (digest + "  file.jar\r\n").getBytes(StandardCharsets.UTF_8);
        Path archive = tmp.resolve("sdk.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("sdk/SHA256SUMS.txt"));
            zip.write(manifest);
            zip.putNextEntry(new ZipEntry("sdk/file.jar"));
            zip.write(Files.readAllBytes(source));
        }
        String outer = ReleasedSdkArchive.digest(archive);
        assertThrows(Exception.class, () -> ReleasedSdkArchive.extract(archive, tmp.resolve("wrong-count"), outer, 2, "sdk"));
        assertFalse(Files.exists(tmp.resolve("wrong-count")));
        ReleasedSdkArchive.extract(archive, tmp.resolve("valid"), outer, 1, "sdk");
        assertEquals(-1L, Files.mismatch(source, tmp.resolve("valid/sdk/file.jar")));
        assertArrayEquals(manifest, Files.readAllBytes(tmp.resolve("valid/sdk/SHA256SUMS.txt")));
        Path symlink = tmp.resolve("link");
        Files.createSymbolicLink(symlink, archive.getFileName());
        assertThrows(Exception.class, () -> ReleasedSdkArchive.extract(symlink, tmp.resolve("linked"), outer, 1, "sdk"));
    }

    @Test void rejectsAnOversizeUnterminatedManifestWithoutReadingPastTheFirstExcessByte() {
        class UnterminatedManifest extends InputStream {
            private int delivered;

            @Override public int read(byte[] bytes, int offset, int length) {
                if (length == 0) return 0;
                int count = Math.min(length, (4 << 20) + 1 - delivered);
                if (count == 0) fail("read past the first byte beyond the 4 MiB manifest limit");
                Arrays.fill(bytes, offset, offset + count, (byte) 'x');
                delivered += count;
                return count;
            }

            @Override public int read() {
                byte[] bytes = new byte[1];
                read(bytes, 0, 1);
                return bytes[0];
            }
        }
        var stream = new UnterminatedManifest();
        assertThrows(IOException.class, () -> ReleasedSdkArchive.readChecksums(stream));
        assertEquals((4 << 20) + 1, stream.delivered);
    }

    @Test void malformedUtf8DoesNotGetReplacedInChecksumPaths() {
        byte[] bytes = ("a".repeat(64) + "  \u00e9.jar\n").getBytes(StandardCharsets.UTF_8);
        bytes[67] = 'x';
        assertThrows(CharacterCodingException.class,
            () -> ReleasedSdkArchive.readChecksums(new ByteArrayInputStream(bytes)));
    }

    @Test void rejectsFileUsedAsDirectoryBeforeWritingValidatedEntries() throws Exception {
        Path archive = tmp.resolve("conflict.zip");
        String sha = ReleasedSdkArchive.digest(Files.writeString(tmp.resolve("content"), "x"));
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("sdk/SHA256SUMS.txt"));
            zip.write((sha + "  conflict\n" + sha + "  conflict/file\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (String name : List.of("conflict", "conflict/file")) {
                zip.putNextEntry(new ZipEntry("sdk/" + name));
                zip.write('x');
            }
        }
        Path output = tmp.resolve("extracted");
        assertThrows(Exception.class, () -> ReleasedSdkArchive.extract(archive, output, ReleasedSdkArchive.digest(archive), 2, "sdk"));
        assertFalse(Files.exists(output));
    }

    @Test void rejectsArchiveSymlinkBeforeMakingTheDestination() throws Exception {
        Path archive = tmp.resolve("links.zip");
        try (var zip = new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(archive)) {
            var link = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("sdk/link");
            link.setUnixMode(0120777);
            zip.putArchiveEntry(link);
            zip.write("unsafe".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
        }
        Path output = tmp.resolve("bad");
        assertThrows(Exception.class, () -> ReleasedSdkArchive.extract(archive, output, ReleasedSdkArchive.digest(archive), 0, "sdk"));
        assertFalse(Files.exists(output));
    }

    @Test void closureBindsCopiedJarBytesAndAllReleasedModules() throws Exception {
        Path repository = Files.createDirectory(tmp.resolve("repo"));
        Path released = repository.resolve("de/regelsuche/regelsuche-core/0.5.2/regelsuche-core-0.5.2.jar");
        Files.createDirectories(released.getParent());
        Files.writeString(released, "immutable");
        Path deps = Files.createDirectory(tmp.resolve("deps"));
        Path actual = Files.copy(released, deps.resolve(released.getFileName()));
        Path graph = Files.writeString(tmp.resolve("graph.tgf"), "1 example:consumer:jar:1\n2 de.regelsuche:regelsuche-core:jar:0.5.2:compile\n#\n1 2\n");
        assertEquals(Set.of("regelsuche-core"), ReleasedSdkChecks.closure(graph, deps, repository, Set.of("regelsuche-core")).get("regelsucheModules"));
        assertThrows(Exception.class, () -> ReleasedSdkChecks.closure(graph, deps, repository, Set.of("unexpected")));
        Files.writeString(actual, "different");
        assertThrows(Exception.class, () -> ReleasedSdkChecks.closure(graph, deps, repository, Set.of("regelsuche-core")));
    }

    @Test void copiedConsumerInputsExcludeGeneratedOutputsAndRejectLinks() throws Exception {
        Path source = Files.createDirectory(tmp.resolve("input"));
        Files.writeString(source.resolve("pom.xml"), "input");
        Files.createDirectories(source.resolve("target"));
        Files.writeString(source.resolve("target/old.jar"), "stale");
        assertEquals(Set.of("pom.xml"), ReleasedSdkChecks.copyInputs(source, tmp.resolve("valid")).keySet());
        Files.createSymbolicLink(source.resolve("link"), Path.of("pom.xml"));
        assertThrows(Exception.class, () -> ReleasedSdkChecks.copyInputs(source, tmp.resolve("invalid")));
    }

    @Test void failedCommandKeepsOriginalExitCodeAndDiagnostics() throws Exception {
        Path file = Files.writeString(tmp.resolve("Reject.java"), "class Reject { public static void main(String[] a) { System.out.println(\"original failure\"); System.exit(7); } }");
        Path log = tmp.resolve("failed.log");
        var records = new java.util.ArrayList<java.util.Map<String, Object>>();
        String executable = Path.of(System.getProperty("java.home"), "bin", (java.io.File.separatorChar == '\\' ? "java.exe" : "java")).toString();
        assertThrows(Exception.class, () -> ReleasedSdkConsumersIT.execute(List.of(executable, file.toString()), tmp, log, records));
        assertEquals(7, records.getFirst().get("exitCode"));
        assertTrue(Files.readString(log).contains("original failure"));
    }

    @Test void checksTheCompleteDiscoveryAndRuleValuesIncludingNegativeControls() throws Exception {
        var contracts = ReleasedSdkChecks.contracts();
        var discovery = contracts.required("geometric-sequence-domain-java25");
        String output = discovery.required("stdoutPrefix").textValue() + "evidenceHash=sha256:" + "a".repeat(64) + "\n";
        assertEquals(true, ReleasedSdkChecks.cli(discovery, output, output).get("repeatVerified"));
        for (String changed : List.of(output.replace("CONFIRMED", "REFUTED"), output.replace("multiplier=2", "multiplier=3"),
                output.replace("observed 4", "observed 5"), output.replace("sha256:", "sha1:"))) {
            assertThrows(Exception.class, () -> ReleasedSdkChecks.cli(discovery, changed, changed));
        }
        var rule = contracts.required("hello-rule-java25");
        String expected = rule.required("stdout").textValue();
        assertEquals("EXACT_OUTPUT", ReleasedSdkChecks.cli(rule, expected, null).get("policy"));
        assertThrows(Exception.class, () -> ReleasedSdkChecks.cli(rule, expected.replace("REJECTED", "ACCEPTED"), null));
    }

    @Test void versionAndCliIdentityRequireTheOriginalResultAndAnActualRepeat() throws Exception {
        var catalog = ReleasedSdkChecks.contracts().required("extension-runtime-java25");
        String output = "extension=hello\norigin=greeting-plugin\ngreeting=Hello Ada\ncatalog=sha256:" + "c".repeat(64) + "\n";
        assertEquals(true, ReleasedSdkChecks.cli(catalog, output, output).get("repeatVerified"));
        for (String invalid : List.of(output + "extra", output.replace("Hello Ada", "Other"), output.replace("sha256:", ""))) {
            assertThrows(Exception.class, () -> ReleasedSdkChecks.cli(catalog, invalid, invalid));
        }
        assertThrows(Exception.class, () -> ReleasedSdkChecks.cli(catalog, output, output.replace("ccc", "ddd")));
        assertThrows(Exception.class, () -> ReleasedSdkChecks.toolchain("Apache Maven 3.9.8\nJava version: 25", "openjdk version \"25\""));
        assertThrows(Exception.class, () -> ReleasedSdkChecks.toolchain("Apache Maven 3.9.9\nJava version: 21", "openjdk version \"25\""));
    }
}
