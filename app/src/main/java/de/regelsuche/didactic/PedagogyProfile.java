package de.regelsuche.didactic;

/**
 * Pedagogical "style" that selects how verbose and how literal the
 * derivation should be — independent of the {@link DifficultyLevel}.
 *
 * <p>Two students at the same level may still ask for different output
 * styles: a quick check ({@link #CONCISE}) versus a fully written-out
 * homework solution ({@link #VERY_DETAILED}). The profile influences
 * cost weighting in {@link DidacticCostModel} and the verbosity of
 * {@link HintGenerator}.</p>
 *
 * <p>Spec item 3: the API can carry
 * <code>{"pedagogyProfile": "VERY_DETAILED"}</code> alongside a
 * <code>"goal"</code>.</p>
 */
public enum PedagogyProfile {

    /** Shortest correct path — no redundant intermediate steps. */
    CONCISE,

    /** School-book style: standard chains taught in class. */
    SCHOOL,

    /** Every intermediate step written out, including trivial ones. */
    VERY_DETAILED,

    /** Prefer elegant / symmetric forms; minimise "ugly" branches. */
    ELEGANT,

    /** Exam-friendly: predictable shape, easy to award partial credit. */
    EXAM_FRIENDLY
}
