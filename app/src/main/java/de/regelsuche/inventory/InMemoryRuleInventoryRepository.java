package de.regelsuche.inventory;

import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryRuleInventoryRepository implements RuleInventoryRepository {
    private final Map<String, ReusableRule> rules = new ConcurrentHashMap<>();
    private final Map<String, String> idsByCanonicalHash = new ConcurrentHashMap<>();
    private final Set<String> disabledIds = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> tagsByRuleId = new ConcurrentHashMap<>();

    @Override
    public synchronized void save(ReusableRule rule) {
        String hash = rule.canonicalHash();
        if (hash != null && !hash.isBlank()) {
            String existingId = idsByCanonicalHash.get(hash);
            if (existingId != null && !existingId.equals(rule.id())) {
                // Duplicate by canonical hash: keep the existing entry, bump its usage instead.
                ReusableRule existing = rules.get(existingId);
                if (existing != null) {
                    rules.put(existingId, existing.withUsage(Instant.now(), existing.usageCount() + 1));
                }
                return;
            }
            idsByCanonicalHash.put(hash, rule.id());
        }
        rules.put(rule.id(), rule);
    }

    @Override
    public List<ReusableRule> findAll() {
        // Sort by id so exports are deterministic regardless of the underlying map's iteration order.
        return rules.values().stream()
            .sorted(Comparator.comparing(ReusableRule::id))
            .toList();
    }

    @Override
    public void setEnabled(String ruleId, boolean enabled) {
        if (enabled) {
            disabledIds.remove(ruleId);
        } else {
            disabledIds.add(ruleId);
        }
    }

    @Override
    public boolean isEnabled(String ruleId) {
        return !disabledIds.contains(ruleId);
    }

    @Override
    public void addTag(String ruleId, String tag) {
        tagsByRuleId.computeIfAbsent(ruleId, key -> ConcurrentHashMap.newKeySet()).add(tag);
    }

    @Override
    public void removeTag(String ruleId, String tag) {
        Set<String> tags = tagsByRuleId.get(ruleId);
        if (tags != null) {
            tags.remove(tag);
        }
    }

    @Override
    public Set<String> tagsOf(String ruleId) {
        Set<String> tags = tagsByRuleId.get(ruleId);
        if (tags == null) {
            return Set.of();
        }
        return Set.copyOf(new LinkedHashSet<>(tags));
    }

    /**
     * Snapshot the repository to a single JSON file. The output is a
     * minimal, hand-written JSON document (no external library dependency)
     * containing all rules plus their {@code enabled}/{@code tags} state.
     */
    public synchronized void persistTo(Path file) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n  \"rules\": [\n");
        List<ReusableRule> all = findAll();
        for (int i = 0; i < all.size(); i++) {
            ReusableRule rule = all.get(i);
            builder.append("    {");
            builder.append("\"id\":").append(quote(rule.id()));
            builder.append(",\"leftPattern\":").append(quote(rule.leftPattern()));
            builder.append(",\"rightPattern\":").append(quote(rule.rightPattern()));
            builder.append(",\"parameterRelations\":").append(quoteArray(rule.parameterRelations()));
            builder.append(",\"proofStatus\":").append(quote(rule.proofStatus().name()));
            builder.append(",\"knownRuleStatus\":").append(quote(rule.knownRuleStatus().name()));
            builder.append(",\"supportingExamples\":").append(rule.supportingExamples());
            builder.append(",\"averageImprovement\":").append(rule.averageImprovement());
            builder.append(",\"createdAt\":").append(quote(rule.createdAt().toString()));
            builder.append(",\"canonicalHash\":").append(quote(rule.canonicalHash()));
            builder.append(",\"lastUsedAt\":").append(rule.lastUsedAt() == null ? "null" : quote(rule.lastUsedAt().toString()));
            builder.append(",\"usageCount\":").append(rule.usageCount());
            builder.append(",\"enabled\":").append(isEnabled(rule.id()));
            builder.append(",\"tags\":").append(quoteArray(new ArrayList<>(tagsOf(rule.id()))));
            builder.append("}");
            if (i < all.size() - 1) {
                builder.append(',');
            }
            builder.append('\n');
        }
        builder.append("  ]\n}\n");
        Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
    }

    /** Re-hydrate a repository from a file previously written by {@link #persistTo(Path)}. */
    public static InMemoryRuleInventoryRepository loadFrom(Path file) throws IOException {
        InMemoryRuleInventoryRepository repo = new InMemoryRuleInventoryRepository();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        for (Map<String, String> raw : MiniJson.parseObjectArray(content, "rules")) {
            List<String> relations = MiniJson.parseStringArray(raw.getOrDefault("parameterRelations", "[]"));
            List<String> tags = MiniJson.parseStringArray(raw.getOrDefault("tags", "[]"));
            String lastUsedAtRaw = raw.get("lastUsedAt");
            Instant lastUsedAt = lastUsedAtRaw == null || lastUsedAtRaw.equals("null") || lastUsedAtRaw.isBlank()
                ? null
                : Instant.parse(lastUsedAtRaw);
            ReusableRule rule = new ReusableRule(
                raw.get("id"),
                raw.get("leftPattern"),
                raw.get("rightPattern"),
                relations,
                CandidateProofStatus.valueOf(raw.getOrDefault("proofStatus", CandidateProofStatus.OBSERVED.name())),
                RuleStatus.valueOf(raw.getOrDefault("knownRuleStatus", RuleStatus.NEW.name())),
                Integer.parseInt(raw.getOrDefault("supportingExamples", "0")),
                Double.parseDouble(raw.getOrDefault("averageImprovement", "0")),
                Instant.parse(raw.getOrDefault("createdAt", Instant.EPOCH.toString())),
                raw.getOrDefault("canonicalHash", ""),
                lastUsedAt,
                Integer.parseInt(raw.getOrDefault("usageCount", "0"))
            );
            repo.save(rule);
            if ("false".equals(raw.get("enabled"))) {
                repo.setEnabled(rule.id(), false);
            }
            for (String tag : tags) {
                repo.addTag(rule.id(), tag);
            }
        }
        return repo;
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

