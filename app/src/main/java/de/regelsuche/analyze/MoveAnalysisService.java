package de.regelsuche.analyze;

import de.regelsuche.api.searchgraph.SearchGraphDto;
import de.regelsuche.api.searchgraph.SearchGraphEdgeDto;
import de.regelsuche.api.searchgraph.SearchGraphNodeDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Chess-style" move analysis for a single expression node in the search graph.
 *
 * <p>Surfaces — for a given starting expression — the best continuation, the
 * alternative continuations, a short reason for the recommendation, the
 * dead-end alternatives and the rule that contributed the most score
 * improvement across the whole graph. The result powers the
 * {@code GET /api/analyze/move?expression=…} endpoint and the
 * "Best-Move-Panel" in the workbench UI.</p>
 */
public final class MoveAnalysisService {

    public MoveAnalysisDto analyze(SearchGraphDto graph, String expression) {
        if (graph == null || expression == null || expression.isBlank()) {
            return new MoveAnalysisDto(expression, null, List.of(), List.of(), "", "");
        }
        SearchGraphNodeDto startNode = findNode(graph, expression);
        if (startNode == null) {
            return new MoveAnalysisDto(expression, null, List.of(), List.of(),
                "Ausdruck nicht im Suchgraph gefunden.", "");
        }

        List<MoveAnalysisDto.Move> all = new ArrayList<>();
        for (SearchGraphEdgeDto edge : graph.edges()) {
            if (!edge.from().equals(startNode.id())) {
                continue;
            }
            SearchGraphNodeDto target = findById(graph, edge.to());
            if (target == null) {
                continue;
            }
            all.add(new MoveAnalysisDto.Move(
                edge.ruleId(),
                edge.ruleKind().name(),
                target.expression(),
                target.latex(),
                edge.scoreDelta(),
                target.isDeadEnd(),
                target.isBest(),
                edge.equivalencePreserving(),
                edge.assumptions(),
                edge.pathIds()
            ));
        }
        all.sort(Comparator
            .comparingInt((MoveAnalysisDto.Move m) -> m.deadEnd() ? 1 : 0)
            .thenComparingInt(MoveAnalysisDto.Move::scoreDelta));

        MoveAnalysisDto.Move best = all.isEmpty() ? null : all.get(0);
        List<MoveAnalysisDto.Move> alternatives = all.size() > 1 ? List.copyOf(all.subList(1, all.size())) : List.of();
        List<MoveAnalysisDto.Move> deadEnds = all.stream().filter(MoveAnalysisDto.Move::deadEnd).toList();
        String reason = buildReason(best, alternatives);
        String mostUsefulRule = mostUsefulRuleAcross(graph).orElse("");

        return new MoveAnalysisDto(expression, best, alternatives, deadEnds, reason, mostUsefulRule);
    }

    private static String buildReason(MoveAnalysisDto.Move best, List<MoveAnalysisDto.Move> alternatives) {
        if (best == null) {
            return "Keine Folgezüge bekannt — Ausdruck ist ein Blatt im Suchgraph.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Regel ").append(best.ruleId());
        if (best.scoreDelta() < 0) {
            sb.append(" reduziert den Score um ").append(-best.scoreDelta()).append(".");
        } else if (best.scoreDelta() == 0) {
            sb.append(" hält den Score gleich (Normalisierung).");
        } else {
            sb.append(" erhöht den Score temporär (vermutlich strategischer Zwischenschritt).");
        }
        if (best.isBest()) {
            sb.append(" Liegt auf dem aktuell besten Pfad.");
        }
        if (!best.equivalencePreserving()) {
            sb.append(" Achtung: Annahmen werden benötigt (")
                .append(String.join(", ", best.assumptions())).append(").");
        }
        if (!alternatives.isEmpty()) {
            sb.append(" ").append(alternatives.size()).append(" alternative Züge verfügbar.");
        }
        return sb.toString();
    }

    private static Optional<String> mostUsefulRuleAcross(SearchGraphDto graph) {
        Map<String, Integer> scoreByRule = new HashMap<>();
        for (SearchGraphEdgeDto edge : graph.edges()) {
            scoreByRule.merge(edge.ruleId(), -edge.scoreDelta(), Integer::sum);
        }
        return scoreByRule.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey);
    }

    private static SearchGraphNodeDto findNode(SearchGraphDto graph, String expression) {
        for (SearchGraphNodeDto n : graph.nodes()) {
            if (expression.equals(n.expression()) || expression.equals(n.id())) {
                return n;
            }
        }
        return null;
    }

    private static SearchGraphNodeDto findById(SearchGraphDto graph, String id) {
        for (SearchGraphNodeDto n : graph.nodes()) {
            if (n.id().equals(id)) {
                return n;
            }
        }
        return null;
    }
}
