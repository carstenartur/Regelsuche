package de.regelsuche.symbol;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.value.ExprValueFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScopedSymbolIdentityTest {
    private static final String ID = "rsym_0123456789abcdef0123456789abcdef_1";

    @Test
    void scopedVariableKeysHaveTheirOwnVersion() {
        try (var values = new ExprValueFactory()) {
            assertTrue(values.fromExpr(new VariableExpr(ID)).key().encoded()
                .startsWith("regelsuche.expr-value/v3:"));
        }
    }

    @Test
    void scopedCompoundKeysPropagateTheVersion() {
        try (var values = new ExprValueFactory()) {
            var parser = new ExpressionParser();
            for (String source : List.of(ID + "+1", ID + "*2", ID + "^2", "sin(" + ID + ")")) {
                assertTrue(values.fromExpr(parser.parseTerm(source)).key().encoded()
                    .startsWith("regelsuche.expr-value/v3:"), source);
            }
        }
    }

    @Test
    void malformedReservedIdentifiersCannotBecomeOrdinarySymbols() {
        for (String invalid : List.of("rsym_bad", ID + "0x", ID.replace("_1", "_0"),
                ID.replace("_1", "_01"), ID.replace("abcdef", "ABCDEF"))) {
            assertThrows(IllegalArgumentException.class, () -> new VariableExpr(invalid), invalid);
        }
    }

    @Test
    void legacyNamesJsonAndKeysRetainTheirExistingContract() throws Exception {
        var x = new VariableExpr("x");
        var mapper = new ObjectMapper();
        assertEquals("{\"name\":\"x\"}", mapper.writeValueAsString(x));
        assertEquals(x, mapper.readValue("{\"name\":\"x\"}", VariableExpr.class));
        assertEquals("VariableExpr[name=x]", x.toString());
        assertThrows(IllegalArgumentException.class, () -> new VariableExpr(null));
        try (var values = new ExprValueFactory()) {
            assertEquals("regelsuche.expr-value/v2:V1:x", values.fromExpr(x).key().encoded());
        }
    }
}
