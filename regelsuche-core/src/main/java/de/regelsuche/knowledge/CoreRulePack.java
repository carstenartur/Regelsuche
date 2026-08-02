package de.regelsuche.knowledge;

import java.util.List;

/**
 * Declarative grouping of built-in rewrite rules.
 *
 * <p>Core packs are tags over the ordered built-in rule list rather than containers. Keeping the
 * canonical rule order in one place means enabling every pack reproduces the historical rule
 * sequence exactly, while disabling a pack removes exactly its rules.
 */
public record CoreRulePack(
        String packId,
        String displayName,
        RuleTier tier,
        boolean enabledByDefault,
        String description,
        List<String> ruleIds) {

    public CoreRulePack {
        if (packId == null || packId.isBlank()) {
            throw new IllegalArgumentException("packId is required");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName is required");
        }
        if (tier == null) {
            throw new IllegalArgumentException("tier is required");
        }
        if (tier == RuleTier.KERNEL && !enabledByDefault) {
            throw new IllegalArgumentException("Kernel pack must be enabled by default: " + packId);
        }
        description = description == null ? "" : description;
        ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
        if (ruleIds.isEmpty()) {
            throw new IllegalArgumentException("Core rule pack must declare at least one rule: " + packId);
        }
    }
}
