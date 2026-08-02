package de.regelsuche.knowledge;

import java.util.Set;

public enum RuleProfile {
    CORE("core", Set.of(), false),
    /** Explicit alias for the default behaviour: kernel plus all first-party packs. */
    FULL("full", Set.of(), false),
    /** Baseline profile: kernel packs only, every ablatable pack switched off. */
    MINIMAL_KERNEL("minimal-kernel", Set.of(), false, false),
    CORE_PLUS_SYMPY_POLYNOMIAL("core+sympy-polynomial", Set.of("sympy-polynomial-basic"), false),
    EXPLORATORY("exploratory", Set.of("sympy-polynomial-basic"), false),
    ALL("all", Set.of(), true);

    private final String id;
    private final Set<String> enabledPackIds;
    private final boolean enableAllPacks;
    private final boolean includeFirstPartyDefaults;

    RuleProfile(String id, Set<String> enabledPackIds, boolean enableAllPacks) {
        this(id, enabledPackIds, enableAllPacks, true);
    }

    RuleProfile(String id, Set<String> enabledPackIds, boolean enableAllPacks, boolean includeFirstPartyDefaults) {
        this.id = id;
        this.enabledPackIds = Set.copyOf(enabledPackIds);
        this.enableAllPacks = enableAllPacks;
        this.includeFirstPartyDefaults = includeFirstPartyDefaults;
    }

    public String id() {
        return id;
    }

    public Set<String> enabledPackIds() {
        return enabledPackIds;
    }

    public boolean enableAllPacks() {
        return enableAllPacks;
    }

    /** Whether packs marked {@code enabledByDefault} outside the kernel tier stay enabled. */
    public boolean includeFirstPartyDefaults() {
        return includeFirstPartyDefaults;
    }

    public static RuleProfile fromId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Rule profile id is required");
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        for (RuleProfile profile : values()) {
            if (profile.id.equals(normalized)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown rule profile: " + value);
    }
}
