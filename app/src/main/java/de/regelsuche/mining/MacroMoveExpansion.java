package de.regelsuche.mining;

import de.regelsuche.discovery.TransformationStep;

import java.util.List;

/**
 * Represents a macro move in the replay/search graph: a single high-level
 * step that compresses multiple atomic rewrite steps into one.
 *
 * <p>The macro move is presented compactly in the UI but can be expanded
 * to reveal the full atomic step sequence via {@link #atomicSteps()}.</p>
 *
 * <p>Created when a {@link MacroRuleCandidate} is applied during search,
 * or when the search graph is post-processed to identify collapsible sub-paths
 * that match a known macro rule.</p>
 *
 * @param macroRuleId       the ID of the macro rule that produced this move
 * @param fromExpression    the expression before applying the macro
 * @param toExpression      the expression after applying the macro
 * @param atomicSteps       the original atomic steps compressed by this macro
 * @param supportingPathIds reconstruction references for the supporting mined paths
 * @param compressionRatio  ratio of atomic steps replaced (≥ 1.0)
 * @param expanded          whether the UI should initially show the expanded view
 */
public record MacroMoveExpansion(
    String macroRuleId,
    String fromExpression,
    String toExpression,
    List<TransformationStep> atomicSteps,
    List<String> supportingPathIds,
    List<String> assumptions,
    double compressionRatio,
    boolean expanded,
    MacroMoveStatistics stats
) {
    public MacroMoveExpansion {
        if (macroRuleId == null || macroRuleId.isBlank()) {
            throw new IllegalArgumentException("macroRuleId must not be blank");
        }
        atomicSteps = atomicSteps == null ? List.of() : List.copyOf(atomicSteps);
        supportingPathIds = supportingPathIds == null ? List.of() : List.copyOf(supportingPathIds);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        if (compressionRatio < 1.0) {
            compressionRatio = 1.0;
        }
        stats = stats == null ? MacroMoveStatistics.empty() : stats;
    }

    public MacroMoveExpansion(
        String macroRuleId,
        String fromExpression,
        String toExpression,
        List<TransformationStep> atomicSteps,
        List<String> supportingPathIds,
        double compressionRatio,
        boolean expanded
    ) {
        this(macroRuleId, fromExpression, toExpression, atomicSteps, supportingPathIds, List.of(),
            compressionRatio, expanded, MacroMoveStatistics.empty());
    }

    public MacroMoveExpansion(
        String macroRuleId,
        String fromExpression,
        String toExpression,
        List<TransformationStep> atomicSteps,
        double compressionRatio,
        boolean expanded
    ) {
        this(macroRuleId, fromExpression, toExpression, atomicSteps, List.of(), List.of(), compressionRatio, expanded,
            MacroMoveStatistics.empty());
    }

    /** Returns a copy with the expanded flag toggled. */
    public MacroMoveExpansion withExpanded(boolean newExpanded) {
        return new MacroMoveExpansion(
            macroRuleId, fromExpression, toExpression,
            atomicSteps, supportingPathIds, assumptions, compressionRatio, newExpanded, stats
        );
    }

    /** Returns the number of atomic steps compressed. */
    public int atomicStepCount() {
        return atomicSteps.size();
    }

    /**
     * Creates a {@link MacroMoveExpansion} from a macro rule candidate and
     * the corresponding discovered atomic steps.
     *
     * @param candidate   the macro rule candidate that was applied
     * @param atomicSteps the atomic steps the macro compresses
     * @return a compact macro move with the full replay available
     */
    public static MacroMoveExpansion from(
        MacroRuleCandidate candidate,
        List<TransformationStep> atomicSteps
    ) {
        String from = atomicSteps.isEmpty() ? candidate.leftPattern()
            : atomicSteps.getFirst().beforeExpression();
        String to = atomicSteps.isEmpty() ? candidate.rightPattern()
            : atomicSteps.getLast().afterExpression();
        return new MacroMoveExpansion(
            candidate.id(),
            from,
            to,
            atomicSteps,
            candidate.supportingTransformationIds(),
            List.of(),
            candidate.compressionRatio(),
            false,
            MacroMoveStatistics.empty()
        );
    }
}
