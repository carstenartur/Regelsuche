package de.regelsuche.checkpoint;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Persistable snapshot of a search job's exploration state.
 *
 * <p>Unlike a plain job listing, a {@code SearchCheckpoint} captures the
 * concrete information needed to <em>continue</em> a search rather than
 * restart it from the original expression:</p>
 *
 * <ul>
 *   <li>{@link #frontier()} — expressions waiting to be explored, ordered
 *       by the strategy's heuristic (best first).</li>
 *   <li>{@link #visitedHashes()} — canonical hashes already seen, so the
 *       resumed search avoids re-exploring them.</li>
 *   <li>{@link #bestPaths()} — the best successes found so far.</li>
 *   <li>{@link #randomSeed()} — seed of the random/MCTS strategy, so
 *       stochastic resumes are reproducible.</li>
 *   <li>{@link #profile()}, {@link #heuristicName()} — the configuration the
 *       job was running under.</li>
 * </ul>
 *
 * <p>The record is intentionally immutable; callers create a new
 * checkpoint per progress save and let the repository overwrite the
 * previous one.</p>
 */
public record SearchCheckpoint(
    String jobId,
    String originalExpression,
    String profile,
    String heuristicName,
    List<String> frontier,
    List<String> visitedHashes,
    List<BestPath> bestPaths,
    long randomSeed,
    Instant createdAt,
    Instant updatedAt
) {
    public SearchCheckpoint {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(originalExpression, "originalExpression");
        profile = profile == null ? "FAST_SIMPLIFY" : profile;
        heuristicName = heuristicName == null ? "default" : heuristicName;
        frontier = frontier == null ? List.of() : List.copyOf(frontier);
        visitedHashes = visitedHashes == null ? List.of() : List.copyOf(visitedHashes);
        bestPaths = bestPaths == null ? List.of() : List.copyOf(bestPaths);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    /**
     * @return the most promising expression to seed the resumed search with,
     *         falling back to the original expression when the frontier is
     *         empty.
     */
    public String resumeSeed() {
        if (!frontier.isEmpty()) {
            return frontier.get(0);
        }
        if (!bestPaths.isEmpty()) {
            return bestPaths.get(0).expression();
        }
        return originalExpression;
    }

    /** A single best-so-far path. */
    public record BestPath(String expression, int improvement, String lastRuleId) {
        public BestPath {
            Objects.requireNonNull(expression, "expression");
            lastRuleId = lastRuleId == null ? "" : lastRuleId;
        }
    }
}
