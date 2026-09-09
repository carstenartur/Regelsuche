package de.regelsuche.math.algorithms.linalg;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.math.algorithms.linalg.LinearRepresentationPlanner.Route;
import de.regelsuche.math.algorithms.linalg.LinearRepresentationPlanner.Result;

class LinearRepresentationPlannerTest {
    private final LinearRepresentationPlanner planner = new LinearRepresentationPlanner();
    private static final List<String> PAIRS = List.of("x+y=3", "x-y=1", "z+w=7", "z-w=1", "u+v=11", "u-v=1", "a+b=15", "a-b=1");

    @Test void allRoutesProveTheSameCompleteSolutionSet() {
        for (List<String> equations : List.of(PAIRS,
                List.of("x+y=3", "z+w=7"),
                List.of("x-x=0", "y+z=2"),
                List.of("x=1", "y=2", "0=1"),
                List.of("x+y=1", "x+y=2", "z=0"))) {
            var baseline = planner.solve(equations, Route.DIRECT, 20_000);
            assertEquals("SOLVED", baseline.status());
            for (Route route : Route.values()) {
                Result result = planner.solve(equations, route, 20_000);
                assertEquals(baseline.solution(), result.solution(), equations + " " + route);
                assertTrue(planner.verify(result));
                assertTrue(planner.audit(result, 20_000).verified());
            }
        }
    }

    @Test void selectsFromSourceBeforeEliminationAndAccountsForEveryBudget() {
        var plan = planner.prepare(PAIRS, 20_000);
        assertEquals(4, plan.blocks().size());
        assertThrows(UnsupportedOperationException.class, () -> plan.blocks().getFirst().add(5));
        var automatic = planner.execute(plan, Route.AUTO, 20_000);
        assertEquals(Route.BLOCKS, automatic.selected());
        var direct = planner.solve(PAIRS, Route.DIRECT, 20_000);
        assertTrue(automatic.totalWork() < direct.totalWork());
        var boundedDirect = planner.solve(PAIRS, Route.DIRECT, automatic.totalWork());
        assertEquals("BUDGET_INCONCLUSIVE", boundedDirect.status());
        assertTrue(boundedDirect.solution().isEmpty());
        for (Route route : Route.values()) {
            int required = planner.solve(PAIRS, route, 20_000).totalWork();
            for (int budget = 0; budget <= required; budget++) {
                Result result = planner.solve(PAIRS, route, budget);
                assertTrue(result.totalWork() <= budget);
                assertEquals(budget == required, result.solution().isPresent(), route + " at " + budget);
            }
        }
    }

    @Test void retainsNegativeControlsAndRejectsChangedEvidence() {
        for (Route route : Route.values()) {
            assertEquals("NONLINEAR", planner.solve(List.of("x*y=2"), route, 20_000).status());
            assertEquals("DOMAIN_UNSUPPORTED", planner.solve(List.of("sin(x)=0"), route, 20_000).status());
            assertEquals("NOT_APPLICABLE", planner.solve(List.of("0=1"), route, 20_000).status());
        }
        var real = planner.solve(PAIRS, Route.AUTO, 20_000);
        var forged = new Result(List.of("x+y=30", "x-y=1", "z+w=7", "z-w=1"),
            real.requested(), real.selected(), real.status(), real.variables(), real.blocks(), real.budget(),
            real.preparationWork(), real.executionWork(), real.compositionWork(), real.steps(), real.solution());
        assertFalse(planner.verify(forged));
        var exhausted = planner.solve(PAIRS, Route.AUTO, 0);
        assertEquals(new LinearRepresentationPlanner.Audit("NOT_SOLVED", 0, Optional.empty()), planner.audit(exhausted, 0));
        assertThrows(IllegalArgumentException.class, () -> planner.solve(List.of(), Route.AUTO, 1));
        assertThrows(IllegalArgumentException.class, () -> planner.solve(PAIRS, Route.AUTO, -1));
        assertThrows(IllegalArgumentException.class, () -> planner.solve(List.of("1e1000000000*x=1"), Route.AUTO, 20_000));
        assertThrows(IllegalArgumentException.class, () -> planner.solve(List.of("((2^64)^64)*x=1"), Route.AUTO, 20_000));
    }
}
