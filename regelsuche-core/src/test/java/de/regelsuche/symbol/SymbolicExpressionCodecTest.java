package de.regelsuche.symbol;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SymbolicExpressionCodecTest {
    private final SymbolicExpressionCodec codec = new SymbolicExpressionCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    private SymbolicExpression document() {
        var document = SymbolicExpression.parse("(x+α)*(x-α)+α^2+0.25", new SymbolScope(new UUID(0, 7)));
        return document.withDisplayName(document.sourceBindings().get("α"), "angle");
    }

    @Test void sourceBindingsLabelsAndExactValuesRoundTripTogether() {
        var before = document();
        byte[] encoded = codec.encode(before);
        var after = codec.decode(encoded);
        assertEquals(before.expression(), after.expression());
        assertNotSame(before.expression(), after.expression());
        assertEquals(before.original().source(), after.original().source());
        assertEquals(before.sourceBindings(), after.sourceBindings());
        assertEquals(before.displayNames(), after.displayNames());
        assertEquals(before.identityText(), after.identityText());
        assertEquals(before.displayText(), after.displayText());
        assertArrayEquals(encoded, codec.encode(after));
        assertEquals(before.original().literals().stream().map(l -> l.evidence().value()).toList(),
            after.original().literals().stream().map(l -> l.evidence().value()).toList());
        encoded[0] = '!';
        assertArrayEquals(codec.encode(before), codec.encode(after));
    }

    @Test void aliasesAndConstantDocumentsRemainSupported() {
        var scope = new SymbolScope(new UUID(0, 1));
        scope.alias("secondName", scope.resolve("x"));
        for (var document : java.util.List.of(SymbolicExpression.parse("x+secondName", scope),
                SymbolicExpression.parse("0.125+1", scope))) {
            assertEquals(document.expression(), codec.decode(codec.encode(document)).expression());
        }
    }

    @Test void duplicateUnknownMissingAndTrailingRootDataAreRejected() throws Exception {
        String valid = new String(codec.encode(document()), StandardCharsets.UTF_8);
        reject(valid.substring(0, valid.length() - 1) + ",\"schema\":\"regelsuche.symbolic-expression/v1\"}");
        reject(valid + " {}");
        reject("null"); reject("[]"); reject("");
        ObjectNode unknown = (ObjectNode) mapper.readTree(valid); unknown.put("proof", true); reject(unknown);
        ObjectNode missing = (ObjectNode) mapper.readTree(valid); missing.remove("source"); reject(missing);
        ObjectNode wrong = (ObjectNode) mapper.readTree(valid); wrong.put("schema", "another-version"); reject(wrong);
        ObjectNode wrongType = (ObjectNode) mapper.readTree(valid); wrongType.put("source", 7); reject(wrongType);
    }

    @Test void invalidUtf8AndOversizedInputAreRejectedBeforeDocumentConstruction() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[] {(byte) 0xc3, 0x28}));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[SymbolicExpressionCodec.MAXIMUM_BYTES + 1]));
        String nesting = "[".repeat(100) + "0" + "]".repeat(100);
        reject(nesting);
        assertThrows(NullPointerException.class, () -> codec.decode(null));
    }

    @Test void duplicateMissingUnusedAndMalformedBindingsCannotBeImported() throws Exception {
        byte[] valid = codec.encode(document());
        ObjectNode duplicate = (ObjectNode) mapper.readTree(valid);
        var rows = (ArrayNode) duplicate.get("bindings"); rows.add(rows.get(0).deepCopy()); reject(duplicate);
        ObjectNode missing = (ObjectNode) mapper.readTree(valid); ((ArrayNode) missing.get("bindings")).remove(0); reject(missing);
        ObjectNode unused = (ObjectNode) mapper.readTree(valid);
        ((ArrayNode) unused.get("bindings")).addObject().put("name", "unused").put("id", new SymbolId(new UUID(0, 3), 1).canonicalText());
        reject(unused);
        for (String id : java.util.List.of("0-0-0-0-0:1", "00000000-0000-0000-0000-000000000007:01", "broken")) {
            ObjectNode bad = (ObjectNode) mapper.readTree(valid); ((ObjectNode) bad.get("bindings").get(0)).put("id", id); reject(bad);
        }
        ObjectNode unknownField = (ObjectNode) mapper.readTree(valid);
        ((ObjectNode) unknownField.get("bindings").get(0)).put("trusted", true); reject(unknownField);
    }

    @Test void displayMappingsMustBeCompleteUniqueAndSourceBound() throws Exception {
        byte[] valid = codec.encode(document());
        ObjectNode missing = (ObjectNode) mapper.readTree(valid); ((ArrayNode) missing.get("displayNames")).remove(0); reject(missing);
        ObjectNode repeated = (ObjectNode) mapper.readTree(valid); var rows = (ArrayNode) repeated.get("displayNames");
        rows.add(rows.get(0).deepCopy()); reject(repeated);
        ObjectNode ambiguous = (ObjectNode) mapper.readTree(valid);
        ((ObjectNode) ambiguous.get("displayNames").get(0)).put("label", "same");
        ((ObjectNode) ambiguous.get("displayNames").get(1)).put("label", "same"); reject(ambiguous);
        ObjectNode foreign = (ObjectNode) mapper.readTree(valid);
        ((ObjectNode) foreign.get("displayNames").get(0)).put("id", new SymbolId(new UUID(0, 99), 1).canonicalText()); reject(foreign);
        ObjectNode reserved = (ObjectNode) mapper.readTree(valid);
        ((ObjectNode) reserved.get("displayNames").get(0)).put("label", "rsym_internal"); reject(reserved);
    }

    @Test void displayOnlyChangesDoNotAlterTheDecodedExpression() throws Exception {
        byte[] original = codec.encode(document());
        ObjectNode renamed = (ObjectNode) mapper.readTree(original);
        ((ObjectNode) renamed.get("displayNames").get(0)).put("label", "renamed");
        assertEquals(codec.decode(original).expression(), codec.decode(mapper.writeValueAsBytes(renamed)).expression());
        // IDs are data, not authorizations: relabeling them creates a different expression.
        var id = new SymbolId(new UUID(0, 7), 1);
        var other = new SymbolId(new UUID(0, 8), 1);
        var first = SymbolicExpression.fromBindings("x", Map.of("x", id), Map.of(id, "x"));
        var second = SymbolicExpression.fromBindings("x", Map.of("x", other), Map.of(other, "x"));
        assertNotEquals(codec.decode(codec.encode(first)).expression(), codec.decode(codec.encode(second)).expression());
    }

    private void reject(String text) {
        assertThrows(IllegalArgumentException.class, () -> codec.decode(text.getBytes(StandardCharsets.UTF_8)));
    }
    private void reject(ObjectNode node) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(node);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(bytes));
    }
}
