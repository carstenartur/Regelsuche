package de.regelsuche.inventory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.search.moves.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Transparent family and continuation observations; no labels or weights update during TEST. */
public final class RuleHistoryMemory {
    public record Stats(long applications, long success, long failure, long duplicates, long capabilityUnlocks,
            long measuredWorkSaved, long verificationWork) {
        public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, 0, 0);
        public Stats {
            if (applications < 0 || success < 0 || failure < 0 || duplicates < 0 || capabilityUnlocks < 0
                    || measuredWorkSaved < 0 || verificationWork < 0 || success > applications || failure > applications
                    || duplicates > applications) throw new IllegalArgumentException("invalid history counters");
        }
        public double successValue() { return (double) (success - failure) / (applications + 2); }
        public double duplicateRate() { return applications == 0 ? 0 : (double) duplicates / applications; }
        public double failureRate() { return applications == 0 ? 0 : (double) failure / applications; }
        public double averageWorkSaved() { return success == 0 ? 0 : (double) measuredWorkSaved / success; }
    }
    public record Snapshot(String schema, Map<String, Stats> families, Map<String, Stats> continuations) {
        public Snapshot {
            if (!"regelsuche.rule-history/v1".equals(schema)) throw new IllegalArgumentException("unsupported history schema");
            families = java.util.Collections.unmodifiableMap(new TreeMap<>(families));
            continuations = java.util.Collections.unmodifiableMap(new TreeMap<>(continuations));
        }
        public Stats family(String context, String family) { return families.getOrDefault(key(context, "", family), Stats.EMPTY); }
        public Stats continuation(String context, String previous, String next) { return continuations.getOrDefault(key(context, previous, next), Stats.EMPTY); }
        public void persistTo(Path path) throws IOException { mapper().writeValue(path.toFile(), this); }
        public static Snapshot load(Path path) throws IOException { return mapper().readValue(path.toFile(), Snapshot.class); }
    }
    private final Map<String, Stats> families = new TreeMap<>(), continuations = new TreeMap<>();
    private long measuredWork;
    public Snapshot freeze() { return new Snapshot("regelsuche.rule-history/v1", families, continuations); }
    /** TRAIN context inspection and two retained table-entry updates per observation. */
    public long measuredWork() { return measuredWork; }
    public void observe(MoveSearch.Result result, MoveContext.Phase phase, Map<String, Long> pairedWorkSavings) {
        if (phase != MoveContext.Phase.TRAIN) throw new IllegalArgumentException("history updates require TRAIN");
        if (result == null || pairedWorkSavings == null) throw new IllegalArgumentException("feedback result and savings are required");
        if (pairedWorkSavings.values().stream().anyMatch(value -> value == null || value < 0)) throw new IllegalArgumentException("invalid measured work saving");
        var successful = result.witness(); var credited = new java.util.HashSet<String>();
        var contexts = new java.util.HashMap<MoveState, StructuralMoveContext>();
        var deadEnds = new java.util.HashSet<>(result.deadEndStates());
        for (var event : result.events()) {
            var structure = contexts.computeIfAbsent(event.source(), source -> {
                var inspected = StructuralMoveContext.of(source);
                measuredWork = Math.addExact(measuredWork, 2L * inspected.visitedNodes());
                return inspected;
            });
            String context = structure.key();
            var witness = successful.stream().filter(step -> step.source().equals(event.source()) && step.move().equals(event.move())).findFirst();
            boolean won = witness.isPresent();
            boolean dead = event.decision() == MoveSearch.Decision.ENQUEUED && deadEnds.contains(event.target());
            boolean failed = dead || event.decision() == MoveSearch.Decision.PROOF_REJECTED
                || event.decision() == MoveSearch.Decision.ASSUMPTION_REJECTED;
            boolean duplicate = event.decision() == MoveSearch.Decision.DUPLICATE;
            long unlocks = witness.map(step -> step.target().capabilities().stream()
                .filter(capability -> !step.source().capabilities().contains(capability)).count()).orElse(0L);
            long saved = won && credited.add(event.move().ruleId()) ? pairedWorkSavings.getOrDefault(event.move().ruleId(), 0L) : 0;
            var delta = new Stats(1, won ? 1 : 0, failed ? 1 : 0, duplicate ? 1 : 0, unlocks, saved,
                event.verificationResult().map(MoveVerifier.Verification::work).orElse(0L));
            measuredWork = Math.addExact(measuredWork, 2);
            families.merge(key(context, "", event.move().ruleFamily()), delta, RuleHistoryMemory::plus);
            continuations.merge(key(context, event.source().previousRule(), event.move().ruleId()), delta, RuleHistoryMemory::plus);
        }
    }
    private static Stats plus(Stats a, Stats b) {
        return new Stats(Math.addExact(a.applications(), b.applications()), Math.addExact(a.success(), b.success()),
            Math.addExact(a.failure(), b.failure()), Math.addExact(a.duplicates(), b.duplicates()),
            Math.addExact(a.capabilityUnlocks(), b.capabilityUnlocks()), Math.addExact(a.measuredWorkSaved(), b.measuredWorkSaved()),
            Math.addExact(a.verificationWork(), b.verificationWork()));
    }
    private static String key(String context, String previous, String rule) {
        return new de.regelsuche.json.JsonWriter().beginArray().value(context).value(previous).value(rule).endArray().toString();
    }
    private static ObjectMapper mapper() { return new ObjectMapper().enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS); }
}
