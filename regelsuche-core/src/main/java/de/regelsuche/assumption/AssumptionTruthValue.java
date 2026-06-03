package de.regelsuche.assumption;

public enum AssumptionTruthValue {
    TRUE,
    FALSE,
    UNKNOWN;

    public AssumptionTruthValue and(AssumptionTruthValue other) {
        if (this == FALSE || other == FALSE) {
            return FALSE;
        }
        if (this == UNKNOWN || other == UNKNOWN) {
            return UNKNOWN;
        }
        return TRUE;
    }
}
