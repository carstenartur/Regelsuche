package de.regelsuche.evolution;

/** Bounded mutation operators admitted by the v1 evolutionary genome contract. */
public enum EvolutionMutationKind {
    GENERALIZE_PLACEHOLDER,
    SPECIALIZE_PLACEHOLDER,
    COMPOSE_REWRITES,
    ADD_ASSUMPTION,
    REMOVE_ASSUMPTION,
    REVERSE_REWRITE,
    ADD_RANKING_FEATURE,
    REMOVE_RANKING_FEATURE
}
