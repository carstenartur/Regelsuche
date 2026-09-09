package de.regelsuche.search.moves;

import de.regelsuche.assumption.AssumptionSignature;
import java.util.List;
import java.util.Objects;

/** Goals and assumptions are supplied equally to every provider and policy. */
public record MoveContext(String goal, List<String> initialAssumptions, Phase phase) {
    public enum Phase { TRAIN, FROZEN_EVALUATION, PRODUCTION }
    public MoveContext {
        goal = Objects.requireNonNull(goal, "goal");
        initialAssumptions = AssumptionSignature.ofExpressions(initialAssumptions).normalizedAssumptions();
        Objects.requireNonNull(phase, "phase");
    }
    public static MoveContext frozen(String goal) { return new MoveContext(goal, List.of(), Phase.FROZEN_EVALUATION); }
    public boolean carries(List<String> required, MoveState state) {
        var available = new java.util.HashSet<>(initialAssumptions);
        available.addAll(state.assumptions());
        return available.containsAll(AssumptionSignature.ofExpressions(required).normalizedAssumptions());
    }
}
