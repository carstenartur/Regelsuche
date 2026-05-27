package de.regelsuche.provenance;

/** First-class mathematical discovery entities stored in the provenance graph. */
public enum ProvenanceNodeType {
    HYPOTHESIS,
    COUNTEREXAMPLE_SEARCH_ATTEMPT,
    COUNTEREXAMPLE,
    PROOF_ATTEMPT,
    SEARCH_RUN,
    MACRO_MOVE,
    SEED_EXPRESSION,
    ASSUMPTION_SIGNATURE,
    BENCHMARK_RUN,
    TRANSFORMATION_PATH,
    SYMBOLIC_REGRESSION_PROPOSAL,
    NUMERIC_RELATION_CANDIDATE,
    CAS_VALIDATION_ATTEMPT
}
