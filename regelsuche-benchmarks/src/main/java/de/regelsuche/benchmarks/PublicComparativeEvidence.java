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
    private static final Path PUBLICATION = Path.of("research/benchmarks/comparative-v2/report.json");
    private static final Path REGISTRY = PUBLICATION.resolveSibling("sources.json");

    private PublicComparativeEvidence() {}

    static Map<Path, byte[]> read(Path repository, String legacy, String rewriting) throws IOException {
        byte[] report = Files.readAllBytes(repository.resolve(PUBLICATION));
        var registry = new JsonReader(Files.readString(repository.resolve(REGISTRY))).readObject();
        if (!sha256(report).equals(registry.remove("compositionSha256"))) {
            throw new IOException("Public comparative composition digest differs: " + PUBLICATION);
        }
        var index = new JsonReader(new String(report, StandardCharsets.UTF_8)).readObject();
        if (!"regelsuche.public-comparative-benchmark/v2".equals(index.get("schema"))
                || !(index.get("sources") instanceof Map<?, ?> sources)
                || !sources.keySet().equals(registry.keySet())) {
            throw new IOException("Public comparative report does not cover its source registry");
        }
        var attachments = new LinkedHashMap<Path, byte[]>();
        attachments.put(Path.of("v2/report.json"), report);
        for (var entry : registry.entrySet()) {
            String name = entry.getKey();
            var source = (Map<?, ?>) entry.getValue();
            Path path = Path.of((String) source.get("path"));
            var indexed = new LinkedHashMap<>((Map<?, ?>) sources.get(name));
            String extension = path.toString().endsWith(".md") ? ".md" : ".json";
            String attachment = "evidence/" + source.get("sha256") + extension;
            if (!attachment.equals(indexed.remove("attachment")) || !source.equals(indexed)
                    || path.isAbsolute() || path.normalize().startsWith("..")) {
                throw new IOException("Invalid public benchmark source: " + name);
            }
            byte[] bytes = readSource(repository.resolve(path));
            verify(bytes, source);
            if (name.equals("legacy")) {
                verify(legacy.getBytes(StandardCharsets.UTF_8), source);
            }
            if (name.equals("rewriting")) {
                verify(rewriting.getBytes(StandardCharsets.UTF_8), source);
            }
            attachments.put(Path.of("v2", attachment), bytes);
        }
        return attachments;
    }

    private static byte[] readSource(Path path) throws IOException {
        try (var file = Files.newInputStream(path);
             var input = path.toString().endsWith(".gz") ? new GZIPInputStream(file) : file) {
            return input.readAllBytes();
        }
    }

    private static void verify(byte[] bytes, Map<?, ?> source) throws IOException {
        if (!sha256(bytes).equals(source.get("sha256"))
                || bytes.length != ((Number) source.get("bytes")).longValue()) {
            throw new IOException("Public benchmark artifact changed: " + source.get("path"));
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
