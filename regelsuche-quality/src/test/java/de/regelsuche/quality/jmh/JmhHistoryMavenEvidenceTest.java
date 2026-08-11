package de.regelsuche.quality.jmh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(
    named = "regelsuche.maven.reactor",
    matches = "true"
)
class JmhHistoryMavenEvidenceTest {

    @Test
    void mavenReactorWritesDurableHistoryEvidence() throws Exception {
        Path repositoryRoot = Path.of(requiredProperty(
            "regelsuche.repositoryRoot"
        )).toAbsolutePath().normalize();
        Path outputDirectory = Path.of(requiredProperty(
            "regelsuche.quality.outputDirectory"
        )).toAbsolutePath().normalize();
        Path moduleTarget = repositoryRoot.resolve(
            "regelsuche-quality/target"
        ).toAbsolutePath().normalize();

        assertTrue(
            outputDirectory.startsWith(moduleTarget),
            () -> "Maven history output must stay below " + moduleTarget
        );

        JmhHistory history = new JmhHistoryLoader().load(
            repositoryRoot.resolve(
                "config/quality/jmh-history-policy.json"
            ),
            repositoryRoot.resolve(
                "config/quality/jmh-regression-policy-v2.json"
            )
        );
        new JmhHistoryReportWriter().write(history, outputDirectory);

        assertTrue(Files.isRegularFile(outputDirectory.resolve(
            "history.json"
        )));
        assertTrue(Files.isRegularFile(outputDirectory.resolve(
            "history.md"
        )));
        try (var charts = Files.list(outputDirectory.resolve("charts"))) {
            assertEquals(
                history.benchmarks().size(),
                charts.filter(Files::isRegularFile).count()
            );
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "required Maven system property is missing: " + name
            );
        }
        return value;
    }
}
