package de.regelsuche.proof;

import de.regelsuche.json.MiniJson;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent {@link ProofCache} backed by a single JSON file.
 *
 * <p>Only entries with a status of at least
 * {@link CandidateProofStatus#FORMALLY_PROVABLE} are persisted — caching a
 * failed attempt is pointless. Writes are atomic (temp file + rename).</p>
 *
 * <p>Cache keys embed the prover {@code workerId} <em>and</em> a configurable
 * {@code proverVersion} (set at construction time): when the prover changes
 * the cache becomes transparent rather than risk reusing stale results.</p>
 */
public final class JsonFileProofCache implements ProofCache {

    private static final CandidateProofStatus MIN_CACHE_THRESHOLD =
        CandidateProofStatus.FORMALLY_PROVABLE;

    private final Path file;
    private final Map<ProofCacheKey, ProofCacheEntry> entries = new ConcurrentHashMap<>();

    public JsonFileProofCache(Path file) throws IOException {
        this.file = file;
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.isRegularFile(file)) {
            load();
        }
    }

    @Override
    public Optional<CandidateProofStatus> get(ProofCacheKey key) {
        ProofCacheEntry entry = entries.get(key);
        return entry == null ? Optional.empty() : Optional.of(entry.status());
    }

    @Override
    public void put(ProofCacheKey key, CandidateProofStatus status) {
        putEntry(key, ProofCacheEntry.ofStatus(status));
    }

    @Override
    public Optional<ProofCacheEntry> getEntry(ProofCacheKey key) {
        return Optional.ofNullable(entries.get(key));
    }

    @Override
    public synchronized void putEntry(ProofCacheKey key, ProofCacheEntry entry) {
        if (entry == null || entry.status() == null) {
            return;
        }
        if (entry.status().ordinal() < MIN_CACHE_THRESHOLD.ordinal()) {
            return;
        }
        entries.put(key, entry);
        try {
            persist();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to persist proof cache", ex);
        }
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public synchronized void clear() {
        entries.clear();
        try {
            persist();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to persist proof cache", ex);
        }
    }

    // ── persistence ─────────────────────────────────────────────────────────

    private void load() throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        for (Map<String, String> raw : MiniJson.parseObjectArray(content, "entries")) {
            ProofCacheKey key = new ProofCacheKey(
                raw.getOrDefault("canonicalLeft", ""),
                raw.getOrDefault("canonicalRight", ""),
                raw.getOrDefault("assumptionsSorted", ""),
                raw.getOrDefault("proverVersion", "unknown")
            );
            ProofCacheEntry entry = new ProofCacheEntry(
                safeStatus(raw.getOrDefault("status", CandidateProofStatus.FORMALLY_PROVABLE.name())),
                raw.getOrDefault("artifactId", ""),
                Instant.parse(raw.getOrDefault("createdAt", Instant.EPOCH.toString())),
                raw.getOrDefault("outputDigest", ""),
                parseLong(raw.getOrDefault("durationMillis", "0"))
            );
            entries.put(key, entry);
        }
    }

    private synchronized void persist() throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n  \"entries\": [\n");
        List<Map.Entry<ProofCacheKey, ProofCacheEntry>> sorted = new ArrayList<>(entries.entrySet());
        sorted.sort(Comparator
            .<Map.Entry<ProofCacheKey, ProofCacheEntry>, String>comparing(e -> e.getKey().canonicalLeft())
            .thenComparing(e -> e.getKey().canonicalRight())
            .thenComparing(e -> e.getKey().assumptionsSorted())
            .thenComparing(e -> e.getKey().proverVersion()));
        for (int i = 0; i < sorted.size(); i++) {
            ProofCacheKey key = sorted.get(i).getKey();
            ProofCacheEntry value = sorted.get(i).getValue();
            builder.append("    {");
            builder.append("\"canonicalLeft\":").append(quote(key.canonicalLeft()));
            builder.append(",\"canonicalRight\":").append(quote(key.canonicalRight()));
            builder.append(",\"assumptionsSorted\":").append(quote(key.assumptionsSorted()));
            builder.append(",\"proverVersion\":").append(quote(key.proverVersion()));
            builder.append(",\"status\":").append(quote(value.status().name()));
            builder.append(",\"artifactId\":").append(quote(value.artifactId()));
            builder.append(",\"createdAt\":").append(quote(value.createdAt().toString()));
            builder.append(",\"outputDigest\":").append(quote(value.outputDigest()));
            builder.append(",\"durationMillis\":").append(value.durationMillis());
            builder.append('}');
            if (i < sorted.size() - 1) {
                builder.append(',');
            }
            builder.append('\n');
        }
        builder.append("  ]\n}\n");
        AtomicJsonFile.writeUtf8(file, builder.toString());
    }

    private static CandidateProofStatus safeStatus(String name) {
        try {
            return CandidateProofStatus.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return CandidateProofStatus.FORMALLY_PROVABLE;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
        return builder.toString();
    }
}
