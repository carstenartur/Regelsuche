package de.regelsuche.mining;

import de.regelsuche.validation.CounterexampleSearchService;

import java.time.Instant;
import java.util.List;

/**
 * An immutable snapshot of one revision step in the counterexample-guided
 * hypothesis refinement loop.
 *
 * <p>Each revision tracks its parent so the full revision chain can be
 * reconstructed.  The first revision (index&nbsp;0) has {@code parentId = null}
 * and {@code triggerEvidence = null}; every subsequent revision has the
 * counterexample that triggered the preceding refinement step as
 * {@code triggerEvidence}.</p>
 *
 * @param id                    stable, unique identifier for this revision
 * @param parentId              id of the revision that this was derived from;
 *                              {@code null} for the initial revision
 * @param originHypothesisId    id of the {@link HypothesisCandidate} this chain
 *                              belongs to
 * @param revisionIndex         zero-based index of this revision in the chain
 * @param leftPattern           generalised left-hand side pattern at this revision
 * @param rightPattern          generalised right-hand side pattern at this revision
 * @param assumptions           assumptions under which the pattern holds
 * @param triggerEvidence       the counterexample that motivated this revision;
 *                              {@code null} for the initial revision
 * @param refinementStrategyName name of the strategy that produced this revision;
 *                              {@code null} for the initial revision
 * @param status                current lifecycle status of this revision
 * @param createdAt             timestamp when this revision was created
 */
public record HypothesisRevision(
    String id,
    String parentId,
    String originHypothesisId,
    int revisionIndex,
    String leftPattern,
    String rightPattern,
    List<String> assumptions,
    CounterexampleSearchService.Counterexample triggerEvidence,
    String refinementStrategyName,
    HypothesisRevisionStatus status,
    Instant createdAt
) {

    public HypothesisRevision {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("HypothesisRevision id must not be blank");
        }
        if (originHypothesisId == null || originHypothesisId.isBlank()) {
            throw new IllegalArgumentException("originHypothesisId must not be blank");
        }
        if (leftPattern == null || rightPattern == null) {
            throw new IllegalArgumentException("patterns must not be null");
        }
        if (revisionIndex < 0) {
            throw new IllegalArgumentException("revisionIndex must be >= 0");
        }
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        status = status == null ? HypothesisRevisionStatus.PROPOSED : status;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        refinementStrategyName = refinementStrategyName == null ? "" : refinementStrategyName;
    }

    /**
     * Creates the initial (first) revision for a hypothesis candidate.
     *
     * @param hypothesis the hypothesis being challenged
     * @return a new revision in {@link HypothesisRevisionStatus#PROPOSED} state
     */
    public static HypothesisRevision initial(HypothesisCandidate hypothesis) {
        return new HypothesisRevision(
            hypothesis.id() + "-r0",
            null,
            hypothesis.id(),
            0,
            hypothesis.leftPattern(),
            hypothesis.rightPattern(),
            hypothesis.assumptions(),
            null,
            null,
            HypothesisRevisionStatus.PROPOSED,
            Instant.now()
        );
    }

    /** Returns a copy with the given status. */
    public HypothesisRevision withStatus(HypothesisRevisionStatus newStatus) {
        return new HypothesisRevision(
            id, parentId, originHypothesisId, revisionIndex,
            leftPattern, rightPattern, assumptions,
            triggerEvidence, refinementStrategyName, newStatus, createdAt
        );
    }

    /**
     * Creates the canonical fingerprint used for cycle detection.
     * Two revisions are considered equivalent (cyclic) if they share
     * the same patterns and sorted assumptions.
     */
    public String canonicalFingerprint() {
        List<String> sortedAssumptions = assumptions.stream().sorted().toList();
        return leftPattern + " <=> " + rightPattern + " | " + sortedAssumptions;
    }
}
