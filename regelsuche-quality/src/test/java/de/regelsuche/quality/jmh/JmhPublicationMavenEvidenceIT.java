package de.regelsuche.quality.jmh;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "regelsuche.jmh.publicationOutput", matches = ".+")
class JmhPublicationMavenEvidenceIT {
    @Test
    void publishRetainedMeasurements() throws IOException {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot"));
        Path input = root.resolve(System.getProperty("regelsuche.jmh.publicationResult"));
        Path output = Path.of(System.getProperty("regelsuche.jmh.publicationOutput"));
        JmhPublicationMain.main(new String[] {input.toString(),
            root.resolve("config/quality/jmh-baseline-more-warmup-v1.json").toString(),
            output.resolve("badge.json").toString(), output.resolve("index.html").toString()});
    }
}
