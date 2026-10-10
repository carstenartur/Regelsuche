package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MoveSearchObjectiveWorkTest {
    private enum Score implements Function<String,MoveSearch.ObjectiveScore>,RetainedGraph.View { INSTANCE;
        @Override public MoveSearch.ObjectiveScore apply(String state) { return new MoveSearch.ObjectiveScore(0, 17); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {}
    }
    private static final class Work implements RetainedOperation.Sink {
        long units;
        RetainedOperation scope;
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }
        @Override public void executionWork(long value) { units += value; }
        @Override public void validationWork(long value) { fail("materialization is not mathematical verification"); }
        @Override public void checkpoint() { RetainedGraph.measure(scope); }
    }
    private static MoveWitnessPath<String,String,String> path(int length) {
        var path = MoveWitnessPath.<String,String,String>root();
        for (int i = 0; i < length; i++) path = path.append(new SearchExecution.Step<>("s" + i, "s" + (i + 1), "move", "checked"));
        return path;
    }
    @Test void nativeObjectiveDoesNotRedelegateAlreadyObservedNonemptyWitnessMaterialization() {
        for (int length : List.of(1, 3)) {
            var path = path(length);
            var direct = new Work();
            List<SearchExecution.Step<String,String,String>> expected;
            try (var scope = RetainedOperation.open(direct)) { direct.scope = scope; expected = path.steps(); }
            var objective = new MoveSearchObjective<String,String,String>(Score.INSTANCE, 0);
            long ledger = objective.observe("selected", path);
            var observed = new Work();
            long delegated;
            try (var scope = RetainedOperation.open(observed)) { observed.scope = scope; delegated = objective.finish(true); }
            assertEquals(expected, objective.witness());
            assertTrue(direct.units >= length + 1L, "the existing native producer has paid its immutable copy");
            assertEquals(direct.units, observed.units, "objective and direct path invoke the same observed producer");
            assertEquals(0, delegated, "native copy work must not also be delegated to ledger.search");
            assertEquals(17, ledger + delegated, "objective scoring stays a separately paid ledger component");
        }
    }
    @Test void inactiveObjectiveKeepsItsHistoricalMaterializationReceiptAndOrder() {
        for (int length : List.of(1, 3)) {
            var path = path(length);
            var objective = new MoveSearchObjective<String,String,String>(Score.INSTANCE, 0);
            long ledger = objective.observe("selected", path);
            assertEquals(length + 1L, objective.finish(false));
            assertEquals(17, ledger);
            assertEquals(path.steps(), objective.witness());
            assertEquals("s0", objective.witness().getFirst().source());
            assertEquals("s" + length, objective.witness().getLast().target());
        }
    }
}
