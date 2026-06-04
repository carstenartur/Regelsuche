package de.regelsuche.docs;

enum PromotionStage {
    OBSERVED,
    CANDIDATE,
    VALIDATED,
    PROMOTED,
    REUSED;

    boolean atLeast(PromotionStage other) {
        return ordinal() >= other.ordinal();
    }
}
