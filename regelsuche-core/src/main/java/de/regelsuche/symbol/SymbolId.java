package de.regelsuche.symbol;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stable symbol identity; neither a display name nor a mathematical proof. */
public record SymbolId(UUID namespace, long ordinal) {
    public static final String IDENTIFIER_PREFIX = "rsym_";
    private static final Pattern IDENTIFIER = Pattern.compile("rsym_[0-9a-f]{32}_[1-9][0-9]{0,18}");

    public SymbolId {
        Objects.requireNonNull(namespace, "namespace");
        if (ordinal < 1) {
            throw new IllegalArgumentException("symbol ordinal must be positive");
        }
    }

    public String canonicalText() {
        return namespace + ":" + ordinal;
    }

    /** Reserved identifier understood by the existing expression parser and text engines. */
    public String identifier() {
        return IDENTIFIER_PREFIX + namespace.toString().replace("-", "") + "_" + ordinal;
    }

    public static SymbolId fromCanonicalText(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() < 38 || text.length() > 56 || text.charAt(36) != ':') {
            throw new IllegalArgumentException("invalid canonical symbol ID");
        }
        var id = new SymbolId(UUID.fromString(text.substring(0, 36)), Long.parseLong(text.substring(37)));
        if (!id.canonicalText().equals(text)) {
            throw new IllegalArgumentException("noncanonical symbol ID");
        }
        return id;
    }

    public static SymbolId fromIdentifier(String identifier) {
        Objects.requireNonNull(identifier, "identifier");
        if (identifier.length() > 57 || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("invalid reserved symbol identifier");
        }
        String hex = identifier.substring(5, 37);
        String uuid = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
            + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
        return fromCanonicalText(uuid + ":" + identifier.substring(38));
    }
}
