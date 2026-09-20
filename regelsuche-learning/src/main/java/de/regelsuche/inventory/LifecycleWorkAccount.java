package de.regelsuche.inventory;

import de.regelsuche.json.JsonWriter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Separate v3 accounting: historical receipts remain opaque and unchanged. */
public final class LifecycleWorkAccount {
    public static final String REVISION = "regelsuche.lifecycle-work/v3";
    public enum Phase { TRAINING_SEARCH, RULE_FORMATION_PROOF, SELECTION_TRAINING, COMPILATION, RESTORE_REPROOF, QUERY, FINAL_CHECK, OUTPUT }
    public record Receipt(String id, Phase phase, long exclusiveWork, long inclusiveWork,
            List<String> children, String rawRevision, String rawReceipt, String zeroReason) {
        public Receipt {
            requireText(id); Objects.requireNonNull(phase); requireText(rawRevision);
            Objects.requireNonNull(rawReceipt); Objects.requireNonNull(zeroReason);
            children = List.copyOf(children);
            if (exclusiveWork < 0 || inclusiveWork < exclusiveWork || (inclusiveWork == 0 && zeroReason.isBlank()))
                throw new IllegalArgumentException("negative or unexplained zero work");
        }
        public static Receipt measured(String id, Phase phase, long work, String revision, String raw) {
            return new Receipt(id, phase, work, work, List.of(), revision, raw, "");
        }
        public static Receipt skipped(String id, Phase phase, String reason) {
            requireText(reason);
            return new Receipt(id, phase, 0, 0, List.of(), REVISION, "", reason);
        }
    }
    private final List<Receipt> receipts;
    private final List<String> roots;
    private final Map<Phase, Long> byPhase;
    private final long totalWork;

    public LifecycleWorkAccount(List<Receipt> receipts, List<String> roots) {
        this.receipts = List.copyOf(receipts); this.roots = List.copyOf(roots);
        var index = new HashMap<String, Receipt>();
        for (var receipt : receipts) if (index.put(receipt.id(), receipt) != null)
            throw new IllegalArgumentException("duplicate receipt: " + receipt.id());
        var visited = new HashSet<String>();
        long total = 0;
        for (String root : roots) total = Math.addExact(total, visit(root, index, visited));
        if (visited.size() != receipts.size()) throw new IllegalArgumentException("unowned receipt");
        var phases = new EnumMap<Phase, Long>(Phase.class);
        for (var phase : Phase.values()) phases.put(phase, 0L);
        for (var receipt : receipts) phases.merge(receipt.phase(), receipt.exclusiveWork(), Math::addExact);
        byPhase = Map.copyOf(phases); totalWork = total;
    }
    private static long visit(String id, Map<String, Receipt> index, Set<String> visited) {
        var receipt = index.get(id);
        if (receipt == null || !visited.add(id)) throw new IllegalArgumentException("missing or doubled delegate: " + id);
        long total = receipt.exclusiveWork();
        for (var child : receipt.children()) total = Math.addExact(total, visit(child, index, visited));
        if (total != receipt.inclusiveWork()) throw new IllegalArgumentException("delegate total mismatch: " + id);
        return total;
    }
    public static LifecycleWorkAccount empty() { return new LifecycleWorkAccount(List.of(), List.of()); }
    public static LifecycleWorkAccount of(Receipt receipt) { return new LifecycleWorkAccount(List.of(receipt), List.of(receipt.id())); }
    public LifecycleWorkAccount plus(LifecycleWorkAccount other) {
        var combined = new ArrayList<>(receipts); combined.addAll(other.receipts);
        var combinedRoots = new ArrayList<>(roots); combinedRoots.addAll(other.roots);
        return new LifecycleWorkAccount(combined, combinedRoots);
    }
    public List<Receipt> receipts() { return receipts; }
    public List<String> roots() { return roots; }
    public Map<Phase, Long> byPhase() { return byPhase; }
    public long work(Phase phase) { return byPhase.get(phase); }
    public long totalWork() { return totalWork; }
    public String toCanonicalJson() {
        return new JsonWriter().beginObject().property("schema", REVISION).property("totalWork", totalWork)
            .stringArray("roots", roots).object("phases", out -> {
                for (var phase : Phase.values()) out.property(phase.name(), work(phase));
            }).array("receipts", out -> receipts.forEach(receipt -> out.objectValue(value -> value
                .property("id", receipt.id()).property("phase", receipt.phase().name())
                .property("exclusiveWork", receipt.exclusiveWork()).property("inclusiveWork", receipt.inclusiveWork())
                .stringArray("children", receipt.children()).property("rawRevision", receipt.rawRevision())
                .property("rawReceipt", receipt.rawReceipt()).property("zeroReason", receipt.zeroReason())))).endObject().toString();
    }
    static void requireText(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("nonblank binding required");
    }
}
