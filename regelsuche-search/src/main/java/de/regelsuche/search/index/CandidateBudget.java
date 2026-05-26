package de.regelsuche.search.index;

/** Candidate retrieval limits applied after index narrowing. */
public record CandidateBudget(int maxAtomicRules, int maxMacroMoves) {
    public CandidateBudget {
        maxAtomicRules = normalize(maxAtomicRules);
        maxMacroMoves = normalize(maxMacroMoves);
    }

    public static CandidateBudget unbounded() {
        return new CandidateBudget(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    private static int normalize(int value) {
        return value < 0 ? Integer.MAX_VALUE : value;
    }
}
