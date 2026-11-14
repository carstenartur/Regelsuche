package de.regelsuche.quality.jmh;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class JmhAllocationMavenEvidenceIT {
    @Test
    void verifyRetainedMeasurement() throws IOException {
        Path repository = Path.of(System.getProperty("regelsuche.repositoryRoot"));
        Path result = repository.resolve(System.getProperty("regelsuche.jmh.allocationResult"));
        JmhAllocationReportMain.main(new String[] {
            result.toString(), System.getProperty("regelsuche.jmh.allocationSummary")
        });
    }
}
