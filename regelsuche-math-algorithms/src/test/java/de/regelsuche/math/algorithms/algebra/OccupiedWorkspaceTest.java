package de.regelsuche.math.algorithms.algebra;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static de.regelsuche.math.algorithms.algebra.OccupiedWorkspace.*;
import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlansTest.*;
import static org.junit.jupiter.api.Assertions.*;

class OccupiedWorkspaceTest {
    @Test void occupiedRectangularScratchRestoredForEveryColumn() {
        var first = matrix(new long[]{1, 2, -1}, new long[]{-1, 0, 3});
        var second = matrix(new long[]{4, 5});
        var schedule = composition(first, second);
        var checked = verify(first, second, schedule, LIMITS, () -> false).requireValue();
        assertEquals(2, checked.borrowedCells());
        assertEquals(16, checked.scheduled().multiplies());
        assertEquals(16, checked.scheduled().additions());
        assertEquals(8, checked.direct().multiplies());
        assertEquals(6, checked.direct().additions());
        var altered = new ArrayList<>(schedule.steps());
        altered.set(3, new Shear(Register.Z, Register.Y, second, Sign.PLUS));
        assertEquals("REGISTER_MAP_MISMATCH", verify(first, second, new Schedule(first, second, altered),
            LIMITS, () -> false).detail());
        var aliased = new Shear(Register.Z, Register.Z, second, Sign.PLUS);
        assertEquals("ALIASED_REGISTERS", verify(first, second, new Schedule(first, second, List.of(aliased)),
            LIMITS, () -> false).detail());
    }

    @Test void nonzeroOccupiedRationalColumns() {
        var a = FIXTURES.matrix(2, 1, (i,j) -> i == 0 ? rational(1,2) : rational(-2,3));
        var b = matrix(new long[]{2, 3});
        var x = matrix(new long[]{6});
        var y = matrix(new long[]{11});
        var z = matrix(new long[]{13}, new long[]{-8});
        var schedule = composition(a,b);
        var state = apply(a, b, schedule, new State(x, y, z), LIMITS, () -> false).requireValue();
        assertEquals(x, state.x());
        assertEquals(matrix(new long[]{5}), state.y());
        assertEquals(z, state.z());
        assertEquals("SOURCE_MISMATCH", verify(a, matrix(new long[]{1,3}), schedule, LIMITS, () -> false).detail());
        assertEquals("STATE_DIMENSION_MISMATCH", apply(a,b,schedule,new State(x, y, x), LIMITS, () -> false).detail());
        var incompatible = composition(b, b);
        assertEquals("COMPOSITION_DIMENSION_MISMATCH", verify(b,b,incompatible,LIMITS, () -> false).detail());
        var wrongShape = new Schedule(a,b,List.of(new Shear(Register.X,Register.Y,a,Sign.PLUS)));
        assertEquals("SHEAR_DIMENSION_MISMATCH", verify(a,b,wrongShape,LIMITS, () -> false).detail());
        assertEquals("CANCELLED", verify(a,b,schedule,LIMITS, () -> true).detail());
    }
}
