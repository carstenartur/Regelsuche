package de.regelsuche.search.index;

import de.regelsuche.transform.RewriteRule;
import java.util.List;

/** Atomic-rule and macro-move candidates selected by a {@link RuleCandidateIndex}. */
public record CandidateSet(
    List<RewriteRule> atomicRules,
    List<TermRuleIndex.IndexedMacroMove> macroMoves,
    IndexMetrics metrics
) {
    public CandidateSet {
        atomicRules = atomicRules == null ? List.of() : List.copyOf(atomicRules);
        macroMoves = macroMoves == null ? List.of() : List.copyOf(macroMoves);
        metrics = metrics == null ? IndexMetrics.empty() : metrics;
    }
}
