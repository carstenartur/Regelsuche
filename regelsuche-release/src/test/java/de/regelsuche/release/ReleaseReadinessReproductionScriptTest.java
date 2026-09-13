package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real Java CLI at all four script sites; external producers only are explicit process seams. */
class ReleaseReadinessReproductionScriptTest {
    private static final Path ROOT = Path.of(System.getProperty("regelsuche.repositoryRoot"));
    private static final String LOCAL = "regelsuche-release/build/reports/release-readiness-qualified";
    private static final String LIB = "regelsuche-release/build/install/regelsuche-release/lib";
    private static final String VALID = "release-readiness-contract=valid";
    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin/java").toString();
    @TempDir static Path libraries;
    @TempDir Path temporary;

    @BeforeAll
    static void packageCurrentTestClasspathForTheRealJavaProcess() throws Exception {
        // Maven needs no Gradle distribution: retain actual classpath bytes in a test-only lib/*.
        int index = 0;
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            Path source = Path.of(entry).toAbsolutePath();
            Path jar = libraries.resolve(String.format("%04d.jar", index++));
            if (Files.isRegularFile(source)) {
                Files.createSymbolicLink(jar, source);
            } else if (Files.isDirectory(source)) {
                try (var output = new JarOutputStream(Files.newOutputStream(jar)); var paths = Files.walk(source)) {
                    for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                        output.putNextEntry(new JarEntry(source.relativize(path).toString().replace(File.separatorChar, '/')));
                        Files.copy(path, output);
                        output.closeEntry();
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"run-release-readiness-verification.sh", "verify-release-readiness-docker-reproduction.sh"})
    void bothRetainedRootsReachTheRealJavaVerifier(String script) throws Exception {
        Path checkout = checkout(script);
        Result result = run(checkout, script, JAVA, false);
        assertEquals(0, result.exit(), result.output());
        assertEquals(2, result.output().lines().filter(VALID::equals).count(), result.output());
        assertTrue(result.output().contains("OK: Gradle and Docker release-readiness evidence are byte-identical"));
        if (script.startsWith("run-")) {
            assertTrue(result.calls().contains(":regelsuche-release:installDist"), result.calls());
            assertTrue(result.calls().contains("--tests de.regelsuche.docs.HiddenRulePilotCampaignTest"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"run-release-readiness-verification.sh", "verify-release-readiness-docker-reproduction.sh"})
    void forgedLocalEvidenceStopsBeforeContainerProduction(String script) throws Exception {
        Path checkout = checkout(script);
        forge(checkout.resolve(LOCAL));
        Result result = run(checkout, script, JAVA, false);
        rejectedByJava(result, 0);
        assertFalse(result.calls().contains("docker build"), result.calls());
        assertFalse(result.calls().contains("docker run"), result.calls());
    }

    @ParameterizedTest
    @ValueSource(strings = {"run-release-readiness-verification.sh", "verify-release-readiness-docker-reproduction.sh"})
    void forgedContainerEvidenceCannotReachTheByteIdenticalSuccess(String script) throws Exception {
        Path checkout = checkout(script);
        forge(checkout.resolve("container-fixture"));
        Result result = run(checkout, script, JAVA, false);
        rejectedByJava(result, 1);
        assertTrue(result.calls().contains("docker run"), result.calls());
    }

    @ParameterizedTest
    @ValueSource(strings = {"run-release-readiness-verification.sh", "verify-release-readiness-docker-reproduction.sh"})
    void wrongJavaFeatureCannotStartEitherProducer(String script) throws Exception {
        Path checkout = checkout(script);
        Path launcher = executable(checkout.resolve("wrong-java"), """
            #!/usr/bin/env bash
            printf '    java.specification.version = 26\n' >&2
            exit 0
            """);
        Result result = run(checkout, script, launcher.toString(), false);
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("Java 25"), result.output());
        assertFalse(result.calls().contains("gradle "), result.calls());
        assertFalse(result.calls().contains("docker build"), result.calls());
        assertFalse(result.output().contains(VALID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"run-release-readiness-verification.sh", "verify-release-readiness-docker-reproduction.sh"})
    void unsupportedNativePlatformIsFailureRatherThanSkippedVerification(String script) throws Exception {
        Path checkout = checkout(script);
        Result result = run(checkout, script, JAVA, true);
        rejectedByJava(result, 0);
        assertTrue(result.output().contains("UNSUPPORTED_PLATFORM"), result.output());
        assertFalse(result.calls().contains("docker build"), result.calls());
    }

    @ParameterizedTest
    @ValueSource(strings = {"run-release-readiness-verification.sh", "verify-release-readiness-docker-reproduction.sh"})
    void missingDistributionNeverFallsBackToAnotherClasspath(String script) throws Exception {
        Path checkout = checkout(script);
        Files.delete(checkout.resolve(LIB));
        Result result = run(checkout, script, JAVA, false);
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("installDist"), result.output());
        assertFalse(result.output().contains(VALID));
        assertFalse(result.calls().contains("docker build"), result.calls());
    }

    private Path checkout(String script) throws Exception {
        Path checkout = Files.createDirectory(temporary.resolve("checkout with spaces"));
        Files.createDirectories(checkout.resolve("scripts"));
        Files.copy(ROOT.resolve("scripts").resolve(script), checkout.resolve("scripts").resolve(script));
        Files.createDirectories(checkout.resolve(LIB).getParent());
        Files.createSymbolicLink(checkout.resolve(LIB), libraries);
        try (var paths = Files.walk(ROOT.resolve("docs/schemas"))) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                Path target = checkout.resolve(ROOT.relativize(path));
                Files.createDirectories(target.getParent());
                Files.copy(path, target);
            }
        }
        for (String directory : List.of(LOCAL, "container-fixture")) {
            for (var entry : ReleaseReadinessJavaBindingsTest.fixture().entrySet()) {
                Path target = checkout.resolve(directory).resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
            }
        }
        Files.createDirectories(checkout.resolve("host-seams"));
        executable(checkout.resolve("host-seams/python3"), """
            #!/usr/bin/env bash
            echo 'Legacy Python provisioning is forbidden in this script control.' >&2
            exit 95
            """);
        executable(checkout.resolve("host-seams/docker"), """
            #!/usr/bin/env bash
            set -euo pipefail
            printf 'docker %s\n' "$*" >> "$SCRIPT_PROBE_ROOT/calls"
            case "$1" in
              info|build) ;;
              run) cp -R "$SCRIPT_PROBE_ROOT/container-fixture/." "$SCRIPT_PROBE_ROOT/build/release-readiness-docker-output/" ;;
              *) exit 96 ;;
            esac
            """);
        executable(checkout.resolve("gradlew"), """
            #!/usr/bin/env bash
            set -euo pipefail
            printf 'gradle %s\n' "$*" >> "$SCRIPT_PROBE_ROOT/calls"
            """);
        return checkout;
    }

    private static Path executable(Path path, String script) throws Exception {
        Files.writeString(path, script);
        assertTrue(path.toFile().setExecutable(true));
        return path;
    }

    private static void forge(Path root) throws Exception {
        Path run = root.resolve("release-readiness-run.json");
        var mapper = new ObjectMapper();
        var document = (ObjectNode) mapper.readTree(run.toFile());
        document.put("matrixHash", "sha256:" + "0".repeat(64));
        mapper.writeValue(run.toFile(), document);
    }

    private static Result run(Path checkout, String script, String java, boolean unsupported) throws Exception {
        Path log = checkout.resolve("process.log");
        var builder = new ProcessBuilder("bash", checkout.resolve("scripts").resolve(script).toString())
            .directory(checkout.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var environment = builder.environment();
        for (String option : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH",
                "REGELSUCHE_VERIFICATION_PYTHON", "REGELSUCHE_VERIFICATION_VENV")) environment.remove(option);
        environment.put("REGELSUCHE_RELEASE_VERIFIER_JAVA", java);
        environment.put("SCRIPT_PROBE_ROOT", checkout.toString());
        environment.put("PATH", checkout.resolve("host-seams") + File.pathSeparator + environment.get("PATH"));
        if (unsupported) environment.put("JAVA_TOOL_OPTIONS", "-Dos.name=unsupported");
        var process = builder.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            fail("script process exceeded 30 seconds");
        }
        Path calls = checkout.resolve("calls");
        return new Result(process.exitValue(), Files.readString(log), Files.exists(calls) ? Files.readString(calls) : "");
    }

    private static void rejectedByJava(Result result, int previousValidRoots) {
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("Exception in thread \"main\""), result.output());
        assertEquals(previousValidRoots, result.output().lines().filter(VALID::equals).count(), result.output());
        assertFalse(result.output().contains("OK: Gradle and Docker"), result.output());
    }

    private record Result(int exit, String output, String calls) { }
}
