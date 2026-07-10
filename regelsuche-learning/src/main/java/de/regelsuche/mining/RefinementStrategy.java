package de.regelsuche.mining;

import de.regelsuche.validation.CounterexampleSearchService;

import java.util.List;
import java.util.Optional;

/**
 * Strategy port for refining a disproved hypothesis revision.
 *
 * <p>Each strategy inspects the failing revision and the counterexample that
 * disproved it, and optionally produces a {@link RefinementProposal} that
 * tightens the hypothesis (e.g. by adding an assumption, narrowing a
 * placeholder, or restricting a parameter domain).  Returning
 * {@link Optional#empty()} means the strategy cannot help with this particular
 * counterexample.</p>
 *
 * <p>Strategies are applied in priority order by
 * {@link HypothesisRefinementLoop}.  The first non-empty proposal wins for
 * one loop iteration; all strategies are re-tried on the successor revision.</p>
 */
public interface RefinementStrategy {

    /** Short, stable identifier used in revision provenance records. */
    String name();

    /**
     * Try to refine {@code revision} given the counterexample that disproved it.
     *
     * @param revision            the revision that was disproved
     * @param counterexampleResult the full counterexample search result, including
     *                            the concrete counterexample and inferred assumptions
     * @return a proposal for the refined hypothesis, or {@link Optional#empty()}
     *         if this strategy cannot apply
     */
    Optional<RefinementProposal> refine(
        HypothesisRevision revision,
        CounterexampleSearchService.CounterexampleSearchResult counterexampleResult
    );

    /**
     * The refined hypothesis proposal returned by a {@link RefinementStrategy}.
     *
     * @param newLeftPattern   left-hand side of the refined hypothesis (may equal
     *                         the original if only assumptions changed)
     * @param newRightPattern  right-hand side of the refined hypothesis
     * @param newAssumptions   updated/extended assumption list
     */
    record RefinementProposal(
        String newLeftPattern,
        String newRightPattern,
        List<String> newAssumptions
    ) {
        public RefinementProposal {
            if (newLeftPattern == null || newLeftPattern.isBlank()) {
                throw new IllegalArgumentException("newLeftPattern must not be blank");
            }
            if (newRightPattern == null || newRightPattern.isBlank()) {
                throw new IllegalArgumentException("newRightPattern must not be blank");
            }
            newAssumptions = newAssumptions == null ? List.of() : List.copyOf(newAssumptions);
        }
    }
}
