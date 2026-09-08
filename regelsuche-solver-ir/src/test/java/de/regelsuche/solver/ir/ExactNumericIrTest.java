package de.regelsuche.solver.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import de.regelsuche.ast.NumberExpr;
import de.regelsuche.value.ExprValueFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactNumericIrTest {
    @Test
    void numericCoreIrRoundTripsKeepLargeIntegersAndRationalsExact() {
        var adapter = new CoreExpressionIrAdapter();
        try (var values = new ExprValueFactory()) {
            for (String literal : List.of("9007199254740993", "1/3", "-2/7", "1.0000000000000001", "9".repeat(768) + "." + "1".repeat(256))) {
                var number = NumberExpr.exact(literal);
                var restored = adapter.toCore(adapter.toIr(number));
                assertEquals(values.fromExpr(number), values.fromExpr(restored));
            }
        }
        var tiny = new NumberExpr(new de.regelsuche.scalar.ExactRational(
            java.math.BigInteger.ONE, java.math.BigInteger.TEN.pow(300)));
        assertEquals(tiny, adapter.toCore(adapter.toIr(tiny)));
        assertNotEquals(adapter.parse("9007199254740992"), adapter.parse("9007199254740993"));
    }
}
