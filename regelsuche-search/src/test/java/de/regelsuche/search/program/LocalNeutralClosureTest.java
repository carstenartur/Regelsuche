package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalNeutralClosureTest {
    @Test void closureRetainsTheActualThreePrimitiveStepsAndNoNewAssumptions() {
        var result = LocalNeutralClosure.execute("((x+0)*1)-0", new LocalNeutralClosure.Limits(3,128));
        assertEquals(LocalNeutralClosure.Status.COMPLETE_BOUNDED,result.status());
        var candidate = result.execution().orElseThrow().candidates().stream()
            .filter(c -> c.outputExpression().equals("x") && c.steps().size()==3).findFirst().orElseThrow();
        assertEquals(3,candidate.toTransformation().primitiveStepCount());
        assertTrue(candidate.steps().stream().allMatch(t -> t.assumptions().isEmpty()));
        assertEquals(result.sourceCalls().size(),result.nativeCalls());
        assertTrue(result.generatedCandidates()>=candidate.steps().size());
        var engine = new AstRewriteTransformationEngine(LocalNeutralClosure.inventory(),0,Integer.MAX_VALUE);
        String cursor=candidate.inputExpression();
        for (var step:candidate.steps()) {
            assertTrue(engine.transform(cursor).stream().anyMatch(t ->
                t.rule().equals(step.rule()) && t.applicationKey().equals(step.applicationKey())
                    && t.transformedExpression().equals(step.transformedExpression())));
            cursor=step.transformedExpression();
        }
        assertEquals("x",cursor);
    }

    @Test void aGlobalCallLimitFailsClosedAndNeverTurnsTheClosureIntoOneHiddenStep() {
        var result=LocalNeutralClosure.execute("((x+0)*1)-0",new LocalNeutralClosure.Limits(3,1));
        assertEquals(LocalNeutralClosure.Status.SOURCE_CALL_LIMIT,result.status());
        assertEquals(1,result.nativeCalls());
        assertFalse(result.sourceCalls().getFirst().generated().isEmpty());
        assertTrue(result.execution().isEmpty());
        assertEquals(LocalNeutralClosure.Status.SOURCE_CALL_LIMIT,
            LocalNeutralClosure.execute("x+0",new LocalNeutralClosure.Limits(1,0)).status());
        assertThrows(IllegalArgumentException.class,()->new LocalNeutralClosure.Limits(9,1));
    }

    @Test void theFrozenPackCannotExpandCancelAnUnknownDenominatorOrLoseUndefinedness() {
        assertEquals(6,LocalNeutralClosure.inventory().size());
        for(String source:List.of("a*(x+y)","a/a","0*(1/x)","x+x")) {
            var result=LocalNeutralClosure.execute(source,new LocalNeutralClosure.Limits(8,128));
            assertEquals(LocalNeutralClosure.Status.COMPLETE_BOUNDED,result.status());
            assertTrue(result.execution().orElseThrow().candidates().isEmpty(),source);
        }
        assertFalse(LocalNeutralClosure.inventoryHash().isBlank());
    }
}
