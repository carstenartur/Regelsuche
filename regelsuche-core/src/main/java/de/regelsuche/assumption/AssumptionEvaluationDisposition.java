package de.regelsuche.assumption;

/**
 * Machine-readable disposition of one assumption evaluator invocation.
 *
 * <p>Only {@link #EVALUATED} may carry a decisive truth value. Unsupported,
 * timed-out and technically failed evaluators remain distinct but all map to
 * {@link AssumptionTruthValue#UNKNOWN} for aggregation.</p>
 */
public enum AssumptionEvaluationDisposition {
    EVALUATED,
    UNSUPPORTED,
    TIMEOUT,
    TECHNICAL_FAILURE
}
