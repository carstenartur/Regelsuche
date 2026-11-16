package de.regelsuche.quality.jmh;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Optional read-only Maven replay of externally produced JMH results. The
 * JMH producer is not silently replaced or skipped by this verification.
 */
@EnabledIfSystemProperty(named = "regelsuche.jmh.regressionOutput", matches = ".+")
class JmhRegressionV3MavenEvidenceIT {
    @Test
    void verifyTheActualResultAgainstTheFrozenCheckoutPolicy()
            throws IOException {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot"));
        Path result = root.resolve(System.getProperty(
            "regelsuche.jmh.regressionResult"));
        Path output = Path.of(System.getProperty(
            "regelsuche.jmh.regressionOutput"));
        JmhRegressionV3Main.main(new String[] {
            root.toString(),
            result.toString(),
            root.resolve("config/quality/jmh-regression-policy-v2.json")
                .toString(),
            root.resolve("config/quality/jmh-regression-decision-policy-v3.json")
                .toString(),
            output.resolve("report.json").toString(),
            output.resolve("report.md").toString()
        });
    }
}
