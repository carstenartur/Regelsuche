package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.search.moves.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistoryStageBoundaryTest {
    @Test void expensiveAndExplorationStagesDoNotInspectTheExpression(){
        var frozen=new RuleHistoryMemory().freeze();
        var state=MoveState.root("x+");var context=MoveContext.frozen("x");
        for(MovePriorityPolicy policy:List.of(new HistoryMovePolicy(frozen,HistoryMovePolicy.Weights.DEFAULT),
                HistoryMovePolicy.typed(frozen,HistoryMovePolicy.Weights.DEFAULT))){
            for(var kind:List.of(SearchMove.SourceKind.SOLVER,SearchMove.SourceKind.PREPARATION,
                    SearchMove.SourceKind.BRIDGE,SearchMove.SourceKind.HYPOTHESIS)){
                var descriptor=new MoveProvider.Descriptor(kind.name(),"fixed",kind,SearchMove.ProofStrength.REPLAYABLE,List.of(),SearchMove.ValueEvidence.UNKNOWN,"fixed-stage");
                var expected=kind==SearchMove.SourceKind.SOLVER || kind==SearchMove.SourceKind.PREPARATION
                    ?MovePriorityPolicy.Stage.EXPENSIVE:MovePriorityPolicy.Stage.EXPLORATION;
                assertEquals(expected,assertDoesNotThrow(()->policy.stage(descriptor,state,context)),
                    "fixed legacy stages must not introduce expression parsing");
            }
        }
    }
}
