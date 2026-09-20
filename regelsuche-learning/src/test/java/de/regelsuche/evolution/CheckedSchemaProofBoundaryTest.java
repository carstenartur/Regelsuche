package de.regelsuche.evolution;

import static de.regelsuche.ast.BinaryOperator.*;
import static de.regelsuche.transform.PatternExpr.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.transform.PatternExpr;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CheckedSchemaProofBoundaryTest {
    private static final CheckedLearnedSchemaModel.Bounds BOUNDS = CheckedLearnedSchemaModel.Bounds.defaults();

    @Test void coincidentTrainingBindingsCannotStandInForDistinctSymbolicIndeterminates() {
        PatternExpr x = var("P0"), y = var("P1"), z = var("P2");
        var source = op(ADD, op(MUL, op(ADD, x, y), op(SUB, x, y)), op(MUL, z, z));
        var work = new CheckedSchemaSupport.Work();
        var rejected = assertThrows(IllegalArgumentException.class, () -> CheckedSchemaSupport.prove(source,
            op(POW, x, num(2)), BOUNDS, work));
        assertEquals("polynomial normal forms differ", rejected.getMessage());
        assertTrue(work.units > 0, "failed exact proof work remains paid");
    }

    @Test void rationalCoefficientsAreExactAndDoNotRoundIntoAFakeIdentity() {
        PatternExpr x = var("P0");
        var thirds = op(ADD, op(MUL, num("1/3"), x), op(MUL, num("2/3"), x));
        assertDoesNotThrow(() -> CheckedSchemaSupport.prove(thirds, x, BOUNDS, new CheckedSchemaSupport.Work()));
        var decimal = op(MUL, num("0.333333333333333333333333333333"), x);
        assertThrows(IllegalArgumentException.class, () -> CheckedSchemaSupport.prove(decimal,
            op(MUL, num("1/3"), x), BOUNDS, new CheckedSchemaSupport.Work()));
    }

    @Test void omittedNonzeroPremisesAndUntrustedFunctionDomainsNeverBecomeUnconditionalEvidence() {
        PatternExpr x = var("P0");
        assertThrows(IllegalArgumentException.class, () -> CheckedSchemaSupport.prove(op(DIV, x, x), num(1),
            BOUNDS, new CheckedSchemaSupport.Work()));
        assertThrows(IllegalArgumentException.class, () -> CheckedSchemaSupport.prove(fn("sqrt", op(POW, x, num(2))), x,
            BOUNDS, new CheckedSchemaSupport.Work()));
        assertThrows(IllegalArgumentException.class, () -> CheckedSchemaSupport.prove(op(POW, x, num(-1)), op(DIV, num(1), x),
            BOUNDS, new CheckedSchemaSupport.Work()));
    }

    @Test void excessiveArithmeticIsBoundedBeforeItCanAuthorizeALoadedStatement() {
        PatternExpr x = var("P0"), y = var("P1"), z = var("P2"), w = var("P3");
        PatternExpr large = op(POW, op(ADD, op(ADD, x, y), op(ADD, z, w)), num(32));
        PatternExpr reordered = op(POW, op(ADD, op(ADD, y, x), op(ADD, w, z)), num(32));
        var work = new CheckedSchemaSupport.Work();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(IllegalArgumentException.class,
            () -> CheckedSchemaSupport.prove(large, reordered, BOUNDS, work)));
        assertTrue(work.units > 0);
        assertThrows(IllegalArgumentException.class, () -> CheckedSchemaSupport.prove(op(MUL, num("1" + "0".repeat(100)), x), x,
            BOUNDS, new CheckedSchemaSupport.Work()));
    }
}
