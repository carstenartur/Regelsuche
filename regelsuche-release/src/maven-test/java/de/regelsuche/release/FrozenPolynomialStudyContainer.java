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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

/** Shared source admission and evidence transport for the two frozen studies. */
final class FrozenPolynomialStudyContainer {
    enum Study {
        NATIVE("native-polynomial-comparison", "native-polynomial-worker-input", "run_native", 675,
            List.of("test_native", "test_run_schema", "test_registered_source")),
        TYPED("typed-external-polynomial-comparison", "typed-polynomial-worker-input", "run_typed", 729,
            List.of("test_typed_run", "test_typed_audit", "test_registered_source"));

        final String directory;
        final String workerInput;
        final String module;
        final int rows;
        final List<String> controls;

        Study(String directory, String workerInput, String runner, int rows, List<String> controls) {
            this.directory = directory;
            this.workerInput = workerInput;
            this.module = "external_polynomial_comparison." + runner;
            this.rows = rows;
            this.controls = controls;
        }
    }

    static void run(Study study) throws Throwable {
        String output = "build/reports/" + study.directory;
        Path root = Path.of("..").toRealPath();
        Path reports = Files.createDirectories(Path.of("target", "reports").toAbsolutePath().resolve(study.directory));
        Path input = Files.createDirectory(Path.of("target", study.workerInput).toAbsolutePath());
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
        Path ancestry = study == Study.TYPED ? typedWitness(root, identity, reports) : null;

        var image = new ImageFromDockerfile().withFileFromPath("Dockerfile", root.resolve("Dockerfile.external-polynomial"))
            .withFileFromPath("requirements.txt", root.resolve("regelsuche-sympy-jmh/requirements.txt"));
        try (var worker = new GenericContainer<>(image).withWorkingDirectory("/study")
                .withEnv("PYTHONPATH", "/study/scripts").withEnv("PYTHONDONTWRITEBYTECODE", "1")) {
            copy(worker, source, "/worker/source.tar");
            copy(worker, registrationFile, "/study/" + QualityArchiveRegistration.RECEIPT);
            if (ancestry != null) copy(worker, ancestry, "/study/" + PolynomialSourceProof.RECEIPT);
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
            var controls = new ArrayList<>(List.of("python", "-m", "unittest", "-v"));
            study.controls.forEach(name -> controls.add("external_polynomial_comparison." + name));
            run(worker, reports, "independent-controls", controls.toArray(String[]::new));
            run(worker, reports, "reference-controls", "python", "-m", "unittest", "discover", "-s",
                "scripts/sympy_rule_reference", "-p", "test_*.py", "-v");
            for (String name : List.of("independent-controls", "reference-controls")) {
                String receipt = Files.readString(reports.resolve(name + ".stderr"));
                assertTrue(receipt.stripTrailing().endsWith("\nOK"), name + " must execute without skips");
                assertTrue(Pattern.compile("(?m)^Ran [1-9][0-9]* tests? in .*s$").matcher(receipt).find(), name);
            }

            Path archive = reports.resolve("evidence.tgz");
            runWithArchive(() -> {
                run(worker, reports, "execute", "python", "-m", study.module, "--classpath", "/worker/classpath.txt", "--output", output);
                run(worker, reports, "verify", "python", "-m", study.module, "--verify", "--output", output);
                if (study == Study.TYPED) {
                    run(worker, reports, "audit", "python", "-m", "external_polynomial_comparison.audit_typed", "--output", output);
                    verifyTypedAudit(reports.resolve("audit.stdout"), identity.required("revision").textValue());
                }
            }, () -> {
                var result = worker.execInContainer("tar", "-czf", "/worker/evidence.tgz", "-C", "/study/build/reports", study.directory);
                Files.writeString(reports.resolve("archive.stdout"), result.getStdout());
                Files.writeString(reports.resolve("archive.stderr"), result.getStderr());
                copyArchive(result.getExitCode(), archive, () -> worker.copyFileFromContainer("/worker/evidence.tgz", archive.toString()));
                run(worker, reports, "archive-digest", "sha256sum", "/worker/evidence.tgz");
                String digest = Files.readString(reports.resolve("archive-digest.stdout")).split(" ", 2)[0];
                verifyArchiveDigest(archive, digest);
            });

            Path extracted = extract(archive, reports.resolve("extracted"), study.directory);
            var json = QualityAcceptanceCollection.JSON;
            var rows = json.readTree(extracted.resolve("rows.json").toFile());
            assertTrue(rows.isArray());
            assertEquals(study.rows, rows.size());
            var metadata = json.readTree(extracted.resolve("metadata.json").toFile());
            String revision = identity.required("revision").textValue();
            if (study == Study.NATIVE) {
                assertEquals(revision, metadata.required("sourceRevision").textValue());
                assertEquals("git:" + identity.required("tree").textValue(), metadata.required("sourceTreeHash").textValue());
                assertTrue(Files.size(extracted.resolve("runtime-inventory.json")) > 0);
            } else {
                assertEquals(revision, metadata.required("revision").textValue());
                assertEquals(PolynomialSourceProof.TYPED, metadata.required("freezeCommit").textValue());
            }
            assertTrue(Files.isRegularFile(extracted.resolve("summary.json")));
            assertTrue(Files.size(extracted.resolve("manifest.json")) > 0);
        }
    }

    private static Path typedWitness(Path root, JsonNode identity, Path reports) throws Exception {
        var proof = PolynomialSourceProof.prepare(root);
        var verified = PolynomialSourceProof.verify(root, proof, identity);
        assertTrue(verified.required("ancestors").required(PolynomialSourceProof.TYPED).booleanValue());
        Files.writeString(reports.resolve("verified-typed-source.json"), verified.toString() + "\n");
        return Files.writeString(reports.resolve("typed-source-witness.json"), proof.toString() + "\n");
    }

    static void verifyTypedAudit(Path receipt, String revision) throws IOException {
        var audit = QualityAcceptanceCollection.JSON.readTree(receipt.toFile());
        if (audit == null || !"regelsuche.typed-selected-path-audit/v1".equals(audit.path("schema").textValue())
                || !revision.equals(audit.path("revision").textValue()) || !audit.path("rows").isIntegralNumber()
                || !audit.path("rows").canConvertToInt() || audit.path("rows").intValue() != Study.TYPED.rows
                || !audit.path("profiles").isObject()) throw new IOException("typed audit does not bind the admitted study");
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

    static Path extract(Path archive, Path destination, String directory) throws IOException {
        Files.createDirectory(destination);
        var members = new HashSet<Path>();
        try (var tar = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path path = Path.of(entry.getName());
                if (path.isAbsolute() || !path.equals(path.normalize()) || !path.startsWith(directory) || !members.add(path)
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
        return destination.resolve(directory);
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
