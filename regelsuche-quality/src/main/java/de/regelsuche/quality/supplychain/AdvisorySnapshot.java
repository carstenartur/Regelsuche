package de.regelsuche.quality.supplychain;

import static de.regelsuche.quality.supplychain.SupplyChainJson.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

record AdvisorySnapshot(JsonNode manifest, byte[] archive, Map<String, JsonNode> advisories) {
    static AdvisorySnapshot load(Path root, JsonNode manifest) throws IOException {
        fields(manifest, Set.of("schema", "provider", "ecosystem", "revision", "createdAt", "url",
            "archive", "metadata", "upstreamRetention", "licenses"));
        require(text(manifest, "schema").equals("regelsuche.supply-chain-advisory-snapshot/v1"),
            "unsupported advisory snapshot");
        require(text(manifest, "provider").equals("OSV.dev") && text(manifest, "ecosystem").equals("Maven"),
            "unsupported advisory provider or ecosystem");
        String revision = text(manifest, "revision");
        require(revision.matches("[0-9]+"), "invalid GCS generation");
        Instant.parse(text(manifest, "createdAt"));
        require(text(manifest, "url").equals("https://storage.googleapis.com/download/storage/v1/b/osv-vulnerabilities/o/Maven%2Fall.zip?generation="
            + revision + "&alt=media"), "snapshot source must bind the exact generation");
        JsonNode archiveSpec = manifest.path("archive");
        Path file = boundFile(root, archiveSpec);
        require(Files.size(file) == integer(archiveSpec, "bytes") && Files.size(file) <= 64_000_000,
            "snapshot byte count differs or exceeds limit");
        byte[] bytes = Files.readAllBytes(file);
        JsonNode metadata = read(boundFile(root, manifest.path("metadata")));
        require(text(metadata, "bucket").equals("osv-vulnerabilities")
            && text(metadata, "name").equals("Maven/all.zip")
            && text(metadata, "generation").equals(revision)
            && text(metadata, "timeCreated").equals(text(manifest, "createdAt"))
            && text(metadata, "size").equals(Long.toString(bytes.length)), "provider metadata binding differs");
        try {
            require(Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes))
                .equals(text(metadata, "md5Hash")), "provider MD5 differs");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        boundFile(root, manifest.path("upstreamRetention"));
        Map<String, String> licenses = new HashMap<>();
        for (JsonNode license : array(manifest, "licenses")) {
            boundFile(root, license);
            require(text(license, "revision").matches("[0-9a-f]{40}"), "invalid source license revision");
            require(licenses.put(text(license, "source"), text(license, "license")) == null,
                "duplicate source license");
        }
        require(licenses.equals(Map.of("GHSA", "CC-BY-4.0", "MAL", "Apache-2.0")),
            "snapshot must retain the licenses of both actual data sources");
        Map<String, JsonNode> records = new HashMap<>();
        Map<String, Long> sourceCounts = new HashMap<>();
        long expanded = 0;
        long withdrawn = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                require(!entry.isDirectory() && entry.getName().matches("(?:GHSA-[a-z0-9-]+|MAL-[0-9-]+)\\.json"),
                    "unexpected advisory ZIP member: " + entry.getName());
                byte[] payload = zip.readNBytes(4_000_001);
                require(payload.length <= 4_000_000, "advisory exceeds byte limit");
                expanded += payload.length;
                require(expanded <= 64_000_000 && records.size() < 100_000, "expanded snapshot exceeds limits");
                JsonNode advisory = parse(payload);
                fields(advisory, Set.of("schema_version", "id", "modified", "published", "withdrawn", "aliases",
                    "related", "summary", "details", "affected", "references", "credits", "database_specific", "severity"));
                String id = text(advisory, "id");
                require(entry.getName().equals(id + ".json"), "advisory member identity differs");
                require(records.put(id, advisory) == null, "duplicate advisory ID: " + id);
                require(Set.of("1.7.3", "1.9.0").contains(text(advisory, "schema_version")),
                    "unqualified advisory schema version: " + id);
                Instant.parse(text(advisory, "modified"));
                if (advisory.has("withdrawn")) {
                    Instant.parse(text(advisory, "withdrawn"));
                    withdrawn++;
                }
                boolean hasMaven = false;
                for (JsonNode affected : array(advisory, "affected")) {
                    JsonNode pkg = affected.path("package");
                    if (pkg.path("ecosystem").asText().equals("Maven")) {
                        text(pkg, "name");
                        hasMaven = true;
                    }
                }
                require(hasMaven, "record in Maven export has no Maven affected package: " + id);
                sourceCounts.merge(id.substring(0, id.indexOf('-')), 1L, Long::sum);
                zip.closeEntry();
            }
        }
        require(records.size() == integer(archiveSpec, "entries") && !records.isEmpty(), "snapshot entry count differs");
        require(expanded == integer(archiveSpec, "uncompressedBytes"), "expanded snapshot byte count differs");
        require(withdrawn == integer(archiveSpec, "withdrawnEntries"), "withdrawn snapshot count differs");
        require(sourceCounts.equals(Map.of("GHSA", integer(archiveSpec.path("sourceCounts"), "GHSA"),
            "MAL", integer(archiveSpec.path("sourceCounts"), "MAL"))), "snapshot source counts differ");
        return new AdvisorySnapshot(manifest, bytes, Map.copyOf(records));
    }
}
