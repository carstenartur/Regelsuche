package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrozenPrimitiveLineageTest {
    private final FrozenPrimitiveLineage replay = new FrozenPrimitiveLineage(AstRewriteTransformationEngine.defaultRules());

    @Test void bindsActualPrimitiveOccurrencesAndIntermediateSyntax() {
        var ids = List.of("ast_add_zero_right", "ast_divide_one");
        String source = "(sin(t)+0)/1";
        assertTrue(replay.verifies(source, "sin(t)", 2, ids, ids, List.of(), List.of(),
            List.of(source, "sin(t)/1", "sin(t)")));
        assertFalse(replay.verifies(source, "cos(t)", 2, ids, ids, List.of(), List.of(), List.of()));
        assertFalse(replay.verifies(source, "sin(t)", 2, ids, ids, List.of(), List.of(),
            List.of(source, "sin(t)+0", "sin(t)")));
        assertFalse(replay.verifies(source, "sin(t)", 2, ids, ids.reversed(), List.of(), List.of(), List.of()));
    }

    @Test void missingParentCannotProduceAStatePath() {
        var search = new TargetFreeRepresentationSearch().search("sin(t)+0",
            AstRewriteTransformationEngine.defaultRules(), TargetFreeRepresentationSearch.Budget.small());
        var state = search.content().candidateStates().getFirst();
        assertFalse(replay.bindsSource("sin(t)+0", FrozenPrimitiveLineage.statePath(state, java.util.Map.of())));
        assertTrue(replay.bindsSource("a+(b+c)", List.of("(a+b)+c")));
        assertFalse(replay.bindsSource("a/(b/c)", List.of("(a/b)/c")));
        assertFalse(replay.bindsSource("a+b", List.of("b+a")));
    }

    @Test void requiresExactlyTheActualPremisesProducedByTheRule() {
        var ids = List.of("ast_cancel_division_factor");
        assertTrue(replay.verifies("(x*y)/x", "y", 1, ids, ids, List.of(), List.of("x != 0"), List.of()));
        assertFalse(replay.verifies("(x*y)/x", "y", 1, ids, ids, List.of(), List.of(), List.of()));
        assertFalse(replay.verifies("(x*y)/x", "y", 1, ids, ids, List.of(), List.of("y != 0"), List.of()));
    }

    @Test void replayMustRetainDefinednessOfBothSides() {
        var ids = List.of("ast_multiply_zero_left");
        assertFalse(replay.verifies("0*(1/0)", "0", 1, ids, ids, List.of(), List.of(), List.of()));
        assertFalse(replay.verifies("0*(1/x)", "0", 1, ids, ids, List.of(), List.of(), List.of()));
        var premise = List.of("x != 0");
        assertTrue(replay.verifies("0*(1/x)", "0", 1, ids, ids, premise, premise, List.of()));
        assertFalse(replay.verifies("0*(1/(x*y))", "0", 1, ids, ids, premise, premise, List.of()));
        var factors = List.of("x != 0", "y != 0");
        assertTrue(replay.verifies("0*(1/(x*y))", "0", 1, ids, ids, factors, factors, List.of()));
    }
}
