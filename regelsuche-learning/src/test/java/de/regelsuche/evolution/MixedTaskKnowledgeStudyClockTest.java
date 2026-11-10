package de.regelsuche.evolution;

import org.junit.jupiter.api.Test;

class MixedTaskKnowledgeStudyClockTest {
    @Test void negativeNanoTimeOriginIsValid() {
        MixedTaskKnowledgeStudyClockContract.verify(-100);
    }

    @Test void shortDurationAcrossNanoTimeWrapIsExact() {
        MixedTaskKnowledgeStudyClockContract.verify(Long.MAX_VALUE - 4);
    }
}
