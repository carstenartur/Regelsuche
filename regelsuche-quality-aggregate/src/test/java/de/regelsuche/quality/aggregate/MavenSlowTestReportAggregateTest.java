package de.regelsuche.quality.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.quality.tests.SlowTestReport;
import de.regelsuche.quality.tests.SlowTestReportGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class MavenSlowTestReportAggregateTest {
    private static final String MODULES_PROPERTY =
        "regelsuche.slowTestReport.modules";
    private static final String FULL_PROPERTY =
        "regelsuche.slowTestReport.full";
    private static final Pattern MODULE_SEPARATOR =
        Pattern.compile("[,\\s]+");

    @Test
    void finalReactorModuleWritesSlowTestEvidence() throws Exception {
        Path root = repositoryRoot();
        Set<String> modules = configuredModules();
        boolean fullProfile = Boolean.parseBoolean(
            System.getProperty(FULL_PROPERTY, "false")
        );

        assertEquals(fullProfile ? 21 : 20, modules.size());
        assertTrue(modules.contains("regelsuche-quality"));
        assertTrue(modules.contains("maven-build-contract"));
        assertTrue(modules.contains("app"));
        assertEquals(
            fullProfile,
            modules.contains("regelsuche-integration-tests")
        );
        assertFalse(modules.contains("regelsuche-quality-aggregate"));
        for (String module : modules) {
            assertTrue(
                Files.isRegularFile(root.resolve(module).resolve("pom.xml")),
                () -> "configured aggregate module has no pom.xml: "
                    + module
            );
        }

        SlowTestReport report = new SlowTestReportGenerator().writeMaven(
            root,
            SlowTestReportGenerator.DEFAULT_LIMIT,
            SlowTestReportGenerator.DEFAULT_SLOW_SECONDS,
            modules,
            SlowTestReportGenerator.DEFAULT_JSON_OUTPUT,
            SlowTestReportGenerator.DEFAULT_MARKDOWN_OUTPUT
        );

        Path json = root.resolve(
            SlowTestReportGenerator.DEFAULT_JSON_OUTPUT
        );
        Path markdown = root.resolve(
            SlowTestReportGenerator.DEFAULT_MARKDOWN_OUTPUT
        );
        assertTrue(report.suiteCount() > 0);
        assertTrue(report.testCount() > 0);
        assertTrue(Files.isRegularFile(json));
        assertTrue(Files.size(json) > 0L);
        assertTrue(Files.isRegularFile(markdown));
        assertTrue(Files.size(markdown) > 0L);

        System.out.println("mavenSlowTestReportStatus=PASSED");
        System.out.println(
            "mavenSlowTestReportProfile="
                + (fullProfile ? "full" : "product-reactor")
        );
        System.out.println(
            "mavenSlowTestReportModules=" + modules.size()
        );
        System.out.println(
            "mavenSlowTestReportTests=" + report.testCount()
        );
        System.out.println("mavenSlowTestReport=" + json);
    }

    private static Set<String> configuredModules() {
        String configured = System.getProperty(MODULES_PROPERTY);
        assertNotNull(
            configured,
            "Maven must declare the active slow-test report modules"
        );
        List<String> values = MODULE_SEPARATOR.splitAsStream(
            configured.trim()
        )
            .filter(value -> !value.isBlank())
            .toList();
        LinkedHashSet<String> modules = new LinkedHashSet<>(values);
        assertEquals(
            values.size(),
            modules.size(),
            "slow-test report modules must be unique"
        );
        assertFalse(
            modules.isEmpty(),
            "slow-test report module set must not be empty"
        );
        return Set.copyOf(modules);
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty(
            "regelsuche.repositoryRoot"
        );
        assertNotNull(
            configured,
            "Maven must expose maven.multiModuleProjectDirectory"
        );
        Path root = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")));
        return root;
    }
}
