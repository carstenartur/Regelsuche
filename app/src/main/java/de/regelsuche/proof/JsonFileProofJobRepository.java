package de.regelsuche.proof;

import de.regelsuche.assumption.Assumption;
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
 * File-backed {@link ProofJobRepository} that persists every state change to
 * a single JSON file.
 *
 * <p>Writes are performed via an atomic "write-temp-then-move" so the on-disk
 * representation never ends up half-written even if the JVM dies in the
 * middle of a {@link #save} call. On startup the file is parsed and all jobs
 * are re-hydrated; jobs that were {@link ProofJobStatus#RUNNING} at shutdown
 * are flipped back to {@link ProofJobStatus#QUEUED} so the scheduler resumes
 * them.</p>
 */
public final class JsonFileProofJobRepository implements ProofJobRepository {

    private final Path file;
    private final Map<String, ProofJob> jobs = new ConcurrentHashMap<>();

    public JsonFileProofJobRepository(Path file) throws IOException {
        this.file = file;
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.isRegularFile(file)) {
            load();
        }
    }

    // ── ProofJobRepository ──────────────────────────────────────────────────

    @Override
    public synchronized void save(ProofJob job) {
        jobs.put(job.id(), job);
        try {
            persist();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to persist proof job " + job.id(), ex);
        }
    }

    @Override
    public Optional<ProofJob> findById(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public List<ProofJob> findAll() {
        return List.copyOf(jobs.values());
    }

    @Override
    public List<ProofJob> findByStatus(ProofJobStatus status) {
        return jobs.values().stream().filter(j -> j.status() == status).toList();
    }

    @Override
    public Optional<ProofJob> findNextQueued() {
        return jobs.values().stream()
            .filter(j -> j.status() == ProofJobStatus.QUEUED
                || j.status() == ProofJobStatus.RETRYING)
            .min(Comparator
                .comparingInt(ProofJob::priority)
                .thenComparing(ProofJob::createdAt));
    }

    @Override
    public synchronized void delete(String id) {
        if (jobs.remove(id) != null) {
            try {
                persist();
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed to persist after delete", ex);
            }
        }
    }

    // ── persistence ─────────────────────────────────────────────────────────

    private void load() throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        for (Map<String, String> raw : MiniJson.parseObjectArray(content, "jobs")) {
            ProofJob job = jobFromRaw(raw);
            // RUNNING jobs at the time the JVM died are not actually running
            // any more — flip them back to QUEUED so the scheduler retries.
            if (job.status() == ProofJobStatus.RUNNING) {
                job = job.withStatus(ProofJobStatus.QUEUED);
            }
            jobs.put(job.id(), job);
        }
    }

    private synchronized void persist() throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n  \"jobs\": [\n");
        List<ProofJob> all = new ArrayList<>(jobs.values());
        all.sort(Comparator.comparing(ProofJob::createdAt));
        for (int i = 0; i < all.size(); i++) {
            ProofJob job = all.get(i);
            builder.append("    {");
            builder.append("\"id\":").append(quote(job.id()));
            builder.append(",\"leftPattern\":").append(quote(job.leftPattern()));
            builder.append(",\"rightPattern\":").append(quote(job.rightPattern()));
            builder.append(",\"assumptions\":").append(quoteArray(assumptionsAsStrings(job.assumptions())));
            builder.append(",\"assumptionKinds\":").append(quoteArray(assumptionKindsAsStrings(job.assumptions())));
            builder.append(",\"status\":").append(quote(job.status().name()));
            builder.append(",\"priority\":").append(job.priority());
            builder.append(",\"retryCount\":").append(job.retryCount());
            builder.append(",\"maxRetries\":").append(job.maxRetries());
            builder.append(",\"workerId\":").append(quote(job.workerType()));
            builder.append(",\"createdAt\":").append(quote(job.createdAt().toString()));
            builder.append(",\"updatedAt\":").append(quote(job.updatedAt().toString()));
            builder.append(",\"lastError\":").append(quote(job.errorMessage()));
            builder.append(",\"proofStatus\":")
                .append(job.resultStatus() == null ? "null" : quote(job.resultStatus().name()));
            builder.append('}');
            if (i < all.size() - 1) {
                builder.append(',');
            }
            builder.append('\n');
        }
        builder.append("  ]\n}\n");
        AtomicJsonFile.writeUtf8(file, builder.toString());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static ProofJob jobFromRaw(Map<String, String> raw) {
        List<String> expressions = MiniJson.parseStringArray(
            raw.getOrDefault("assumptions", "[]"));
        List<String> kinds = MiniJson.parseStringArray(
            raw.getOrDefault("assumptionKinds", "[]"));
        List<Assumption> assumptions = new ArrayList<>();
        for (int i = 0; i < expressions.size(); i++) {
            String expr = expressions.get(i);
            Assumption.Kind kind = i < kinds.size()
                ? safeKind(kinds.get(i))
                : Assumption.Kind.CUSTOM;
            assumptions.add(new Assumption(kind, expr));
        }
        String resultStatusRaw = raw.get("proofStatus");
        CandidateProofStatus resultStatus = resultStatusRaw == null
            || resultStatusRaw.equals("null") || resultStatusRaw.isBlank()
            ? null
            : safeStatus(resultStatusRaw);
        return new ProofJob(
            raw.get("id"),
            raw.getOrDefault("leftPattern", ""),
            raw.getOrDefault("rightPattern", ""),
            assumptions,
            ProofJobStatus.valueOf(raw.getOrDefault("status", ProofJobStatus.QUEUED.name())),
            Integer.parseInt(raw.getOrDefault("priority", "0")),
            Integer.parseInt(raw.getOrDefault("retryCount", "0")),
            Integer.parseInt(raw.getOrDefault("maxRetries", "3")),
            raw.getOrDefault("workerId", "lean4"),
            Instant.parse(raw.getOrDefault("createdAt", Instant.EPOCH.toString())),
            Instant.parse(raw.getOrDefault("updatedAt", Instant.EPOCH.toString())),
            resultStatus,
            raw.getOrDefault("lastError", "")
        );
    }

    private static Assumption.Kind safeKind(String name) {
        try {
            return Assumption.Kind.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return Assumption.Kind.CUSTOM;
        }
    }

    private static CandidateProofStatus safeStatus(String name) {
        try {
            return CandidateProofStatus.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return CandidateProofStatus.OBSERVED;
        }
    }

    private static List<String> assumptionsAsStrings(List<Assumption> assumptions) {
        return assumptions.stream().map(Assumption::expression).toList();
    }

    private static List<String> assumptionKindsAsStrings(List<Assumption> assumptions) {
        return assumptions.stream().map(a -> a.kind().name()).toList();
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

    private static String quoteArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(quote(values.get(i)));
        }
        builder.append(']');
        return builder.toString();
    }
}
