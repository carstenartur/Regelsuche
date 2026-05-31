package de.regelsuche.search.estimate;

/**
 * Qualitative assessment of how likely an ongoing search is to explode in size.
 *
 * <p>Used by {@link SearchSpaceEstimator} to translate the estimated branching
 * factor and the projected number of states into a coarse signal the GUI/CLI can
 * surface to the user (issue #74, "Suchraumabschätzung").</p>
 */
public enum SearchSpaceRisk {
    /** Frontier is stable or shrinking; no explosion expected. */
    LOW,
    /** Frontier grows slowly; the search should still stay within budget. */
    MODERATE,
    /** Frontier grows fast enough to approach or exceed the visit budget. */
    HIGH,
    /** Frontier grows so fast that an explosion of the search space is likely. */
    EXPLOSIVE
}
