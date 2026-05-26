package de.regelsuche.api.searchgraph.semantic;

public record SemanticGraphViewConfigDto(
    SemanticGraphViewMode mode,
    boolean showLowSignal,
    boolean showAlternatives,
    boolean showVariants,
    int maxAlternatives,
    int maxVariantsPerCluster,
    SemanticLayoutDto layout
) {
    public SemanticGraphViewConfigDto {
        mode = mode == null ? SemanticGraphViewMode.SEMANTIC : mode;
        maxAlternatives = Math.max(0, maxAlternatives);
        maxVariantsPerCluster = Math.max(0, maxVariantsPerCluster);
        layout = layout == null ? new SemanticLayoutDto(java.util.Map.of(), java.util.List.of(), SemanticLayoutKind.MAIN_PATH_LAYERED) : layout;
    }
}
