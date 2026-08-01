package de.regelsuche.evolution;

import java.io.IOException;

/** Durable, append-only storage boundary for one-shot FINAL TEST attempts. */
public interface EvolutionFinalTestAttemptStore {
    void reserve(EvolutionFinalTestReservation reservation) throws IOException;

    void writeEvaluation(EvolutionFinalTestEvaluation evaluation)
        throws IOException;
}
