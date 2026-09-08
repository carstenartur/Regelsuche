package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DelegatedTransformationWorkTest {
    @Test
    void delegatedMechanicsStaySeparateAndSurviveAllCopiesAndAggregation() {
        var work = TransformationWorkMetrics.flatEngine(1).withDelegatedMechanicalWork(17)
            .withCandidateWork(new ExecutionWork(0, 1, 3));
        assertEquals(20, work.totalWorkUnits());
        assertEquals(23, work.totalWorkUnitsV2());
        assertEquals(1, work.engineInvocations());
        assertEquals(17, work.delegatedMechanicalWorkUnits());
        assertEquals(work, work.plus(TransformationWorkMetrics.ZERO));
        assertEquals(34, work.plus(work).delegatedMechanicalWorkUnits());
        assertEquals(22, work.withDuplicateCandidatesDropped(2).totalWorkUnits());
        assertEquals(17, work.withCandidateWork(ExecutionWork.ZERO).delegatedMechanicalWorkUnits());
        assertEquals(0, TransformationWorkMetrics.flatEngine(1).delegatedMechanicalWorkUnits());
        assertThrows(IllegalArgumentException.class, () -> work.withDelegatedMechanicalWork(-1));
    }
}
