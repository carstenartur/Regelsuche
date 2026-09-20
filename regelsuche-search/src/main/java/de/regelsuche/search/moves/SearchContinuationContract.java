package de.regelsuche.search.moves;

/**
 * Explicit caller contract for continuation reuse in the existing search.
 * This is not mathematical proof authority and is never inferred from a rule's score.
 */
public enum SearchContinuationContract {
    /** Preserve full path identity, including depths, previous rule and resource history. */
    PATH_SENSITIVE,

    /**
     * The caller declares that, for fixed expression, assumptions and capabilities, providers,
     * verification and state valuation have the same mathematical behavior regardless of depths,
     * previous rule or resources already spent. Resource bounds must be monotone: less spent
     * never removes a permitted continuation. Any objective must also be state-local.
     *
     * Scheduling may rank paths differently, but must not hide eligible moves or change their
     * proof requirements. Mutable or history-dependent integrations must use PATH_SENSITIVE.
     * Only FAST mode accepts this contract; no complete bounded relation is claimed.
     */
    DECLARED_STATE_LOCAL
}
