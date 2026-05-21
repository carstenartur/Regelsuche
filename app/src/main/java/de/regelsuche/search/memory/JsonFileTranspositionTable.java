package de.regelsuche.search.memory;

import de.regelsuche.inventory.MiniJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * File-backed {@link TranspositionTable} used by the killer-demo's
 * {@link de.regelsuche.persistence.GraphPersistenceMode#JSON_FILE} mode.
 *
 * <p>Persists entries to a single JSON document under
 * {@code storageDirectory/transposition.json}. Reads stay in memory (fast);
 * writes flush to disk so a fresh JVM picks up the search experience from
 * the previous run.</p>
 */
public final class JsonFileTranspositionTable extends InMemoryTranspositionTable {

    public static final String STORAGE_FILE = "transposition.json";

    private final Path storageDirectory;
    private final Path file;

    public JsonFileTranspositionTable(Path storageDirectory) {
        this.storageDirectory = storageDirectory;
        this.file = storageDirectory.resolve(STORAGE_FILE);
        try {
            Files.createDirectories(storageDirectory);
            if (Files.exists(file)) {
                hydrate();
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to initialize JSON transposition table at " + storageDirectory, ex);
        }
    }

    public Path storagePath() {
        return storageDirectory;
    }

    public Path filePath() {
        return file;
    }

    private void hydrate() throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) {
            return;
        }
        for (Map<String, String> raw : MiniJson.parseObjectArray(content, "entries")) {
            List<String> ruleIds = MiniJson.parseStringArray(raw.getOrDefault("reachedByRuleIds", "[]"));
            String firstSeen = raw.getOrDefault("firstSeen", Instant.EPOCH.toString());
            String lastSeen = raw.getOrDefault("lastSeen", firstSeen);
            TranspositionEntry entry = new TranspositionEntry(
                raw.get("canonicalHash"),
                raw.getOrDefault("canonicalExpression", ""),
                Integer.parseInt(raw.getOrDefault("bestScore", "0")),
                Integer.parseInt(raw.getOrDefault("minDepthSeen", "0")),
                raw.getOrDefault("bestKnownPathId", ""),
                new LinkedHashSet<>(ruleIds),
                Math.max(1, Integer.parseInt(raw.getOrDefault("visitCount", "1"))),
                Instant.parse(firstSeen),
                Instant.parse(lastSeen)
            );
            super.record(entry);
        }
    }

    @Override
    public synchronized TranspositionEntry record(TranspositionEntry entry) {
        TranspositionEntry merged = super.record(entry);
        persist();
        return merged;
    }

    @Override
    public synchronized void clear() {
        super.clear();
        persist();
    }

    @Override
    public synchronized void remove(String canonicalHash) {
        super.remove(canonicalHash);
        persist();
    }

    private void persist() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n  \"schemaVersion\": ").append(GlobalMemoryService.SCHEMA_VERSION).append(",\n");
        builder.append("  \"entries\": [\n");
        List<TranspositionEntry> all = new ArrayList<>(entries());
        for (int i = 0; i < all.size(); i++) {
            TranspositionEntry entry = all.get(i);
            builder.append("    {");
            builder.append("\"canonicalHash\":").append(quote(entry.canonicalHash()));
            builder.append(",\"canonicalExpression\":").append(quote(entry.canonicalExpression()));
            builder.append(",\"bestScore\":").append(entry.bestScore());
            builder.append(",\"minDepthSeen\":").append(entry.minDepthSeen());
            builder.append(",\"bestKnownPathId\":").append(quote(entry.bestKnownPathId()));
            builder.append(",\"reachedByRuleIds\":").append(stringArray(entry.reachedByRuleIds()));
            builder.append(",\"visitCount\":").append(entry.visitCount());
            builder.append(",\"firstSeen\":").append(quote(entry.firstSeen().toString()));
            builder.append(",\"lastSeen\":").append(quote(entry.lastSeen().toString()));
            builder.append('}');
            if (i < all.size() - 1) {
                builder.append(',');
            }
            builder.append('\n');
        }
        builder.append("  ]\n}\n");
        try {
            Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to persist transposition table to " + file, ex);
        }
    }

    private static String stringArray(java.util.Set<String> values) {
        StringBuilder b = new StringBuilder("[");
        int i = 0;
        for (String v : values) {
            if (i++ > 0) {
                b.append(',');
            }
            b.append(quote(v));
        }
        b.append(']');
        return b.toString();
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
        return b.toString();
    }
}
