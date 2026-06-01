package de.regelsuche.knowledge;

import java.util.Locale;

public enum SearchEffect {
    SIMPLIFYING,
    NORMALIZING,
    FACTORIZING,
    BRIDGING,
    EXPANDING;

    public static SearchEffect fromExternal(String value) {
        return SearchEffect.valueOf(value.strip().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
