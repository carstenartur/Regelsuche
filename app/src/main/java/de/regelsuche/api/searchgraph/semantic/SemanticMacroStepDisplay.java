package de.regelsuche.api.searchgraph.semantic;

public enum SemanticMacroStepDisplay {
    COMPACT,
    DIDACTIC;

    public static SemanticMacroStepDisplay parse(String value) {
        if (value == null || value.isBlank()) {
            return COMPACT;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "didactic", "docs", "documentation" -> DIDACTIC;
            case "compact", "semantic" -> COMPACT;
            default -> COMPACT;
        };
    }
}
