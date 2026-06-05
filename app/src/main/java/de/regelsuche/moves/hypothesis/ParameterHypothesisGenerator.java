package de.regelsuche.moves.hypothesis;

import java.util.List;

/**
 * Proposes mathematically plausible parameters for rewrite moves derived from
 * the structure of an expression (and optionally a target).
 *
 * <p>Implementations must be deterministic: for a given {@link ParameterContext}
 * they must always return the same hypotheses in the same order, and must never
 * enumerate an unbounded or random parameter space.</p>
 */
public interface ParameterHypothesisGenerator {

    /** @return a short, stable id identifying this generator. */
    String id();

    /**
     * Proposes parameter hypotheses for the given context.
     *
     * @param context the structural context of the input/target expression
     * @return a deterministically ordered, finite list of hypotheses
     */
    List<ParameterHypothesis> propose(ParameterContext context);
}
