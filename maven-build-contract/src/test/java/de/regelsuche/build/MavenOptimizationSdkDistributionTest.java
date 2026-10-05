package de.regelsuche.build;

import static de.regelsuche.build.MavenPomTestSupport.repositoryRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Real builds and consumers belong to Maven/JUnit, not workflow shell assertions. */
@EnabledIfSystemProperty(named = "regelsuche.sdk.distribution", matches = "true")
class MavenOptimizationSdkDistributionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void twoCleanBuildsAndFreshConsumersProduceIdenticalSourceBoundDistributions() throws Exception {
        Path root = repositoryRoot();
        Path reports = root.resolve("maven-build-contract/target/surefire-reports");
        Files.createDirectories(reports);
        Path evidence = Files.createTempDirectory(reports, "sdk-distribution-");
        String revision = run(root, evidence.resolve("source-revision.txt"), "git", "rev-parse", "HEAD").trim();
        String tree = run(root, evidence.resolve("source-tree.txt"), "git", "rev-parse", "HEAD^{tree}").trim();
        String epoch = run(root, evidence.resolve("source-epoch.txt"), "git", "show", "-s", "--format=%ct", "HEAD").trim();
        assertTrue(revision.matches("[0-9a-f]{40}"), revision);
        assertTrue(tree.matches("[0-9a-f]{40}"), tree);
        assertTrue(epoch.matches("[0-9]+"), epoch);
        String javaHome = System.getProperty("java.home");
        String maven = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows") ? "mvn.cmd" : "mvn";
        String python = maven.endsWith(".cmd") ? "python" : "python3";
        run(root, evidence.resolve("java-version.txt"), Path.of(javaHome, "bin", "java").toString(), "-version");
        run(root, evidence.resolve("maven-version.txt"), maven, "--version");
        long expectedTests = -1;
        List<Path> distributions = new ArrayList<>();
        for (String attempt : List.of("first", "second")) {
            Path output = evidence.resolve(attempt);
            Path dependencies = evidence.resolve(attempt + "-dependencies");
            // This reactor does not contain maven-build-contract: no recursive invocation of this test.
            run(root, evidence.resolve(attempt + "-maven.log"), maven, "--batch-mode", "--no-transfer-progress",
                "-Psdk-release", "-pl", "regelsuche-optimization-sdk", "-am", "clean", "install",
                "-Dproject.build.outputTimestamp=" + epoch);
            long tests = verifyTestReports(root, evidence.resolve(attempt + "-test-reports"));
            if (expectedTests < 0) expectedTests = tests;
            else assertEquals(expectedTests, tests, "Both clean builds must execute the same SDK test count");
            run(root, evidence.resolve(attempt + "-dependencies.log"), maven, "--batch-mode", "--no-transfer-progress",
                "-pl", "regelsuche-optimization-sdk", "dependency:copy-dependencies", "-DincludeScope=runtime",
                "-DexcludeGroupIds=de.regelsuche", "-DoutputDirectory=" + dependencies);
            String result = run(root, evidence.resolve(attempt + "-consumer.json"), python, "-B",
                root.resolve("scripts/package-optimization-sdk.py").toString(),
                "--external-dependencies", dependencies.toString(), "--output", output.toString(), "--java-home", javaHome);
            verifyDistribution(JSON.readTree(result), output, revision);
            distributions.add(output);
        }
        List<Path> entries = relativeFiles(distributions.getFirst());
        assertFalse(entries.isEmpty(), "An empty distribution is not reproducibility evidence");
        assertEquals(entries, relativeFiles(distributions.getLast()));
        for (Path relative : entries) {
            assertEquals(-1L, Files.mismatch(distributions.getFirst().resolve(relative), distributions.getLast().resolve(relative)),
                "Distribution bytes differ: " + relative);
        }
        var receipt = JSON.createObjectNode();
        receipt.put("status", "PASS");
        receipt.put("sourceRevision", revision);
        receipt.put("sourceTree", tree);
        receipt.put("cleanBuilds", 2);
        receipt.put("sdkTestsPerBuild", expectedTests);
        receipt.put("freshConsumers", 2);
        receipt.put("identicalDistributionFiles", entries.size());
        JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("reproducibility.json").toFile(), receipt);
    }

    private static void verifyDistribution(JsonNode result, Path output, String revision) throws Exception {
        assertEquals(revision, result.path("revision").asText());
        Path jar = Path.of(result.path("jar").asText()).toAbsolutePath().normalize();
        assertTrue(jar.startsWith(output.toAbsolutePath().normalize()), "Packager must return its own output");
        assertTrue(Files.isRegularFile(jar), jar.toString());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        assertEquals(hash, result.path("sha256").asText());
        Path qualification = output.resolve("regelsuche-optimization-sdk-" + result.path("version").asText() + "-qualification.json");
        JsonNode proof = JSON.readTree(Files.readString(qualification));
        assertEquals("regelsuche.optimization-distribution/v1", proof.path("schema").asText());
        assertEquals(revision, proof.path("sourceRevision").asText());
        assertEquals(hash, proof.path("standaloneJarSha256").asText());
        assertEquals(25, proof.path("javaRelease").asInt());
        assertTrue(proof.path("consumer").path("freshDirectory").asBoolean());
        assertTrue(proof.path("consumer").path("emptyDependencyCache").asBoolean());
        String log = proof.path("consumer").path("output").asText();
        assertTrue(log.contains("optimization=VERIFIED"), log);
        assertTrue(log.contains("checked=ORIGINAL_OVERFLOW_DETECTED"), log);
        try (JarFile binary = new JarFile(jar.toFile())) {
            assertEquals(revision, binary.getManifest().getMainAttributes().getValue("Source-Revision"));
        }
    }

    private static long verifyTestReports(Path root, Path retained) throws Exception {
        Path reports = root.resolve("regelsuche-optimization-sdk/target/surefire-reports");
        Files.createDirectories(retained);
        var factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        long total = 0;
        try (var files = Files.list(reports)) {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("TEST-")
                    && p.getFileName().toString().endsWith(".xml")).sorted().toList()) {
                Files.copy(file, retained.resolve(file.getFileName()));
                var suite = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
                assertEquals("testsuite", suite.getTagName(), file.toString());
                for (String outcome : List.of("failures", "errors", "skipped")) {
                    assertEquals(0, Integer.parseInt(suite.getAttribute(outcome)), file + ": " + outcome);
                }
                total += Long.parseLong(suite.getAttribute("tests"));
            }
        }
        assertTrue(total > 0, "No SDK tests executed");
        return total;
    }

    private static List<Path> relativeFiles(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).map(directory::relativize).sorted().toList();
        }
    }

    private static String run(Path root, Path log, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(root.toFile())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.MINUTES), "SDK command timed out; see " + log);
            String output = Files.readString(log);
            assertEquals(0, process.exitValue(), () -> "Command failed: " + String.join(" ", command) + "\n"
                + output.substring(Math.max(0, output.length() - 12000)));
            return output;
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }
}
