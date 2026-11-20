package de.regelsuche.proofworker;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

class CheckedProofContainerIT {
    private static final String MATHLIB = "c44e0c8ee63ca166450922a373c7409c5d26b00b";
    private static final List<String> PROJECTS = List.of("reference", "rebuild");

    @Test
    void twoIndependentMathlibProjectsAndTheActualSmtBinariesQualifyApplicationProofs() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot"));
        Path target = Path.of(System.getProperty("regelsuche.proof.target"));
        Path reports = Files.createDirectories(target.resolve("reports/checked-proof"));
        Path input = root.resolve(".ci/lean-proof");
        assertEquals("leanprover/lean4:v4.19.0", Files.readString(input.resolve("lean-toolchain")).strip());
        Map<String, List<String>> suites = suites(target.resolve("test-classes/checked-proof-suites.txt"));
        var image = new ImageFromDockerfile().withFileFromPath("Dockerfile", root.resolve("Dockerfile.external-proofs"));
        try (var worker = new GenericContainer<>(image)
                .withEnv("MATHLIB_NO_CACHE_ON_UPDATE", "1")
                .withEnv("REGELSUCHE_REAL_LEAN", "true")
                .withEnv("REGELSUCHE_LEAN_PROJECT", "/proof/reference")
                .withEnv("REGELSUCHE_LEAN_REBUILD_PROJECT", "/proof/rebuild")
                .withCopyFileToContainer(MountableFile.forHostPath(target.resolve("proof-lib")), "/proof/lib")
                .withCopyFileToContainer(MountableFile.forHostPath(target.resolve("test-classes")), "/proof/tests")
                .withCopyFileToContainer(MountableFile.forHostPath(input.resolve("transport/curl"), 0755), "/proof/transport/curl")) {
            for (String project : PROJECTS) {
                for (String file : List.of("lakefile.toml", "lean-toolchain")) {
                    worker.withCopyFileToContainer(MountableFile.forHostPath(input.resolve(file)), "/proof/" + project + "/" + file);
                }
            }
            worker.start();
            for (String project : PROJECTS) {
                String directory = "/proof/" + project;
                run(worker, reports, project + "-update", "env", "--chdir=" + directory, "lake", "update");
                Path manifest = reports.resolve(project + "-lake-manifest.json");
                worker.copyFileFromContainer(directory + "/lake-manifest.json", manifest.toString());
                var packages = new ObjectMapper().readTree(manifest.toFile()).required("packages");
                assertTrue(packages.valueStream().anyMatch(row -> "mathlib".equals(row.path("name").asText())
                    && MATHLIB.equals(row.path("rev").asText())), "mathlib commit in " + manifest);
                run(worker, reports, project + "-cache", "env", "--chdir=" + directory, "lake", "exe", "cache", "get");
                assertTrue(run(worker, reports, project + "-version", "env", "--chdir=" + directory, "lake", "env", "lean", "--version")
                    .contains("4.19.0"));
            }
            assertTrue(run(worker, reports, "z3-version", "z3", "--version").contains("4.8.12"));
            assertTrue(run(worker, reports, "cvc5-version", "cvc5", "--version").contains("1.0.3"));
            // A version string does not show that these binaries can actually decide SMT-LIB inputs.
            for (String solver : List.of("z3", "cvc5")) {
                for (String expected : List.of("sat", "unsat")) {
                    String name = solver + "-" + expected;
                    Path script = reports.resolve(name + ".smt2");
                    Files.writeString(script, "(set-logic QF_LIA)\n(declare-const x Int)\n(assert (= x 1))\n"
                        + ("unsat".equals(expected) ? "(assert (= x 2))\n" : "") + "(check-sat)\n");
                    worker.copyFileToContainer(MountableFile.forHostPath(script), "/proof/" + name + ".smt2");
                    assertEquals(expected, run(worker, reports, name, solver, "/proof/" + name + ".smt2").strip());
                }
            }
            for (var module : suites.entrySet()) runSuites(worker, reports, module.getKey(), module.getValue());
        }
    }

    private static Map<String, List<String>> suites(Path manifest) throws Exception {
        Map<String, List<String>> suites = new LinkedHashMap<>();
        for (String line : Files.readAllLines(manifest)) {
            String[] fields = line.split(" ");
            assertEquals(2, fields.length, line);
            assertTrue(Set.of("app", "regelsuche-solver-portfolio").contains(fields[0]), line);
            assertTrue(fields[1].matches("de\\.regelsuche\\.[a-z.]+\\.[A-Za-z0-9]+Test"), line);
            var classes = suites.computeIfAbsent(fields[0], ignored -> new ArrayList<>());
            assertFalse(classes.contains(fields[1]), line);
            classes.add(fields[1]);
        }
        assertEquals(Set.of("app", "regelsuche-solver-portfolio"), suites.keySet());
        return suites;
    }

    private static void runSuites(GenericContainer<?> worker, Path reports, String module, List<String> classes) throws Exception {
        String directory = "/proof/" + module;
        run(worker, reports, module + "-prepare", "mkdir", "-p", directory + "/target/checked-proof");
        var command = new ArrayList<>(List.of("env", "--chdir=" + directory, "java", "--enable-native-access=ALL-UNNAMED",
            "-cp", "/proof/tests:/proof/lib/*", "org.junit.platform.console.ConsoleLauncher", "execute",
            "--fail-if-no-tests", "--disable-ansi-colors", "--reports-dir=" + directory + "/target/junit"));
        classes.forEach(name -> command.add("--select-class=" + name));
        var result = worker.execInContainer(command.toArray(String[]::new));
        Files.writeString(reports.resolve(module + ".stdout.log"), result.getStdout());
        Files.writeString(reports.resolve(module + ".stderr.log"), result.getStderr());
        run(worker, reports, module + "-archive", "tar", "-czf", directory + "/evidence.tgz", "-C", directory, "target");
        worker.copyFileFromContainer(directory + "/evidence.tgz", reports.resolve(module + "-evidence.tgz").toString());
        assertEquals(0, result.getExitCode(), "Proof suite failed in " + reports);
        Path report = reports.resolve(module + "-jupiter.xml");
        worker.copyFileFromContainer(directory + "/target/junit/TEST-junit-jupiter.xml", report.toString());
        var parser = DocumentBuilderFactory.newInstance();
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var suite = parser.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
        assertEquals("testsuite", suite.getTagName());
        for (String key : List.of("skipped", "errors", "failures")) assertEquals("0", suite.getAttribute(key), key);
        var cases = suite.getElementsByTagName("testcase");
        Set<String> executed = java.util.stream.IntStream.range(0, cases.getLength())
            .mapToObj(i -> ((org.w3c.dom.Element) cases.item(i)).getAttribute("classname")).collect(Collectors.toSet());
        assertEquals(Set.copyOf(classes), executed);
    }

    private static String run(GenericContainer<?> worker, Path reports, String name, String... command) throws Exception {
        var result = worker.execInContainer(command);
        Files.writeString(reports.resolve(name + ".stdout.log"), result.getStdout());
        Files.writeString(reports.resolve(name + ".stderr.log"), result.getStderr());
        assertEquals(0, result.getExitCode(), name + " failed in " + reports);
        return result.getStdout();
    }
}
