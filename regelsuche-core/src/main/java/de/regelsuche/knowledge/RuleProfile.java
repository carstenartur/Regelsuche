package de.regelsuche.knowledge;

import java.util.Set;

public enum RuleProfile {
    CORE("core", Set.of(), false),
    CORE_PLUS_SYMPY_POLYNOMIAL("core+sympy-polynomial", Set.of("sympy-polynomial-basic"), false),
    EXPLORATORY("exploratory", Set.of("sympy-polynomial-basic"), false),
    ALL("all", Set.of(), true);

    private final String id;
    private final Set<String> enabledPackIds;
    private final boolean enableAllPacks;

    RuleProfile(String id, Set<String> enabledPackIds, boolean enableAllPacks) {
        this.id = id;
        this.enabledPackIds = Set.copyOf(enabledPackIds);
        this.enableAllPacks = enableAllPacks;
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
}
