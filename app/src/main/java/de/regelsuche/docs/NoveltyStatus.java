package de.regelsuche.docs;

/** Classification of a discovery candidate relative to prior candidates and known rules. */
enum NoveltyStatus {
    NEW,
    DUPLICATE,
    ALPHA_EQUIVALENT,
    KNOWN_RULE,
    VARIANT,
    UNKNOWN
}
