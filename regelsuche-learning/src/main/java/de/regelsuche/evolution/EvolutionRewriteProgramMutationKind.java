package de.regelsuche.evolution;

/** Controlled, bounded mutations over {@link EvolutionRewriteProgramPlan}. */
public enum EvolutionRewriteProgramMutationKind {
    WRAP_REPEAT,
    WRAP_REQUIRE,
    WRAP_PRIORITY,
    WRAP_PRUNE,
    PREPEND_SOURCE,
    APPEND_SOURCE,
    SWAP_ADJACENT_CHILDREN,
    REMOVE_WRAPPER,
    CHOICE_TO_FIRST_APPLICABLE,
    FIRST_APPLICABLE_TO_CHOICE
}
