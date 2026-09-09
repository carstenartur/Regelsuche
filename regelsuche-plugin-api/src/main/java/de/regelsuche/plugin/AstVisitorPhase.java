package de.regelsuche.plugin;

@de.regelsuche.api.StableApi(since = "1")
public enum AstVisitorPhase {
    AFTER_PARSE,
    BEFORE_NORMALIZATION,
    AFTER_NORMALIZATION,
    BEFORE_SEARCH,
    DURING_SEARCH,
    AFTER_TRANSFORMATION,
    BEFORE_OUTPUT,
    EXPLAIN_PATH
}
