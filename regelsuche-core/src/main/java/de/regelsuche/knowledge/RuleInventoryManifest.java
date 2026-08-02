package de.regelsuche.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Content-addressed inventory of the rules that were actually active for a run.
 *
 * <p>The manifest answers the reviewer question "what exactly was enabled for this proof?" with a
 * hash instead of a directory listing: a profile id, the enabled packs per tier and a SHA-256 over
 * the canonical serialization of the effective rule set.
 */
public record RuleInventoryManifest(
        String profileId,
        List<PackEntry> packs,
        List<String> ruleIds,
        String contentHash) {

    public RuleInventoryManifest {
        profileId = profileId == null || profileId.isBlank() ? RuleProfile.CORE.id() : profileId;
        packs = packs == null ? List.of() : List.copyOf(packs);
        ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
        contentHash = contentHash == null ? "" : contentHash;
    }

    /** A single pack contributing to (or explicitly excluded from) the effective rule set. */
    public record PackEntry(String packId, RuleTier tier, String source, boolean enabled, int ruleCount) {
        public PackEntry {
            if (packId == null || packId.isBlank()) {
                throw new IllegalArgumentException("packId is required");
            }
            if (tier == null) {
                throw new IllegalArgumentException("tier is required");
            }
            source = source == null ? "" : source;
        }
    }

    public static RuleInventoryManifest of(String profileId, List<PackEntry> packs, List<String> ruleIds) {
        List<PackEntry> packEntries = packs == null ? List.of() : List.copyOf(packs);
        List<String> rules = ruleIds == null ? List.of() : List.copyOf(ruleIds);
        String canonical = canonicalText(profileId, packEntries, rules);
        return new RuleInventoryManifest(profileId, packEntries, rules, sha256(canonical));
    }

    public List<PackEntry> enabledPacks() {
        return packs.stream().filter(PackEntry::enabled).toList();
    }

    public List<PackEntry> packsByTier(RuleTier tier) {
        return packs.stream().filter(entry -> entry.tier() == tier).toList();
    }

    public String canonicalText() {
        return canonicalText(profileId, packs, ruleIds);
    }

    private static String canonicalText(String profileId, List<PackEntry> packs, List<String> ruleIds) {
        StringBuilder builder = new StringBuilder();
        builder.append("profile=").append(profileId == null ? "" : profileId).append('\n');
        List<String> packLines = new ArrayList<>();
        for (PackEntry entry : packs) {
            packLines.add("pack=" + entry.packId()
                    + ";tier=" + entry.tier().id()
                    + ";source=" + entry.source()
                    + ";enabled=" + entry.enabled()
                    + ";rules=" + entry.ruleCount());
        }
        packLines.sort(String::compareTo);
        packLines.forEach(line -> builder.append(line).append('\n'));
        List<String> sortedRuleIds = new ArrayList<>(ruleIds);
        sortedRuleIds.sort(String::compareTo);
        sortedRuleIds.forEach(ruleId -> builder.append("rule=").append(ruleId).append('\n'));
        return builder.toString();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
