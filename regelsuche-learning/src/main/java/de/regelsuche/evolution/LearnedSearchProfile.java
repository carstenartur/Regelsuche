package de.regelsuche.evolution;

/** Knowledge and scheduling controls; EXPERT is an explicitly handwritten reference. */
public enum LearnedSearchProfile {
    BASE, LEARNED_NAIVE, LEARNED_RANKED, EXPERT;

    public boolean usesFrozenLearning() { return this == LEARNED_NAIVE || this == LEARNED_RANKED; }
}
