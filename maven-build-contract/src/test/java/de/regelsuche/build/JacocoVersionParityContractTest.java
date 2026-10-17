package de.regelsuche.build;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Measuring and rendering with different analyzers changes filtered counters. */
class JacocoVersionParityContractTest {
    private static final Pattern TOOL = Pattern.compile("toolVersion\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern REPORT = Pattern.compile("org\\.jacoco:org\\.jacoco\\.report:([^'\"]+)");

    @Test void allMeasurementAndRenderingAnalyzersUseTheSamePinnedVersion() throws Exception {
        Path root = MavenPomTestSupport.repositoryRoot();
        var properties = MavenPomTestSupport.directChild(
                MavenPomTestSupport.parse(root.resolve("pom.xml")).getDocumentElement(), "properties");
        String measured = MavenPomTestSupport.directChildTexts(properties, "jacoco.version").getFirst();
        String rendered = MavenPomTestSupport.directChildTexts(properties, "jacoco.report.version").getFirst();
        assertEquals(measured, rendered, "Maven measurement and report runtime must agree");
        assertEquals(measured, version(REPORT, Files.readString(root.resolve("regelsuche-quality/build.gradle"))),
                "Gradle aggregate renderer must agree with Maven");
        var checked = new HashSet<String>();
        try (var modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path build = module.resolve("build.gradle");
                if (!Files.isRegularFile(build)) continue;
                String source = Files.readString(build);
                if (!source.contains("id 'jacoco'")) continue;
                assertEquals(measured, version(TOOL, source), module.getFileName().toString());
                checked.add(module.getFileName().toString());
            }
        }
        assertTrue(checked.containsAll(java.util.Set.of("app", "regelsuche-core", "regelsuche-search")));
    }

    @Test void duplicateOrMissingVersionPinsAreRejected() {
        assertThrows(AssertionError.class, () -> version(TOOL, "jacoco {}"));
        assertThrows(AssertionError.class, () -> version(TOOL, "toolVersion='one'\ntoolVersion='two'"));
        assertEquals("fixture-version", version(TOOL, "jacoco { toolVersion = 'fixture-version' }"));
    }

    private static String version(Pattern pattern, String source) {
        var matcher = pattern.matcher(source);
        assertTrue(matcher.find(), "An explicit JaCoCo version pin is required");
        String result = matcher.group(1);
        assertFalse(matcher.find(), "JaCoCo version pin must be unambiguous");
        return result;
    }
}
