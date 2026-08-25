package de.regelsuche.polynomial;

/** Terminal mathematical or operational outcome of one factorization engine. */
public enum FactorizationStatus {
    COMPLETE_FACTORIZATION,
    IRREDUCIBLE,
    PARTIAL_FACTORIZATION,
    NO_FACTORIZATION_FOUND,
    UNSUPPORTED_DOMAIN,
    UNSUPPORTED_REQUEST,
    BUDGET_INCONCLUSIVE,
    TECHNICAL_FAILURE
}
