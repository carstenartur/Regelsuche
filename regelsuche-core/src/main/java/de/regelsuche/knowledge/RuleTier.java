package de.regelsuche.knowledge;

/**
 * Provenance tier of a rule pack.
 *
 * <p>The tier decides whether a pack can be switched off for ablation runs. Kernel packs carry the
 * soundness and termination assumptions of the search engine and are therefore never ablatable.
 */
public enum RuleTier {
    /** Soundness/termination critical rules: canonical normalization, numeric folding, identities. */
    KERNEL("kernel"),
    /** First-party shortcuts that may be switched off wholesale for baseline proofs. */
    FIRST_PARTY("first-party"),
    /** Contributions loaded through the plugin SPI. */
    PLUGIN("plugin");

    private final String id;

    RuleTier(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean ablatable() {
        return this != KERNEL;
    }

    public static RuleTier fromId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Rule tier id is required");
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        for (RuleTier tier : values()) {
            if (tier.id.equals(normalized)) {
                return tier;
            }
        }
        throw new IllegalArgumentException("Unknown rule tier: " + value);
    }
}
