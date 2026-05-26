package de.regelsuche.api.searchgraph.semantic;

import java.util.List;

public record CanonicalExpressionCluster(
    String canonicalHash,
    String canonicalExpression,
    String representativeExpression,
    List<String> variants,
    int minDepth,
    int bestScore,
    String assumptionFingerprint
) {
    public CanonicalExpressionCluster {
        variants = variants == null ? List.of() : List.copyOf(variants);
        assumptionFingerprint = assumptionFingerprint == null ? "" : assumptionFingerprint;
    }
}
