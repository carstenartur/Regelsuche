package de.regelsuche.inventory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveSearch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/** Search activity is a separate sidecar. No API here mutates mathematical proof status or deletes a rule. */
public final class RuleActivityMemory {
    public enum Tier { HOT, WARM, COLD, SHADOW }
    public record Entry(double activity, long applications, long successes, long failures, long duplicates,
            long deadEnds, long capabilityUnlocks, long measuredWorkSaved, long lastUsedEpoch) {
        public Entry {
            if (!Double.isFinite(activity) || applications < 0 || successes < 0 || failures < 0 || duplicates < 0
                    || deadEnds < 0 || capabilityUnlocks < 0 || measuredWorkSaved < 0 || lastUsedEpoch < 0)
                throw new IllegalArgumentException("invalid activity observation");
        }
        public Tier tier() {
            if (successes == 0 && capabilityUnlocks == 0) return applications == 0 ? Tier.SHADOW : Tier.COLD;
            return activity >= 8 ? Tier.HOT : activity >= 1 ? Tier.WARM : Tier.COLD;
        }
        Entry decay() { return new Entry(activity * 0.95, applications, successes, failures, duplicates, deadEnds,
            capabilityUnlocks, measuredWorkSaved, lastUsedEpoch); }
    }
    public record Snapshot(String schema, long epoch, Map<String, Entry> rules) {
        public Snapshot {
            if (!"regelsuche.rule-activity/v1".equals(schema) || epoch < 0) throw new IllegalArgumentException("invalid activity snapshot");
            rules = java.util.Collections.unmodifiableMap(new TreeMap<>(rules));
            if (rules.entrySet().stream().anyMatch(entry -> entry.getKey().isBlank() || entry.getValue() == null
                    || entry.getValue().lastUsedEpoch() > epoch)) throw new IllegalArgumentException("invalid activity identity/epoch");
        }
        public Tier tier(String id) { return rules.containsKey(id) ? rules.get(id).tier() : Tier.SHADOW; }
        public void persistTo(Path path) throws IOException {
            if (path.toAbsolutePath().getParent() != null) Files.createDirectories(path.toAbsolutePath().getParent());
            mapper().writeValue(path.toFile(), this);
        }
        public static Snapshot load(Path path) throws IOException { return mapper().readValue(path.toFile(), Snapshot.class); }
    }
    private final Map<String, Entry> rules = new TreeMap<>();
    private long epoch;
    /** IDs come from the caller's validated durable inventory. Their presence grants no execution authority. */
    public RuleActivityMemory(Collection<String> inventoryIds) {
        for (String id : inventoryIds) {
            if (id == null || id.isBlank() || rules.putIfAbsent(id, new Entry(0, 0, 0, 0, 0, 0, 0, 0, 0)) != null)
                throw new IllegalArgumentException("invalid or duplicate inventory identity");
        }
    }
    public RuleActivityMemory(Snapshot snapshot) { rules.putAll(snapshot.rules()); epoch = snapshot.epoch(); }
    public Snapshot freeze() { return new Snapshot("regelsuche.rule-activity/v1", epoch, rules); }
    public void age(MoveContext.Phase phase) {
        requireTrain(phase); epoch = Math.addExact(epoch, 1); rules.replaceAll((id, entry) -> entry.decay());
    }
    /** Only completed witness edges count as successes; budget-cut enqueues are not failures or dead ends. */
    public void observe(MoveSearch.Result result, MoveContext.Phase phase, Map<String, Long> pairedWorkSavings) {
        requireTrain(phase);
        if (pairedWorkSavings.values().stream().anyMatch(value -> value == null || value < 0)) throw new IllegalArgumentException("negative measured saving");
        age(phase);
        for (var event : result.events()) {
            var entry = rules.get(event.move().ruleId()); if (entry == null) continue;
            boolean duplicate = event.decision() == MoveSearch.Decision.DUPLICATE;
            boolean failure = event.decision() == MoveSearch.Decision.PROOF_REJECTED || event.decision() == MoveSearch.Decision.ASSUMPTION_REJECTED;
            double penalty = duplicate ? 1 : failure ? 3 + Math.log1p(event.move().applicationCost()) : 0;
            rules.put(event.move().ruleId(), new Entry(entry.activity() - penalty, entry.applications() + 1, entry.successes(),
                entry.failures() + (failure ? 1 : 0), entry.duplicates() + (duplicate ? 1 : 0), entry.deadEnds(),
                entry.capabilityUnlocks(), entry.measuredWorkSaved(), epoch));
        }
        for (var state : result.deadEndStates()) {
            var entry = rules.get(state.previousRule()); if (entry == null) continue;
            rules.put(state.previousRule(), new Entry(entry.activity() - 3, entry.applications(), entry.successes(), entry.failures(),
                entry.duplicates(), entry.deadEnds() + 1, entry.capabilityUnlocks(), entry.measuredWorkSaved(), entry.lastUsedEpoch()));
        }
        var credited = new java.util.HashSet<String>();
        for (var step : result.witness()) {
            String id = step.move().ruleId(); var entry = rules.get(id); if (entry == null) continue;
            long saved = credited.add(id) ? pairedWorkSavings.getOrDefault(id, 0L) : 0;
            long unlocks = step.target().capabilities().stream().filter(capability -> !step.source().capabilities().contains(capability)).count();
            rules.put(id, new Entry(entry.activity() + 2 + 4 * unlocks + Math.log1p(saved), entry.applications(), entry.successes() + 1,
                entry.failures(), entry.duplicates(), entry.deadEnds(), entry.capabilityUnlocks() + unlocks,
                Math.addExact(entry.measuredWorkSaved(), saved), epoch));
        }
    }
    private static void requireTrain(MoveContext.Phase phase) {
        if (phase != MoveContext.Phase.TRAIN) throw new IllegalArgumentException("activity updates require TRAIN; evaluation snapshots are frozen");
    }
    private static ObjectMapper mapper() {
        return new ObjectMapper().enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
}
