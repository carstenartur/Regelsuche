package de.regelsuche.benchmarks;

import de.regelsuche.json.JsonReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Validates every registered public artifact before the previous publication is replaced. */
final class PublicComparativeEvidence {
    static final Path PUBLICATION = Path.of("research/benchmarks/comparative-v2/report.json");

    private PublicComparativeEvidence() {}

    static Map<Path, byte[]> read(String legacy, String rewriting) throws IOException {
        Path repository = Files.isRegularFile(PUBLICATION) ? Path.of(".") : Path.of("..");
        byte[] report = Files.readAllBytes(repository.resolve(PUBLICATION));
        var index = new JsonReader(new String(report, StandardCharsets.UTF_8)).readObject();
        var attachments = new LinkedHashMap<Path, byte[]>();
        attachments.put(Path.of("v2/report.json"), report);
        var sources = (Map<?, ?>) index.get("sources");
        for (var entry : sources.entrySet()) {
            var source = (Map<?, ?>) entry.getValue();
            String filename = (String) source.get("path");
            Path path = repository.resolve(filename);
            byte[] bytes;
            try (var file = Files.newInputStream(path);
                 var input = filename.endsWith(".gz") ? new GZIPInputStream(file) : file) {
                bytes = input.readAllBytes();
            }
            verify(bytes, source);
            String name = entry.getKey().toString();
            if (name.equals("legacy")) verify(legacy.getBytes(StandardCharsets.UTF_8), source);
            if (name.equals("rewriting")) verify(rewriting.getBytes(StandardCharsets.UTF_8), source);
            attachments.put(Path.of("v2", (String) source.get("attachment")), bytes);
        }
        return attachments;
    }

    private static void verify(byte[] bytes, Map<?, ?> source) throws IOException {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!hash.equals(source.get("sha256")) || bytes.length != ((Number) source.get("bytes")).longValue()) {
                throw new IOException("Public benchmark artifact changed: " + source.get("path"));
            }
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
