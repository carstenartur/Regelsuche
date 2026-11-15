package de.regelsuche.benchmarks;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicComparativeEvidenceTest {
    @Test
    void rejectsChangedTrackWithUnchangedSourceRegistry(@TempDir Path repository) throws Exception {
        Path metadata = Files.createDirectories(repository.resolve("research/benchmarks/comparative-v2"));
        String legacy = "{}";
        Files.writeString(repository.resolve("legacy.json"), legacy);
        String artifact = digest(legacy);
        String canonical = """
            {"schema":"regelsuche.public-comparative-benchmark/v2",
             "tracks":{"A1":{"cases":1}},"sources":{"legacy":{
               "path":"legacy.json","sha256":"%s","bytes":2,"attachment":"evidence/%s.json"}}}
            """.formatted(artifact, artifact);
        Files.writeString(metadata.resolve("sources.json"), """
            {"compositionSha256":"%s","legacy":{"path":"legacy.json","sha256":"%s","bytes":2}}
            """.formatted(digest(canonical), artifact));
        Path report = metadata.resolve("report.json");
        Files.writeString(report, canonical);
        assertArrayEquals(canonical.getBytes(StandardCharsets.UTF_8),
            PublicComparativeEvidence.read(repository, legacy, "").get(Path.of("v2/report.json")));

        Files.writeString(report, canonical.replace("\"cases\":1", "\"cases\":2"));

        IOException failure = assertThrows(IOException.class,
            () -> PublicComparativeEvidence.read(repository, legacy, ""));
        assertTrue(failure.getMessage().contains("composition digest differs"));
    }

    private static String digest(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
