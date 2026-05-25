package de.regelsuche.search;

import de.regelsuche.assumption.AssumptionSignature;

/**
 * Compact port for persisting large search traces.
 *
 * <p>Discovery runs with millions of edges must avoid duplicating expression
 * strings or rule IDs in every path step. Therefore this store exposes
 * interning and id-based edge/path APIs that can be backed by compact
 * encodings.
 */
public interface SearchTraceStore {

    /** Intern a canonical expression and return a stable numeric id. */
    long internExpression(String canonicalHash, String canonicalForm);

    /** Intern a rule id and return a stable numeric id. */
    long internRule(String ruleId);

    /** Intern an assumption signature and return a stable numeric id. */
    long internAssumptions(AssumptionSignature assumptions);

    /** Add one directed edge in the trace graph and return its id. */
    long addEdge(long fromExprId, long toExprId, int ruleId, long assumptionsId);

    /** Add one path represented as edge-id sequence and return its id. */
    long addPath(long[] edgeIds);
}
