package de.regelsuche.math.sympy;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.MountableFile;

class SympyRuntimeContainerIT {
    private static final String PACKAGE = "de.regelsuche.math.sympy";
    private static final String JVM_AGENT = "-javaagent:/worker/jacoco.jar=destfile=/worker/jacoco.exec,"
        + "append=true,excludes=com.oracle.graal.python.builtins.modules.cext.PythonCApiAssertions";

    @Test
    void fullEmbeddedAndCpythonSuiteRunsInFreshForksWithCoverage() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot"));
        Path target = Path.of(System.getProperty("regelsuche.sympy.target"));
        Path tests = target.resolve("test-classes");
        Path reports = Files.createDirectories(target.resolve("reports/sympy-runtime"));
        var image = new ImageFromDockerfile()
            .withFileFromPath("Dockerfile", root.resolve("Dockerfile.external-python"))
            .withFileFromPath("requirements.txt", root.resolve("regelsuche-sympy-jmh/requirements.txt"));
        List<String> classes;
        try (var files = Files.list(tests.resolve(PACKAGE.replace('.', '/')))) {
            classes = files.map(path -> path.getFileName().toString())
                .filter(file -> file.endsWith("Test.class") && !file.contains("$"))
                .map(file -> file.substring(0, file.length() - ".class".length())).sorted().toList();
        }
        assertTrue(classes.containsAll(List.of("GraalPySymPyRuntimeTest", "GraalPySymPyRuntimeBootstrapTest",
            "SymPyFactorizationEngineTest")), classes.toString());
        try (var worker = new GenericContainer<>(image)
                .withCopyFileToContainer(MountableFile.forHostPath(tests), "/worker/tests")
                .withCopyFileToContainer(MountableFile.forHostPath(target.resolve("runtime-lib")), "/worker/lib")
                .withCopyFileToContainer(MountableFile.forHostPath(target.resolve("runtime-jacoco.jar")), "/worker/jacoco.jar")
                .withCopyFileToContainer(MountableFile.forHostPath(target.resolve(System.getProperty("regelsuche.sympy.jar"))), "/worker/sympy.jar")) {
            worker.start();
            List<String> failures = new ArrayList<>();
            for (String name : classes) {
                var result = worker.execInContainer("java", "--enable-native-access=ALL-UNNAMED", "-Xms512m", "-Xmx2g", JVM_AGENT,
                    "-Dregelsuche.sympy.python=/usr/local/bin/python", "-cp", "/worker/tests:/worker/sympy.jar:/worker/lib/*",
                    "org.junit.platform.console.ConsoleLauncher", "execute", "--select-class=" + PACKAGE + "." + name,
                    "--fail-if-no-tests", "--disable-ansi-colors", "--reports-dir=/worker/reports/" + name);
                Files.writeString(reports.resolve(name + ".stdout.log"), result.getStdout());
                Files.writeString(reports.resolve(name + ".stderr.log"), result.getStderr());
                Path xml = reports.resolve("TEST-" + PACKAGE + "." + name + ".xml");
                worker.copyFileFromContainer("/worker/reports/" + name + "/TEST-junit-jupiter.xml", xml.toString());
                if (result.getExitCode() != 0 || !passedEntireClass(xml)) failures.add(name);
            }
            Path coverage = reports.resolve("jacoco.exec");
            worker.copyFileFromContainer("/worker/jacoco.exec", coverage.toString());
            assertTrue(Files.size(coverage) > 0);
            assertTrue(failures.isEmpty(), "Failed or omitted SymPy tests: " + failures + " in " + reports);
            // All original module tests ran again inside this worker. Use exactly that
            // execution data for the normal module JaCoCo report at verify.
            Files.copy(coverage, target.resolve("jacoco.exec"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean passedEntireClass(Path report) throws Exception {
        var parser = DocumentBuilderFactory.newInstance();
        parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var suite = parser.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
        return "testsuite".equals(suite.getTagName()) && Integer.parseInt(suite.getAttribute("tests")) > 0
            && List.of("failures", "errors", "skipped").stream().allMatch(key -> "0".equals(suite.getAttribute(key)));
    }
}
