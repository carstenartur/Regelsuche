package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.NumberExpr;
import java.util.List;
import org.junit.jupiter.api.Test;

class TypedSourceOnlyContextTest {
    @Test void sourceOnlyContextHasNoManufacturedGoal() {
        var context = TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION);
        assertTrue(context.sourceOnly());
        assertNull(context.goal(), "a source-only search must not receive a reference expression");
        assertEquals(MoveContext.Phase.FROZEN_EVALUATION, context.phase());
    }

    @Test void targetedConstructorStillRequiresAGoal() {
        assertThrows(NullPointerException.class, () -> new TypedMoveSearch.Context(null, List.of(), MoveContext.Phase.TRAIN));
        assertEquals(new NumberExpr(3), TypedMoveSearch.Context.frozen(new NumberExpr(3)).goal());
    }
}
