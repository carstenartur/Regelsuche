package de.regelsuche.transform;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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

    public static void rememberAll(Map<String, String> replacements) {
        if (replacements == null || replacements.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            remember(entry.getKey(), entry.getValue());
        }
    }

    public static String nextPlaceholder(Set<String> blockedNames) {
        Set<String> blocked = blockedNames == null ? Set.of() : blockedNames;
        Map<String, String> current = PLACEHOLDER_TO_REPLACEMENT.get();
        for (char ch = 'A'; ch <= 'Z'; ch++) {
            String candidate = String.valueOf(ch);
            if (!current.containsKey(candidate) && !blocked.contains(candidate)) {
                return candidate;
            }
        }
        int suffix = 1;
        while (suffix <= 10_000) {
            for (char ch = 'A'; ch <= 'Z'; ch++) {
                String candidate = ch + Integer.toString(suffix);
                if (!current.containsKey(candidate) && !blocked.contains(candidate)) {
                    return candidate;
                }
            }
            suffix++;
        }
        throw new IllegalStateException("unable to allocate substitution placeholder");
    }

    public static Map<String, String> snapshot() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(PLACEHOLDER_TO_REPLACEMENT.get()));
    }

    /** Clears all substitution state for the current thread. Call at the start of each independent search. */
    public static void clear() {
        PLACEHOLDER_TO_REPLACEMENT.get().clear();
    }
}
