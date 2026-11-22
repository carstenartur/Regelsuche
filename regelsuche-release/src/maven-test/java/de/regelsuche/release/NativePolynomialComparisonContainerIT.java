package de.regelsuche.release;

import static de.regelsuche.release.PolynomialComparisonContainerIT.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/** The frozen native study and its independent verifier in the pinned worker. */
class NativePolynomialComparisonContainerIT {
    private static final String STUDY = "native-polynomial-comparison";
    private static final String OUTPUT = "build/reports/" + STUDY;
    private static final String WORKER = "external_polynomial_comparison.run_native";

    @Test void executesAndIndependentlyChecksTheFrozen675Rows() throws Throwable {
        Path root = Path.of("..").toRealPath();
        Path reports = Files.createDirectories(Path.of("target", "reports").toAbsolutePath().resolve(STUDY));
        Path input = Files.createDirectory(Path.of("target/native-polynomial-worker-input").toAbsolutePath());
        var identity = QualityCollectionCheckout.identity(root);
        assertEquals(BooleanNode.FALSE, identity.required("trackedChangesPresent"));
        Path registrationFile = input.resolve("source.json");
        if (Files.exists(root.resolve(".git"))) QualityArchiveRegistration.write(root, registrationFile);
        else Files.copy(root.resolve(QualityArchiveRegistration.RECEIPT), registrationFile);
        var registration = QualityAcceptanceCollection.JSON.readTree(registrationFile.toFile());
        assertEquals(identity.required("revision"), registration.required("revision"));
        PolynomialArchiveProvenanceMain.requireRegisteredImplementation(root, registration);
        Path source = input.resolve("source.tar");
        packSource(root, source, registration);

        var image = new ImageFromDockerfile().withFileFromPath("Dockerfile", root.resolve("Dockerfile.external-polynomial"))
            .withFileFromPath("requirements.txt", root.resolve("regelsuche-sympy-jmh/requirements.txt"));
        try (var worker = new GenericContainer<>(image).withWorkingDirectory("/study")
                .withEnv("PYTHONPATH", "/study/scripts").withEnv("PYTHONDONTWRITEBYTECODE", "1")) {
            copy(worker, source, "/worker/source.tar");
            copy(worker, registrationFile, "/study/" + QualityArchiveRegistration.RECEIPT);
            String verifier = System.getProperty("surefire.test.class.path");
            assertNotNull(verifier, "Maven must provide the resolved verifier classpath");
            var verifierPaths = Pattern.compile(Pattern.quote(File.pathSeparator)).splitAsStream(verifier).map(Path::of).toList();
            worker.withEnv("REGELSUCHE_NATIVE_SOURCE_VERIFIER_CLASSPATH", stageClasspath(worker, verifierPaths, "/worker/verifier"));
            Path launch = Files.writeString(input.resolve("classpath.txt"), stageClasspath(worker, runtime(root), "/worker/runtime"));
            copy(worker, launch, "/worker/classpath.txt");
            worker.start();
            run(worker, reports, "extract-source", "tar", "-xf", "/worker/source.tar", "-C", "/study");
            run(worker, reports, "runtime", "python", "-c",
                "import sys,sympy,mpmath; assert sys.version_info[:3]==(3,12,12) and sympy.__version__=='1.14.0' and mpmath.__version__=='1.3.0'; print(sys.version,sympy.__version__,mpmath.__version__)");
            run(worker, reports, "git-fixture-tool", "git", "--version");
            assertEquals("git version 2.39.5", Files.readString(reports.resolve("git-fixture-tool.stdout")).strip());
            run(worker, reports, "independent-controls", "python", "-m", "unittest", "-v",
                "external_polynomial_comparison.test_native", "external_polynomial_comparison.test_run_schema",
                "external_polynomial_comparison.test_registered_source");
            run(worker, reports, "reference-controls", "python", "-m", "unittest", "discover", "-s",
                "scripts/sympy_rule_reference", "-p", "test_*.py", "-v");
            for (String name : List.of("independent-controls", "reference-controls")) {
                String receipt = Files.readString(reports.resolve(name + ".stderr"));
                assertTrue(receipt.stripTrailing().endsWith("\nOK"), name + " must execute without skips");
                assertTrue(Pattern.compile("(?m)^Ran [1-9][0-9]* tests? in .*s$").matcher(receipt).find(), name);
            }

            Path archive = reports.resolve("evidence.tgz");
            runWithArchive(() -> {
                run(worker, reports, "execute", "python", "-m", WORKER, "--classpath", "/worker/classpath.txt", "--output", OUTPUT);
                run(worker, reports, "verify", "python", "-m", WORKER, "--verify", "--output", OUTPUT);
            }, () -> {
                var result = worker.execInContainer("tar", "-czf", "/worker/evidence.tgz", "-C", "/study/build/reports", STUDY);
                Files.writeString(reports.resolve("archive.stdout"), result.getStdout());
                Files.writeString(reports.resolve("archive.stderr"), result.getStderr());
                copyArchive(result.getExitCode(), archive, () -> worker.copyFileFromContainer("/worker/evidence.tgz", archive.toString()));
                run(worker, reports, "archive-digest", "sha256sum", "/worker/evidence.tgz");
                String digest = Files.readString(reports.resolve("archive-digest.stdout")).split(" ", 2)[0];
                verifyArchiveDigest(archive, digest);
            });

            Path extracted = extract(archive, reports.resolve("extracted"));
            var json = QualityAcceptanceCollection.JSON;
            var rows = json.readTree(extracted.resolve("rows.json").toFile());
            assertTrue(rows.isArray());
            assertEquals(675, rows.size());
            var metadata = json.readTree(extracted.resolve("metadata.json").toFile());
            assertEquals(identity.required("revision").textValue(), metadata.required("sourceRevision").textValue());
            assertEquals("git:" + identity.required("tree").textValue(), metadata.required("sourceTreeHash").textValue());
            assertTrue(Files.isRegularFile(extracted.resolve("summary.json")));
            for (String name : List.of("manifest.json", "runtime-inventory.json")) {
                assertTrue(Files.size(extracted.resolve(name)) > 0, name);
            }
        }
    }

    private static void packSource(Path root, Path archive, JsonNode registration) throws IOException {
        try (var tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (var row : registration.required("members")) {
                String name = row.required("path").textValue();
                Path member = Path.of(name);
                if (member.isAbsolute() || !member.equals(member.normalize()) || member.startsWith(".."))
                    throw new IOException("invalid source member: " + name);
                Path file = root.resolve(member);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("missing source member: " + name);
                var entry = new TarArchiveEntry(name);
                entry.setSize(Files.size(file));
                entry.setMode(row.required("mode").intValue());
                tar.putArchiveEntry(entry);
                Files.copy(file, tar);
                tar.closeArchiveEntry();
            }
        }
    }

    static void verifyArchiveDigest(Path archive, String expected) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var contents = new DigestInputStream(Files.newInputStream(archive), digest)) {
            contents.transferTo(OutputStream.nullOutputStream());
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expected))
            throw new IOException("copied study archive differs from worker bytes");
    }

    static Path extract(Path archive, Path destination) throws IOException {
        Files.createDirectory(destination);
        var members = new HashSet<Path>();
        try (var tar = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path path = Path.of(entry.getName());
                if (path.isAbsolute() || !path.equals(path.normalize()) || !path.startsWith(STUDY) || !members.add(path)
                        || entry.isSymbolicLink() || entry.isLink() || (!entry.isDirectory() && !entry.isFile()))
                    throw new IOException("unsupported study archive member: " + entry.getName());
                Path target = destination.resolve(path);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(tar, target);
                }
            }
        }
        return destination.resolve(STUDY);
    }

    private static String stageClasspath(GenericContainer<?> worker, List<Path> members, String prefix) {
        var staged = new ArrayList<String>();
        for (Path member : members) {
            assertTrue(member.isAbsolute() && (Files.isDirectory(member) || Files.isRegularFile(member)), member.toString());
            String target = prefix + "/" + staged.size() + "/" + member.getFileName();
            copy(worker, member, target);
            staged.add(target);
        }
        assertFalse(staged.isEmpty(), "empty worker classpath");
        return String.join(":", staged);
    }

    private static void copy(GenericContainer<?> worker, Path source, String destination) {
        worker.withCopyFileToContainer(MountableFile.forHostPath(source), destination);
    }

    private static void run(GenericContainer<?> worker, Path reports, String name, String... command) throws Exception {
        var output = worker.execInContainer(command);
        Files.writeString(reports.resolve(name + ".stdout"), output.getStdout());
        Files.writeString(reports.resolve(name + ".stderr"), output.getStderr());
        assertEquals(0, output.getExitCode(), name + " failed; evidence in " + reports);
    }
}
