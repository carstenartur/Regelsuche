package de.regelsuche.symbol;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.value.ExprValueFactory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SymbolIdTest {
    private static final UUID NAMESPACE = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");

    @Test void canonicalAndIdentifierFormsRoundTripWithoutAName() {
        for (long ordinal : List.of(1L, 42L, Long.MAX_VALUE)) {
            var id = new SymbolId(NAMESPACE, ordinal);
            assertEquals(id, SymbolId.fromCanonicalText(id.canonicalText()));
            assertEquals(id, SymbolId.fromIdentifier(id.identifier()));
            assertEquals(id, new VariableExpr(id.identifier()).symbol().orElseThrow());
        }
    }

    @Test void equalityIncludesBothAllocationNamespaceAndOrdinal() {
        var id = new SymbolId(NAMESPACE, 1);
        assertEquals(id, new SymbolId(NAMESPACE, 1));
        assertEquals(id.hashCode(), new SymbolId(NAMESPACE, 1).hashCode());
        assertNotEquals(id, new SymbolId(NAMESPACE, 2));
        assertNotEquals(id, new SymbolId(new UUID(0, 1), 1));
        assertThrows(IllegalArgumentException.class, () -> new SymbolId(NAMESPACE, 0));
        assertThrows(IllegalArgumentException.class, () -> new SymbolId(NAMESPACE, -1));
        assertThrows(NullPointerException.class, () -> new SymbolId(null, 1));
    }

    @Test void malformedOrNoncanonicalIdsAreRejected() {
        String text = new SymbolId(NAMESPACE, 1).canonicalText();
        for (String invalid : List.of("", "0-0-0-0-0:1", text.toUpperCase(), text + " ",
                text.replace(":1", ":01"), text.replace(":1", ":0"), text.replace(":1", ":-1"),
                text.replace(":1", ":9223372036854775808"), "x".repeat(10000))) {
            assertThrows(IllegalArgumentException.class, () -> SymbolId.fromCanonicalText(invalid), invalid);
        }
        for (String invalid : List.of("x", "rsym_", "rsym_" + "f".repeat(32) + "_01",
                "rsym_" + "g".repeat(32) + "_1", "rsym_" + "f".repeat(32) + "_9223372036854775808")) {
            assertThrows(IllegalArgumentException.class, () -> SymbolId.fromIdentifier(invalid));
        }
        assertThrows(NullPointerException.class, () -> SymbolId.fromCanonicalText(null));
        assertThrows(NullPointerException.class, () -> SymbolId.fromIdentifier(null));
    }

    @Test void astFormattingJsonAndFactoryBoundariesRetainTheId() throws Exception {
        var id = new SymbolId(NAMESPACE, 7);
        var first = VariableExpr.scoped(id);
        var second = VariableExpr.scoped(SymbolId.fromCanonicalText(id.canonicalText()));
        assertNotSame(first, second);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, new VariableExpr("y"));
        assertEquals(first, new ExpressionParser().parseTerm(ExpressionFormatter.format(first)));
        var mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(first);
        assertEquals(1, mapper.readTree(json).size());
        assertEquals(first, mapper.readValue(json, VariableExpr.class));
        try (var a = new ExprValueFactory(); var b = new ExprValueFactory()) {
            var av = a.scopedVariable(id);
            assertSame(av, a.fromExpr(second));
            assertSame(av, a.variable(id.identifier()));
            assertEquals(id, av.symbol().orElseThrow());
            var bv = b.scopedVariable(id);
            assertEquals(av, bv);
            assertEquals(av.key(), bv.key());
            assertNotSame(av, bv);
            a.clear();
            assertNotSame(av, a.scopedVariable(id));
            assertEquals(av, a.scopedVariable(id));
        }
    }
}
