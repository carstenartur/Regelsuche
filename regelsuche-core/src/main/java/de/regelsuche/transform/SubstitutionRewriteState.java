package de.regelsuche.transform;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight symbolic substitution state for discovery operators. */
public final class SubstitutionRewriteState {
    private static final ThreadLocal<Map<String, String>> PLACEHOLDER_TO_REPLACEMENT =
        ThreadLocal.withInitial(LinkedHashMap::new);

    private SubstitutionRewriteState() {
    }

    public static void remember(String placeholder, String replacement) {
        if (placeholder == null || placeholder.isBlank() || replacement == null || replacement.isBlank()) {
            return;
        }
        PLACEHOLDER_TO_REPLACEMENT.get().put(placeholder, replacement);
    }

    public static Map<String, String> snapshot() {
        return Map.copyOf(PLACEHOLDER_TO_REPLACEMENT.get());
    }

    /** Clears all substitution state for the current thread. Call at the start of each independent search. */
    public static void clear() {
        PLACEHOLDER_TO_REPLACEMENT.get().clear();
    }
}
