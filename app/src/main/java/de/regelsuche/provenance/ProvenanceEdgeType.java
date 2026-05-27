package de.regelsuche.provenance;

/** Typed provenance relationships for discovery lineage and replay semantics. */
public enum ProvenanceEdgeType {
    SUPPORTED_BY,
    HYPOTHESIS_TESTED_BY,
    FOUND_COUNTEREXAMPLE,
    NO_COUNTEREXAMPLE_WITHIN_BUDGET,
    INCONCLUSIVE_DUE_TO,
    REFUTED_BY,
    GENERALIZES,
    DERIVED_FROM,
    USEFUL_FOR,
    REPLAY_OF,
    GENERATED_BY,
    PROPOSAL_FROM,
    VALIDATED_BY_CAS,
    FAILED_CAS_VALIDATION
}
