package de.regelsuche.mining;

import de.regelsuche.validation.CounterexampleSearchService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Counterexample-guided hypothesis refinement loop.
 *
 * <p>Given a hypothesis candidate and a set of registered
 * {@link RefinementStrategy refinement strategies}, the loop:
 * <ol>
 *   <li>Creates the initial {@link HypothesisRevision} in
 *       {@link HypothesisRevisionStatus#PROPOSED} state.</li>
 *   <li>Submits the revision for a counterexample search
 *       ({@link HypothesisRevisionStatus#CHALLENGED}).</li>
 *   <li>If no counterexample is found, marks the revision as
 *       {@link HypothesisRevisionStatus#VALIDATED_WITHIN_BUDGET} (terminal).</li>
 *   <li>If a counterexample is found:
 *     <ul>
 *       <li>Marks the revision as
 *           {@link HypothesisRevisionStatus#COUNTEREXAMPLE_FOUND}.</li>
 *       <li>Applies the first strategy that produces a
 *           {@link RefinementStrategy.RefinementProposal}.</li>
 *       <li>If no strategy applies, or the revision budget is exhausted, or
 *           a cycle is detected, marks the revision
 *           {@link HypothesisRevisionStatus#REJECTED} (terminal).</li>
 *       <li>Otherwise, creates a successor revision in
 *           {@link HypothesisRevisionStatus#CHALLENGED_AGAIN} and repeats.</li>
 *     </ul>
 *   </li>
 *   <li>If the search is {@link CounterexampleSearchService.Status#INCONCLUSIVE},
 *       marks the revision {@link HypothesisRevisionStatus#INCONCLUSIVE}
 *       (terminal).</li>
 * </ol>
 *
 * <p>The full revision history (every attempted revision) is captured in the
 * returned {@link RefinementOutcome} so it can be serialized and shown in
 * reports.</p>
 */
public class HypothesisRefinementLoop {

    /** Default maximum number of revision attempts before giving up. */
    public static final int DEFAULT_MAX_REVISIONS = 5;

    private final CounterexampleSearchService counterexampleService;
    private final List<RefinementStrategy> strategies;
    private final int maxRevisions;

    /**
     * Constructs a refinement loop with the default maximum revision count and
     * the five built-in refinement strategies.
     *
     * @param counterexampleService the service to use for counterexample search
     */
    public HypothesisRefinementLoop(CounterexampleSearchService counterexampleService) {
        this(counterexampleService, defaultStrategies(), DEFAULT_MAX_REVISIONS);
    }

    /**
     * Constructs a refinement loop with explicit strategies and revision budget.
     *
     * @param counterexampleService the service to use for counterexample search
     * @param strategies            refinement strategies to try in order
     * @param maxRevisions          maximum number of revisions (must be &ge;&nbsp;1)
     */
    public HypothesisRefinementLoop(
        CounterexampleSearchService counterexampleService,
        List<RefinementStrategy> strategies,
        int maxRevisions
    ) {
        if (counterexampleService == null) {
            throw new IllegalArgumentException("counterexampleService must not be null");
        }
        if (maxRevisions < 1) {
            throw new IllegalArgumentException("maxRevisions must be >= 1");
        }
        this.counterexampleService = counterexampleService;
        this.strategies = strategies == null ? List.of() : List.copyOf(strategies);
        this.maxRevisions = maxRevisions;
    }

    /**
     * Runs the refinement loop for the given hypothesis.
     *
     * @param hypothesis the hypothesis to challenge and (if needed) refine
     * @return a {@link RefinementOutcome} with the terminal revision and full
     *         revision history
     */
    public RefinementOutcome refine(HypothesisCandidate hypothesis) {
        List<HypothesisRevision> history = new ArrayList<>();
        Set<String> seenFingerprints = new LinkedHashSet<>();

        HypothesisRevision current = HypothesisRevision.initial(hypothesis);
        history.add(current);

        for (int attempt = 0; attempt < maxRevisions; attempt++) {
            // Mark as challenged
            current = current.withStatus(
                attempt == 0
                    ? HypothesisRevisionStatus.CHALLENGED
                    : HypothesisRevisionStatus.CHALLENGED_AGAIN
            );
            history.set(history.size() - 1, current);

            // Run counterexample search
            CounterexampleSearchService.CounterexampleSearchResult searchResult =
                counterexampleService.search(
                    new CounterexampleSearchService.HypothesisInput(
                        current.id(),
                        current.leftPattern(),
                        current.rightPattern(),
                        current.assumptions()
                    ),
                    CounterexampleSearchService.CounterexampleBudget.defaultBudget()
                );

            if (searchResult.status() == CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND) {
                // Accept: no counterexample found within budget
                current = current.withStatus(HypothesisRevisionStatus.VALIDATED_WITHIN_BUDGET);
                history.set(history.size() - 1, current);
                return new RefinementOutcome(current, List.copyOf(history), searchResult);
            }

            if (searchResult.status() == CounterexampleSearchService.Status.INCONCLUSIVE) {
                current = current.withStatus(HypothesisRevisionStatus.INCONCLUSIVE);
                history.set(history.size() - 1, current);
                return new RefinementOutcome(current, List.copyOf(history), searchResult);
            }

            // Counterexample found — mark and try to refine
            current = current.withStatus(HypothesisRevisionStatus.COUNTEREXAMPLE_FOUND);
            history.set(history.size() - 1, current);

            if (attempt >= maxRevisions - 1) {
                // Budget exhausted
                current = current.withStatus(HypothesisRevisionStatus.REJECTED);
                history.set(history.size() - 1, current);
                return new RefinementOutcome(current, List.copyOf(history), searchResult);
            }

            // Try refinement strategies in order
            Optional<RefinementStrategy.RefinementProposal> proposal = Optional.empty();
            String strategyUsed = null;
            for (RefinementStrategy strategy : strategies) {
                proposal = strategy.refine(current, searchResult);
                if (proposal.isPresent()) {
                    strategyUsed = strategy.name();
                    break;
                }
            }

            if (proposal.isEmpty()) {
                // No strategy can help
                current = current.withStatus(HypothesisRevisionStatus.REJECTED);
                history.set(history.size() - 1, current);
                return new RefinementOutcome(current, List.copyOf(history), searchResult);
            }

            // Mark current as REFINED before creating the successor
            current = current.withStatus(HypothesisRevisionStatus.REFINED);
            history.set(history.size() - 1, current);

            // Create successor revision
            RefinementStrategy.RefinementProposal p = proposal.get();
            HypothesisRevision successor = new HypothesisRevision(
                current.originHypothesisId() + "-r" + (attempt + 1),
                current.id(),
                current.originHypothesisId(),
                attempt + 1,
                p.newLeftPattern(),
                p.newRightPattern(),
                p.newAssumptions(),
                searchResult.counterexample().orElse(null),
                strategyUsed,
                HypothesisRevisionStatus.PROPOSED,
                Instant.now()
            );

            // Cycle detection: check if this fingerprint was already seen
            String fingerprint = successor.canonicalFingerprint();
            if (seenFingerprints.contains(fingerprint)) {
                // Cycle detected — reject
                HypothesisRevision cycleRevision = successor.withStatus(HypothesisRevisionStatus.REJECTED);
                history.add(cycleRevision);
                return new RefinementOutcome(cycleRevision, List.copyOf(history), searchResult);
            }
            seenFingerprints.add(fingerprint);

            current = successor;
            history.add(current);
        }

        // Should not normally reach here, but handle defensively
        current = current.withStatus(HypothesisRevisionStatus.REJECTED);
        history.set(history.size() - 1, current);
        CounterexampleSearchService.CounterexampleSearchResult finalResult =
            CounterexampleSearchService.CounterexampleSearchResult.inconclusive(
                "revision budget exhausted after " + maxRevisions + " attempts"
            );
        return new RefinementOutcome(current, List.copyOf(history), finalResult);
    }

    /** The five built-in refinement strategies in priority order. */
    public static List<RefinementStrategy> defaultStrategies() {
        return List.of(
            new NonZeroDenominatorRefinementStrategy(),
            new PositivityRefinementStrategy(),
            new NumericRangeRefinementStrategy(),
            new StructuralCompatibilityRefinementStrategy(),
            new AstPlaceholderSpecializationRefinementStrategy()
        );
    }

    /**
     * The outcome of the refinement loop.
     *
     * @param terminalRevision   the last (terminal) revision in the chain
     * @param revisionHistory    all revisions created during the loop (in order)
     * @param lastSearchResult   the counterexample search result from the terminal round
     */
    public record RefinementOutcome(
        HypothesisRevision terminalRevision,
        List<HypothesisRevision> revisionHistory,
        CounterexampleSearchService.CounterexampleSearchResult lastSearchResult
    ) {
        public RefinementOutcome {
            if (terminalRevision == null) {
                throw new IllegalArgumentException("terminalRevision must not be null");
            }
            revisionHistory = revisionHistory == null ? List.of() : List.copyOf(revisionHistory);
            if (lastSearchResult == null) {
                lastSearchResult = CounterexampleSearchService.CounterexampleSearchResult.inconclusive();
            }
        }

        /** @return {@code true} if the terminal revision was accepted. */
        public boolean isAccepted() {
            return terminalRevision.status() == HypothesisRevisionStatus.VALIDATED_WITHIN_BUDGET;
        }

        /** @return {@code true} if the terminal revision was rejected or inconclusive. */
        public boolean isRejected() {
            return terminalRevision.status() == HypothesisRevisionStatus.REJECTED;
        }

        /** @return {@code true} if the result was inconclusive. */
        public boolean isInconclusive() {
            return terminalRevision.status() == HypothesisRevisionStatus.INCONCLUSIVE;
        }

        /**
         * @return the accepted terminal revision's patterns and assumptions,
         *         usable to update the original hypothesis; present only when
         *         {@link #isAccepted()} is {@code true}.
         */
        public Optional<RefinementStrategy.RefinementProposal> acceptedProposal() {
            if (!isAccepted()) {
                return Optional.empty();
            }
            return Optional.of(new RefinementStrategy.RefinementProposal(
                terminalRevision.leftPattern(),
                terminalRevision.rightPattern(),
                terminalRevision.assumptions()
            ));
        }
    }
}
