package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class FiniteSearchKnowledgeComparisonTest {
    @Test void allTenArmsRetainRealWorkAndAgreeOnTheCompleteFiniteControl() {
        var cases=List.of(FiniteSearchKnowledgeComparison.independentFactors(3),
            new FiniteSearchKnowledgeComparison.Case("missing-rule","a/a","1",3));
        var report=FiniteSearchKnowledgeComparison.runCases(cases);
        assertEquals(20,report.rows().size());
        assertEquals(20,report.measurements().size());
        assertTrue(report.canonicalJson().contains("\"schema\":\"regelsuche.finite-search-knowledge-comparison/v2\""));
        assertTrue(report.canonicalJson().contains("\"guidedController\":\"regelsuche.guided-factor-deepening/v2\""));
        for(var row:report.rows()) {
            assertEquals("COMPLETE_BOUNDED_CLOSURE",row.referenceStatus());
            assertTrue(row.totalNativeCalls()<=4096);
            assertEquals(row.caseId().equals("missing-rule")?-1:3,row.pathLength(),row.toString());
        }
        var serial=report.rows().stream().filter(r->r.caseId().endsWith("-3") && r.arm().equals("PORTFOLIO_1")).findFirst().orElseThrow();
        var parallel=report.rows().stream().filter(r->r.caseId().endsWith("-3") && r.arm().equals("PORTFOLIO_3")).findFirst().orElseThrow();
        assertEquals(serial.totalNativeCalls(),parallel.totalNativeCalls());
        assertTrue(report.measurements().stream().allMatch(m->m.wallNanos()>0));
        assertTrue(report.measurements().stream().filter(m->m.arm().startsWith("PORTFOLIO")).allMatch(m->m.currentThreadAllocatedBytes()==-1));
        assertEquals(report.canonicalJson(),FiniteSearchKnowledgeComparison.runCases(cases).canonicalJson());
    }
}
