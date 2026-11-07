package de.regelsuche.evolution;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Caller-owned reuse of independently verified generations; never a query-result cache. */
public final class TaskKnowledgeSession implements AutoCloseable {
    public record Preparation(CumulativeTaskKnowledge knowledge, boolean reused, long lookupWork, long preparationWork) {
        public long totalWork() { return Math.addExact(lookupWork, preparationWork); }
    }
    /** Character bounds describe retained canonical data, not a JVM heap or RSS limit. */
    public record Statistics(long loads, long hits, long admissions, long failures, long evictions, int retainedGenerations,
            long retainedCharacters, long lookupWork, long preparationWork, boolean accountingComplete) { }

    private final EvolutionGenome inventory;
    private final int maximumGenerations;
    private final long maximumCharacters;
    private final LinkedHashMap<String, CumulativeTaskKnowledge> retained = new LinkedHashMap<>(16, .75f, true);
    private long loads, hits, admissions, failures, evictions, characters, lookupWork, preparationWork;
    private boolean closed;

    public TaskKnowledgeSession(EvolutionGenome inventory, int maximumGenerations, long maximumCharacters) {
        this.inventory = Objects.requireNonNull(inventory);
        if (maximumGenerations < 1 || maximumGenerations > 256 || maximumCharacters < 1)
            throw new IllegalArgumentException("positive bounded prepared-generation capacity required");
        this.maximumGenerations = maximumGenerations;
        this.maximumCharacters = maximumCharacters;
    }

    /** Every call performs the strict file/content-address checks before consulting retained proof authority. */
    public synchronized Preparation prepare(Path path) throws IOException {
        if (closed) throw new IllegalStateException("knowledge session is closed");
        loads = Math.addExact(loads, 1);
        try {
            String json = TaskKnowledgeSnapshot.read(path);
            // Declared read/key-character units; actual CPU/I/O are measured separately by callers.
            long lookup = Math.addExact(json.length(), 1L);
            lookupWork = Math.addExact(lookupWork, lookup);
            var cached = retained.get(json);
            if (cached != null) {
                hits = Math.addExact(hits, 1);
                return new Preparation(cached, true, lookup, 0);
            }
            var knowledge = CumulativeTaskKnowledge.restorePrepared(inventory, json);
            long setup = knowledge.sessionSetupWork();
            preparationWork = Math.addExact(preparationWork, setup);
            remember(knowledge);
            return new Preparation(knowledge, false, lookup, setup);
        } catch (IOException | RuntimeException failure) {
            failures = Math.addExact(failures, 1);
            throw failure;
        }
    }

    private void remember(CumulativeTaskKnowledge knowledge) {
        String canonical = knowledge.toCanonicalJson();
        if (canonical.length() > maximumCharacters) return;
        while (retained.size() >= maximumGenerations || characters > maximumCharacters - canonical.length()) {
            var iterator = retained.entrySet().iterator();
            var oldest = iterator.next();
            characters -= oldest.getKey().length();
            iterator.remove();
            evictions = Math.addExact(evictions, 1);
        }
        retained.put(canonical, knowledge);
        characters = Math.addExact(characters, canonical.length());
    }

    /** Accept only an existing checked object, as returned by prepared loading or cumulative learning. */
    public synchronized Preparation retainVerified(CumulativeTaskKnowledge knowledge) {
        if (closed) throw new IllegalStateException("knowledge session is closed");
        Objects.requireNonNull(knowledge);
        if (!inventory.contentHash().equals(knowledge.inventoryHash()) || !knowledge.usesIndexedPreparation())
            throw new IllegalArgumentException("same-inventory prepared knowledge required");
        admissions = Math.addExact(admissions, 1);
        String canonical = knowledge.toCanonicalJson();
        long lookup = Math.addExact(canonical.length(), 1L);
        lookupWork = Math.addExact(lookupWork, lookup);
        var previous = retained.get(canonical);
        if (previous != null) return new Preparation(previous, true, lookup, 0);
        remember(knowledge);
        return new Preparation(knowledge, false, lookup, 0);
    }

    public synchronized Statistics statistics() {
        return new Statistics(loads, hits, admissions, failures, evictions, retained.size(), characters,
            lookupWork, preparationWork, failures == 0);
    }

    @Override public synchronized void close() {
        retained.clear(); characters = 0; closed = true;
    }
}
