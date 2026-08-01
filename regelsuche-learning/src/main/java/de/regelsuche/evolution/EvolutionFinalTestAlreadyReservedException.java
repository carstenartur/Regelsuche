package de.regelsuche.evolution;

import java.io.IOException;

/** Raised when a preregistered study has already consumed its FINAL TEST attempt. */
public final class EvolutionFinalTestAlreadyReservedException
        extends IOException {
    private static final long serialVersionUID = 1L;

    public EvolutionFinalTestAlreadyReservedException(String message) {
        super(message);
    }
}
