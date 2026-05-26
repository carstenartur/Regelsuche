package de.regelsuche.api.searchgraph.semantic;

public enum SemanticGraphViewMode {
    SEMANTIC,
    MAIN_PATH,
    COMPLEXITY,
    RAW;

    public static SemanticGraphViewMode parse(String value) {
        if (value == null || value.isBlank()) {
            return SEMANTIC;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "semantic" -> SEMANTIC;
            case "mainpath", "main_path", "main-path" -> MAIN_PATH;
            case "complexity", "complexity_map" -> COMPLEXITY;
            case "raw" -> RAW;
            default -> SEMANTIC;
        };
    }
}
