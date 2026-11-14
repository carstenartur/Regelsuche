package de.regelsuche.evolution;

/** The numerical positive and transparent-null contract for one primary endpoint. */
public interface FlagshipEndpointThresholds {
    String contentHash();
    String toCanonicalJson();
    void requireCompatibleWithFinalSurface(EvolutionRewriteProgramHeldOutCommitment finalTest);
}
