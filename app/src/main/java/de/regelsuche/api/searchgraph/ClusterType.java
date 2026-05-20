package de.regelsuche.api.searchgraph;

/**
 * Kinds of clusters surfaced in the Visual Search Graph.
 *
 * <ul>
 *   <li>{@link #RULE_USAGE} – nodes reached by the same atomic rule id.</li>
 *   <li>{@link #MACRO_SEQUENCE} – nodes participating in a recurring multi-step
 *       sequence discovered by the macro miner.</li>
 *   <li>{@link #STRUCTURAL_PATTERN} – nodes whose expressions share a canonical
 *       AST skeleton.</li>
 *   <li>{@link #SCORE_BASIN} – nodes whose score falls into the same bucket.</li>
 *   <li>{@link #PROOF_STATUS} – nodes that reached the same proof status.</li>
 * </ul>
 */
public enum ClusterType {
    RULE_USAGE,
    MACRO_SEQUENCE,
    STRUCTURAL_PATTERN,
    SCORE_BASIN,
    PROOF_STATUS
}
