package de.regelsuche.api.searchgraph.semantic;

import de.regelsuche.export.layout.MathLayout;
import de.regelsuche.mining.MacroMoveExpansion;
import java.util.List;

public record SemanticGraphEdgeDto(
    String from,
    String to,
    String ruleId,
    String ruleLatex,
    MathLayout layout,
    SemanticEdgeKind kind,
    int atomicStepCount,
    int hiddenStepCount,
    boolean lowSignal,
    boolean macroMove,
    MacroMoveExpansion macroMoveExpansion,
    List<String> sourceEdgeIds,
    double interestingness
) {
    public SemanticGraphEdgeDto {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from/to are required");
        }
        ruleId = ruleId == null ? "" : ruleId;
        ruleLatex = ruleLatex == null ? "" : ruleLatex;
        kind = kind == null ? SemanticEdgeKind.ALTERNATIVE : kind;
        atomicStepCount = Math.max(0, atomicStepCount);
        hiddenStepCount = Math.max(0, hiddenStepCount);
        sourceEdgeIds = sourceEdgeIds == null ? List.of() : List.copyOf(sourceEdgeIds);
    }
}
