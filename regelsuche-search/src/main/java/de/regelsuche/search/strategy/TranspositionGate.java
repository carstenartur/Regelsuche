package de.regelsuche.search.strategy;

import de.regelsuche.search.memory.PruningDecision;
import de.regelsuche.search.memory.PruningReason;
import de.regelsuche.search.memory.SearchMemory;
import de.regelsuche.search.memory.TranspositionEntry;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Helper used by {@link SearchStrategy} implementations to consult the
 * {@link SearchMemory transposition table} attached to a {@link SearchProblem}.
 * Only the state's current ValueKey-derived canonical identity is consulted.
 *
 * <p>Returns a {@link Verdict#KEEP keep verdict} if the state should be
 * expanded, or a {@link Verdict#PRUNE prune verdict} if not. When
 * {@code memory} is non-null, the helper records a {@link PruningDecision}
 * for revisits (and other non-first-sighting cases) to keep pruning decisions
 * explainable. Strategies that pass {@code null} memory behave exactly as
 * before – no lookup, no recording.</p>
 */
public final class TranspositionGate {

    public enum Verdict { KEEP, PRUNE }

    private TranspositionGate() {
    }

    /**
     * Evaluate a candidate state against the {@link SearchMemory}. The
     * helper:
     * <ul>
     *   <li>Records the visit in the {@link
     *       de.regelsuche.search.memory.TranspositionTable} (so subsequent
     *       lookups see it).</li>
     *   <li>Returns {@link Verdict#KEEP KEEP} for first sightings, states
     *       with strictly better score, states reached at lower depth, or
     *       states whose applied rule set adds at least one previously
     *       unseen rule id.</li>
     *   <li>Otherwise returns {@link Verdict#PRUNE PRUNE} and emits a
     *       matching {@link PruningDecision}.</li>
     * </ul>
     *
     * @param memory may be {@code null} – {@link Verdict#KEEP} is returned
     *               unconditionally in that case.
     * @param state candidate state about to be expanded.
     * @param pathId stable identifier of the path leading to {@code state}.
     */
    public static Verdict evaluate(SearchMemory memory, SearchState state, String pathId) {
        if (memory == null) {
            return Verdict.KEEP;
        }
        String identityHash = identityHash(state.canonicalHash(), state);
        TranspositionEntry existing = memory.table().lookup(identityHash).orElse(null);

        Set<String> ruleIds = new LinkedHashSet<>(state.appliedRuleIds());
        int score = state.score().weightedTotal();
        Instant now = Instant.now();
        TranspositionEntry candidate = new TranspositionEntry(
            identityHash,
            state.expression(),
            score,
            state.depth(),
            pathId == null ? "" : pathId,
            ruleIds,
            1,
            now,
            now
        );

        if (existing == null) {
            memory.table().record(candidate);
            return Verdict.KEEP;
        }
        if (score < existing.bestScore()) {
            memory.table().record(candidate);
            memory.recordDecision(new PruningDecision(
                state.expression(), identityHash,
                PruningReason.REPLACED_WORSE_PATH));
            return Verdict.KEEP;
        }
        if (score == existing.bestScore() && state.depth() < existing.minDepthSeen()) {
            memory.table().record(candidate);
            memory.recordDecision(new PruningDecision(
                state.expression(), identityHash,
                PruningReason.KEPT_LOWER_DEPTH));
            return Verdict.KEEP;
        }
        boolean hasNewRules = !existing.reachedByRuleIds().containsAll(ruleIds);
        if (hasNewRules) {
            memory.table().record(candidate);
            memory.recordDecision(new PruningDecision(
                state.expression(), identityHash,
                PruningReason.KEPT_NEW_RULE_COMBO));
            return Verdict.KEEP;
        }
        memory.table().record(candidate);
        PruningReason reason = score > existing.bestScore()
            ? PruningReason.ALREADY_KNOWN_BETTER
            : PruningReason.ALREADY_KNOWN_EQUAL;
        memory.recordDecision(new PruningDecision(
            state.expression(), identityHash, reason));
        return Verdict.PRUNE;
    }

    private static String identityHash(String baseHash, SearchState state) {
        String assumptions = state.assumptionFingerprint();
        if (assumptions.isBlank()) {
            return baseHash;
        }
        return baseHash + "\u0001assumptions:" + assumptions;
    }
}
