package de.regelsuche.evolution;

import de.regelsuche.search.moves.MoveSearch;
import java.util.Optional;

/** A native training gate derived from the actual price and length of an independently replayed proof. */
public final class ProofDepthChallenge {
    private ProofDepthChallenge() {}

    /**
     * Returns the same input and objective with room for one fewer search step and at most
     * the observed proof's price. The caller pays the reference solve even when there is
     * no shorter positive-depth gate. All other resource limits are preserved.
     */
    public static Optional<CheckedSchemaTaskSolver.Task> from(CheckedSchemaTaskSolver.Solution reference) {
        var evidence = reference.evidence();
        if (!reference.solved() || !evidence.accountingComplete() || evidence.witness().size() < 2) {
            return Optional.empty();
        }
        var task = reference.task();
        var original = task.budget();
        int depth = Math.min(original.maxSearchDepth(), evidence.witness().size() - 1);
        long price = Math.clamp(evidence.totalWork(), 1L, original.totalWork());
        var budget = new MoveSearch.Budget(original.maxPrimitiveSteps(), depth, original.maxTheoryWork(),
            original.maxStates(), price, original.maxComplexityDebt());
        return Optional.of(new CheckedSchemaTaskSolver.Task(task.source(), task.assumptions(), task.goal(), budget));
    }
}
