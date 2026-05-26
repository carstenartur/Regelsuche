package de.regelsuche.api.searchgraph.semantic;

import de.regelsuche.api.searchgraph.SearchGraphEdgeDto;
import de.regelsuche.transform.RewriteKind;

public final class RewriteSignalClassifier {

    public RewriteSignal classify(SearchGraphEdgeDto edge) {
        if (edge == null) {
            return RewriteSignal.LOW_SIGNAL;
        }
        String rule = (edge.ruleId() == null ? "" : edge.ruleId()).toLowerCase(java.util.Locale.ROOT);
        if (isLowSignalRuleName(rule)) {
            return RewriteSignal.LOW_SIGNAL;
        }
        if (edge.ruleKind() == RewriteKind.NORMALIZE) {
            return edge.scoreDelta() == 0 ? RewriteSignal.LOW_SIGNAL : RewriteSignal.MEDIUM_SIGNAL;
        }
        if (Math.abs(edge.scoreDelta()) >= 2) {
            return RewriteSignal.HIGH_SIGNAL;
        }
        return RewriteSignal.MEDIUM_SIGNAL;
    }

    private static boolean isLowSignalRuleName(String rule) {
        return rule.contains("commut")
            || rule.contains("associat")
            || rule.contains("parenth")
            || rule.contains("format")
            || rule.contains("sort")
            || rule.contains("canonical")
            || rule.contains("normalize")
            || rule.contains("neutral")
            || rule.contains("identity")
            || rule.contains("ast_");
    }
}
