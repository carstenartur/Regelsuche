package de.regelsuche.search.moves;

import de.regelsuche.ast.VariableExpr;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeBoundedExportTest {
    private static NativeMoveSearch.Problem problem(long work) {
        return new NativeMoveSearch.Problem(new VariableExpr("x"), TypedMoveSearch.Context.frozen(new VariableExpr("y")),
            List.of(), MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(1, 1, 0, 8, work));
    }
    private static NativeMoveSearch.Result run(long work, SearchContinuationContract continuation) {
        return NativeMoveSearch.boundedAccounting().search(problem(work), continuation);
    }
    @Test void qualifiedExportHasItsOwnPaidRevisionAndPreservesTheActualBoundedRelation() {
        var search = run(1_000_000, SearchContinuationContract.PATH_SENSITIVE);
        long before = search.totalWork();
        var export = search.exportLegacy(1_000_000, SearchExpressionStore.Limits.DEFAULT);
        assertTrue(export.complete());
        assertTrue(export.artifactAvailable());
        assertEquals(NativeMoveSearch.Result.QUALIFIED_EXPORT_REVISION, export.accounting().workRevision());
        assertEquals(MoveSearch.Outcome.BOUNDED_EXHAUSTED, export.projection().outcome());
        assertTrue(export.projection().completeBoundedRelation());
        assertTrue(export.accounting().work() > 0);
        assertEquals(before, search.totalWork());
        assertDoesNotThrow(() -> { search.exportLegacy(); });
    }
    @Test void completeAccountingCannotInventTheStateLocalBoundedRelation() {
        var export = run(1_000_000, SearchContinuationContract.DECLARED_STATE_LOCAL).exportLegacy(1_000_000, SearchExpressionStore.Limits.DEFAULT);
        assertTrue(export.complete());
        assertFalse(export.projection().completeBoundedRelation());
    }
    @Test void outputOverrunRetainsItsChargeButNoPartialArtifact() {
        var search = run(1_000_000, SearchContinuationContract.PATH_SENSITIVE);
        long searchWork = search.totalWork();
        var ample = search.exportLegacy(1_000_000, SearchExpressionStore.Limits.DEFAULT);
        var overrun = search.exportLegacy(ample.accounting().work() - 1, SearchExpressionStore.Limits.DEFAULT);
        assertFalse(overrun.complete());
        assertFalse(overrun.artifactAvailable());
        assertTrue(overrun.accounting().work() > overrun.accounting().budget());
        assertEquals("NATIVE_EXPORT_WORK_EXHAUSTED", overrun.accounting().detail());
        assertEquals(searchWork, search.totalWork());
        assertTrue(search.withinBudget());
    }
    @Test void successfulOutputCannotRefundAnOverrunSearchBudget() {
        var ample = run(1_000_000, SearchContinuationContract.PATH_SENSITIVE);
        var shortSearch = run(ample.totalWork() - 1, SearchContinuationContract.PATH_SENSITIVE);
        var exported = shortSearch.exportLegacy(1_000_000, SearchExpressionStore.Limits.DEFAULT);
        assertTrue(exported.complete());
        assertFalse(shortSearch.withinBudget());
        assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, exported.projection().outcome());
        assertFalse(exported.projection().completeBoundedRelation());
    }
    @Test void legacySearchCannotBeQualifiedByAnOtherwiseSuccessfulExport() {
        var legacy = new NativeMoveSearch().search(problem(1_000_000), SearchContinuationContract.PATH_SENSITIVE);
        var export = legacy.exportLegacy(1_000_000, SearchExpressionStore.Limits.DEFAULT);
        assertTrue(export.artifactAvailable());
        assertTrue(export.accounting().observationsComplete());
        assertFalse(export.complete());
        assertEquals(NativeMoveSearch.Result.EXPORT_REVISION, export.accounting().workRevision());
        assertFalse(export.projection().completeBoundedRelation());
    }
}
