package de.regelsuche.api.searchgraph.semantic;

import de.regelsuche.api.searchgraph.SearchGraphEdgeDto;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.mining.MacroMoveExpansion;
import de.regelsuche.mining.MacroRuleCandidate;
import java.util.ArrayList;
import java.util.List;

public final class SemanticMacroCompressor {

    public List<SemanticGraphEdgeDto> compress(
        List<SearchGraphEdgeDto> rawEdges,
        List<DiscoveredTransformation> paths,
        List<MacroRuleCandidate> macroRules
    ) {
        List<SemanticGraphEdgeDto> out = new ArrayList<>();
        if (rawEdges == null) {
            return out;
        }
        for (SearchGraphEdgeDto edge : rawEdges) {
            MacroMoveExpansion expansion = edge.macroMoveExpansion();
            boolean macro = expansion != null || isKnownMacro(edge.ruleId(), macroRules);
            out.add(new SemanticGraphEdgeDto(
                edge.from(),
                edge.to(),
                edge.ruleId(),
                edge.ruleLatex(),
                edge.layout(),
                macro ? SemanticEdgeKind.MACRO_MOVE : SemanticEdgeKind.ALTERNATIVE,
                macro && expansion != null ? expansion.atomicSteps().size() : 1,
                0,
                false,
                macro,
                expansion,
                List.of(rawEdgeId(edge)),
                Math.abs(edge.scoreDelta()) + (macro ? 1.0 : 0.0)
            ));
        }
        return out;
    }

    private boolean isKnownMacro(String ruleId, List<MacroRuleCandidate> macroRules) {
        if (ruleId == null || macroRules == null) {
            return false;
        }
        return macroRules.stream().anyMatch(m -> ruleId.equals(m.id()));
    }

    private static String rawEdgeId(SearchGraphEdgeDto edge) {
        return edge.from() + "->" + edge.to() + ":" + edge.ruleId();
    }
}
