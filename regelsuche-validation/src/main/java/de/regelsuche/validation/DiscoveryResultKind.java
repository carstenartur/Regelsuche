package de.regelsuche.validation;

/** Classification levels for hidden-structure and macro-discovery outcomes. */
public enum DiscoveryResultKind {
    NO_CANDIDATE,
    HYPOTHESIS_ONLY,
    BRIDGE_FOUND,
    FACTORED,
    SIMPLIFIED,
    MACRO_LEARNED,
    MACRO_REUSED,
    FALSE_POSITIVE;

    public boolean hasCandidate() {
        return switch (this) {
            case NO_CANDIDATE -> false;
            case HYPOTHESIS_ONLY, BRIDGE_FOUND, FACTORED, SIMPLIFIED, MACRO_LEARNED, MACRO_REUSED, FALSE_POSITIVE -> true;
        };
    }

    public boolean hasBridge() {
        return switch (this) {
            case BRIDGE_FOUND, FACTORED, SIMPLIFIED, MACRO_LEARNED, MACRO_REUSED -> true;
            case NO_CANDIDATE, HYPOTHESIS_ONLY, FALSE_POSITIVE -> false;
        };
    }

    public boolean hasTransformedResult() {
        return switch (this) {
            case FACTORED, SIMPLIFIED, MACRO_LEARNED, MACRO_REUSED -> true;
            case NO_CANDIDATE, HYPOTHESIS_ONLY, BRIDGE_FOUND, FALSE_POSITIVE -> false;
        };
    }

    public boolean hasMacroLearning() {
        return this == MACRO_LEARNED || this == MACRO_REUSED;
    }

    public boolean hasMacroReuse() {
        return this == MACRO_REUSED;
    }

    public boolean isFalsePositive() {
        return this == FALSE_POSITIVE;
    }

    public boolean discovered() {
        return hasBridge() || hasTransformedResult() || hasMacroLearning() || hasMacroReuse();
    }
}
