package de.regelsuche.search.convergence;

/**
 * Rendering options for {@link SearchSpaceSvgWriter}.
 *
 * <p>These switches let large generated search spaces be simplified so they stay readable.
 * They also form the basis for upcoming clustering / equivalence-class folding work: the
 * writer already emits {@code data-canonical-key} and {@code data-convergence} attributes so
 * groups of equivalent states can later be folded without changing the rendering API.
 *
 * @param showLabels    render the descriptive role label inside each node
 * @param showRuleNames include the concrete rule id (not just the rule family) on edge labels
 * @param showNodeIds   render the short node id beneath each node
 * @param showEdgeLabels render edge labels at all
 */
public record SearchSpaceSvgOptions(
    boolean showLabels,
    boolean showRuleNames,
    boolean showNodeIds,
    boolean showEdgeLabels
) {
    /** Full detail: labels, rule names and edge labels, but no node ids. */
    public static SearchSpaceSvgOptions detailed() {
        return new SearchSpaceSvgOptions(true, true, false, true);
    }

    /** Compact rendering for large graphs: keep role labels, drop rule names and edge labels. */
    public static SearchSpaceSvgOptions compact() {
        return new SearchSpaceSvgOptions(true, false, false, false);
    }

    /** Minimal rendering: only shapes and convergence colouring, no text labels. */
    public static SearchSpaceSvgOptions minimal() {
        return new SearchSpaceSvgOptions(false, false, false, false);
    }
}
