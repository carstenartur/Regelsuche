package de.regelsuche.polynomial;

import java.util.Objects;

/** Evidence strength attached to one exact factorization candidate. */
public enum FactorizationCompleteness {
    DECOMPOSITION_ONLY,
    BACKEND_CLAIMED_COMPLETE,
    INDEPENDENTLY_CERTIFIED_COMPLETE;

    public boolean meets(FactorizationCompleteness required) {
        return ordinal() >= Objects.requireNonNull(
            required,
            "required").ordinal();
    }
}
