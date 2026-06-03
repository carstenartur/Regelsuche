package de.regelsuche.transform;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight symbolic substitution state for discovery operators. */
public final class SubstitutionRewriteState {
    private static final Map<String, String> PLACEHOLDER_TO_REPLACEMENT = new LinkedHashMap<>();

    private SubstitutionRewriteState() {
    }

    public static synchronized void remember(String placeholder, String replacement) {
        if (placeholder == null || placeholder.isBlank() || replacement == null || replacement.isBlank()) {
            return;
        }
        PLACEHOLDER_TO_REPLACEMENT.put(placeholder, replacement);
    }

    public static synchronized Map<String, String> snapshot() {
        return Map.copyOf(PLACEHOLDER_TO_REPLACEMENT);
    }
}
