package de.regelsuche.didactic.analytics;

import de.regelsuche.didactic.DifficultyLevel;
import de.regelsuche.didactic.HintGenerator;
import de.regelsuche.didactic.PedagogyProfile;
import de.regelsuche.inventory.MiniJson;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistent {@link DidacticEventStore} backed by a single JSON file.
 *
 * <p>Each {@link #record(DidacticEvent)} call atomically rewrites the
 * file (temp + rename), the same approach used by
 * {@link de.regelsuche.proof.JsonFileProofCache} and the proof
 * job/artifact repositories. The format intentionally stays minimal —
 * one top-level {@code "events"} array of flat objects — so that the
 * analytics dashboard can stream and aggregate it without a JSON
 * library.</p>
 */
public final class JsonFileDidacticEventStore implements DidacticEventStore {

    private final Path file;
    private final List<DidacticEvent> events =
        Collections.synchronizedList(new ArrayList<>());

    public JsonFileDidacticEventStore(Path file) throws IOException {
        this.file = Objects.requireNonNull(file, "file");
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.isRegularFile(file)) {
            load();
        }
    }

    @Override
    public synchronized void record(DidacticEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
        try {
            persist();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to persist didactic event", ex);
        }
    }

    @Override
    public List<DidacticEvent> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    @Override
    public synchronized void clear() {
        events.clear();
        try {
            persist();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to persist didactic event", ex);
        }
    }

    // ── persistence ─────────────────────────────────────────────────────────

    private void load() throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        for (Map<String, String> raw : MiniJson.parseObjectArray(content, "events")) {
            DidacticEvent.Kind kind = parseEnum(DidacticEvent.Kind.class,
                raw.getOrDefault("kind", DidacticEvent.Kind.STEP_CHECK.name()),
                DidacticEvent.Kind.STEP_CHECK);
            Instant ts = parseInstant(raw.getOrDefault("timestamp", Instant.EPOCH.toString()));
            Optional<String> pathId = optionalString(raw.get("pathId"));
            Optional<DifficultyLevel> difficulty = optionalEnum(
                DifficultyLevel.class, raw.get("difficulty"));
            Optional<PedagogyProfile> profile = optionalEnum(
                PedagogyProfile.class, raw.get("pedagogyProfile"));
            Optional<Boolean> correct = optionalBoolean(raw.get("correct"));
            Optional<Boolean> appropriate = optionalBoolean(raw.get("didacticallyAppropriate"));
            Optional<String> misconception = optionalString(raw.get("misconceptionId"));
            Optional<HintGenerator.Strength> strength = optionalEnum(
                HintGenerator.Strength.class, raw.get("hintStrength"));
            events.add(new DidacticEvent(
                kind, ts, pathId, difficulty, profile,
                correct, appropriate, misconception, strength));
        }
    }

    private synchronized void persist() throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n  \"events\": [\n");
        List<DidacticEvent> snapshot;
        synchronized (events) {
            snapshot = new ArrayList<>(events);
        }
        for (int i = 0; i < snapshot.size(); i++) {
            DidacticEvent event = snapshot.get(i);
            builder.append("    {");
            builder.append("\"kind\":").append(quote(event.kind().name()));
            builder.append(",\"timestamp\":").append(quote(event.timestamp().toString()));
            event.pathId().ifPresent(v ->
                builder.append(",\"pathId\":").append(quote(v)));
            event.difficulty().ifPresent(v ->
                builder.append(",\"difficulty\":").append(quote(v.name())));
            event.pedagogyProfile().ifPresent(v ->
                builder.append(",\"pedagogyProfile\":").append(quote(v.name())));
            event.correct().ifPresent(v ->
                builder.append(",\"correct\":").append(v));
            event.didacticallyAppropriate().ifPresent(v ->
                builder.append(",\"didacticallyAppropriate\":").append(v));
            event.misconceptionId().ifPresent(v ->
                builder.append(",\"misconceptionId\":").append(quote(v)));
            event.hintStrength().ifPresent(v ->
                builder.append(",\"hintStrength\":").append(quote(v.name())));
            builder.append('}');
            if (i < snapshot.size() - 1) {
                builder.append(',');
            }
            builder.append('\n');
        }
        builder.append("  ]\n}\n");
        AtomicJsonFile.writeUtf8(file, builder.toString());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static Optional<String> optionalString(String raw) {
        return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(raw);
    }

    private static Optional<Boolean> optionalBoolean(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Boolean.parseBoolean(raw));
    }

    private static <E extends Enum<E>> Optional<E> optionalEnum(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, raw));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static Instant parseInstant(String raw) {
        try {
            return Instant.parse(raw);
        } catch (RuntimeException ex) {
            return Instant.EPOCH;
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
