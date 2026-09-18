package de.regelsuche.search.program;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.transform.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class CompiledAstReplayCodecTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExpressionParser PARSER = new ExpressionParser();
    private final CompiledAstReplayCodec codec = new CompiledAstReplayCodec();

    private static CompiledAstRewriteProgram program() {
        var a = PatternExpr.var("A");
        var rule = new PatternRewriteRule("zero", PatternExpr.op(ADD, a, PatternExpr.num(0)), a) {
            @Override public List<Assumption> assumptions(Expr input) {
                return List.of(Assumption.nonZero("x"));
            }
        };
        var source = new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("stage"),
            new PreparedAstRewriteTransformationEngine(List.of(rule), 64, 128));
        return new CompiledLinearRewriteEngine(source, 128).compileAst();
    }

    private static CompiledAstRewriteProgram.Candidate candidate(Expr target) {
        var step = new AstRewriteTransport.Step(new BinaryExpr(target, ADD, new NumberExpr(0)), target,
            "zero", RewriteKind.NORMALIZE, false, 0, true, List.of("x != 0"), "core", "PROJECT");
        return new CompiledAstRewriteProgram.Candidate("stage", List.of("stage"), List.of(step));
    }

    private ObjectNode document() throws Exception {
        return (ObjectNode) JSON.readTree(codec.encode(candidate(PARSER.parseTerm("a+(b+c)"))));
    }

    private byte[] mutate(Consumer<ObjectNode> change) throws Exception {
        var root = document(); change.accept(root); return JSON.writeValueAsBytes(root);
    }

    @Test void exactRoundTripRetainsGroupingNumbersSymbolsAndFunctionArgumentOrder() {
        var symbol = VariableExpr.scoped(new SymbolId(new UUID(0, 71), 3));
        for (Expr expression : List.of(PARSER.parseTerm("a+(b+c)"), PARSER.parseTerm("a*(b*c)"),
                NumberExpr.exact("-7/13"), NumberExpr.exact("10000000000000000.125"), symbol,
                new FunctionExpr("f", List.of(symbol, NumberExpr.exact("1/3"), PARSER.parseTerm("a+(b+c)"))),
                new FunctionExpr("empty", List.of()), new VariableExpr("label\\\"α"))) {
            var value = candidate(expression);
            byte[] bytes = codec.encode(value);
            var restored = new CompiledAstReplayCodec().decode(bytes.clone());
            assertEquals(value, restored);
            assertEquals(value.states(), restored.states());
            assertNotSame(value, restored);
            assertArrayEquals(bytes, new CompiledAstReplayCodec().encode(restored));
            assertEquals(codec.contentHash(value), codec.contentHash(restored));
        }
    }

    @Test void expressionTransportIsCanonicalSingleLineEvenWhenValuesContainNewlines() {
        Expr expression = new FunctionExpr("f\nname", List.of(new VariableExpr("x\ny")));
        String encoded = codec.encodeExpression(expression);
        assertFalse(encoded.contains("\n"), "canonical expression transport must escape embedded newlines");
        assertEquals(expression, codec.decodeExpression(encoded));
        assertEquals(encoded, codec.encodeExpression(codec.decodeExpression(encoded)));
    }

    @Test void representationAndSideConditionsContributeToContentIdentity() {
        assertNotEquals(codec.contentHash(candidate(PARSER.parseTerm("a+(b+c)"))),
            codec.contentHash(candidate(PARSER.parseTerm("(a+b)+c"))));
        assertNotEquals(codec.contentHash(candidate(NumberExpr.exact("1/3"))),
            codec.contentHash(candidate(PARSER.parseTerm("1/3"))));
        assertNotEquals(codec.contentHash(candidate(VariableExpr.scoped(new SymbolId(new UUID(0, 1), 1)))),
            codec.contentHash(candidate(VariableExpr.scoped(new SymbolId(new UUID(0, 2), 1)))));
        var original = candidate(new VariableExpr("x"));
        var step = original.steps().getFirst();
        var changed = new AstRewriteTransport.Step(step.source(), step.target(), step.rule(), step.kind(),
            step.mayIncreaseComplexity(), step.estimatedCostDelta(), step.equivalencePreservingByConstruction(),
            List.of("y != 0"), step.packId(), step.license());
        assertNotEquals(codec.contentHash(original), codec.contentHash(new CompiledAstRewriteProgram.Candidate(
            original.programId(), original.sourceIds(), List.of(changed))));
        assertTrue(codec.contentHash(original).matches("sha256:[0-9a-f]{64}"));
    }

    @Test void savedRecordReplaysUnderAFreshProgramAndReportsActualRegenerationWork(@TempDir Path directory) throws Exception {
        Expr source = PARSER.parseTerm("(a+(b+c))+0");
        var actual = program().transformMeasured(source).candidates().getFirst();
        var file = directory.resolve("trace.json");
        Files.write(file, codec.encode(actual));
        var receiving = program();
        var replay = receiving.replayEncoded(source, Files.readAllBytes(file));
        assertEquals(PARSER.parseTerm("a+(b+c)"), replay.target());
        assertEquals(receiving.replay(source, actual), replay);
        assertTrue(replay.workMetrics().totalWorkUnits() > 0);
        assertEquals(List.of("x != 0"), codec.decode(Files.readAllBytes(file)).assumptions());
    }

    @Test void dataImportDoesNotAuthorizeChangedIntermediateMetadataOrEquivalentTargets() throws Exception {
        Expr source = candidate(PARSER.parseTerm("a+(b+c)")).source();
        var unchanged = candidate(PARSER.parseTerm("a+(b+c)"));
        assertEquals(unchanged, program().transformMeasured(source).candidates().getFirst());
        assertEquals(unchanged.target(), program().replayEncoded(source, codec.encode(unchanged)).target());
        List<Consumer<ObjectNode>> changes = List.of(
            root -> root.put("program", "different"),
            root -> ((ArrayNode) root.get("sourceIds")).set(0, JSON.getNodeFactory().textNode("other-stage")),
            root -> ((ObjectNode) root.get("steps").get(0)).put("rule", "other-rule"),
            root -> ((ObjectNode) root.get("steps").get(0)).put("estimatedCostDelta", 99),
            root -> ((ObjectNode) root.get("steps").get(0)).put("equivalencePreservingByConstruction", false),
            root -> ((ObjectNode) root.get("steps").get(0)).putArray("assumptions"),
            root -> ((ObjectNode) root.get("steps").get(0)).putArray("assumptions").add("y != 0"),
            root -> ((ObjectNode) root.get("steps").get(0)).put("license", "different"),
            root -> {
                var target = (ObjectNode) root.get("states").get(1);
                var inner = target.get("right").deepCopy();
                var left = target.get("left").deepCopy();
                target.set("left", JSON.createObjectNode().put("type", "binary").put("operator", "ADD")
                    .set("left", left));
                ((ObjectNode) target.get("left")).set("right", inner.get("left"));
                target.set("right", inner.get("right"));
            });
        for (var change : changes) {
            byte[] bytes = mutate(change);
            assertNotNull(codec.decode(bytes), "well-formed data can still be unauthorized");
            assertThrows(IllegalArgumentException.class, () -> program().replayEncoded(source, bytes));
        }
        byte[] bytes = codec.encode(candidate(PARSER.parseTerm("a+(b+c)")));
        assertThrows(IllegalArgumentException.class,
            () -> program().replayEncoded(PARSER.parseTerm("((a+b)+c)+0"), bytes));
    }

    @Test void strictSchemaRejectsUnknownMissingDuplicateAndTrailingData() throws Exception {
        List<Consumer<ObjectNode>> mutations = List.of(
            root -> root.put("schema", "future"), root -> root.put("backend", "future"),
            root -> root.remove("states"), root -> root.put("unexpected", true),
            root -> ((ObjectNode) root.get("states").get(0)).put("surprise", 1),
            root -> ((ObjectNode) root.get("steps").get(0)).remove("assumptions"),
            root -> ((ObjectNode) root.get("steps").get(0)).put("trusted", true));
        for (var change : mutations) {
            byte[] bytes = mutate(change);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(bytes));
        }
        String text = new String(codec.encode(candidate(new VariableExpr("x"))), StandardCharsets.UTF_8);
        for (String invalid : List.of(text + " {}", text.replace("\"program\":\"stage\"", "\"program\":\"stage\",\"program\":\"stage\""),
                text.replace("\"type\":\"variable\"", "\"type\":\"variable\",\"type\":\"variable\""), "null", "[]", "")) {
            assertThrows(IllegalArgumentException.class, () -> codec.decode(invalid.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test void malformedUtf8AndMalformedJsonHaveDistinctDiagnostics() {
        var utf8 = assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[] {(byte) 0xff}));
        assertTrue(utf8.getMessage().contains("UTF-8"));
        var json = assertThrows(IllegalArgumentException.class,
            () -> codec.decode("{".getBytes(StandardCharsets.UTF_8)));
        assertEquals("invalid AST replay JSON", json.getMessage());
    }

    @Test void decodingNeverCoercesMetadataAndLengthsMustAgree() throws Exception {
        List<Consumer<ObjectNode>> mutations = List.of(
            root -> root.put("program", 12),
            root -> ((ObjectNode) root.get("steps").get(0)).put("mayIncreaseComplexity", "false"),
            root -> ((ObjectNode) root.get("steps").get(0)).put("estimatedCostDelta", 1.5),
            root -> ((ObjectNode) root.get("steps").get(0)).put("estimatedCostDelta", 2147483648L),
            root -> ((ArrayNode) root.get("sourceIds")).removeAll(),
            root -> ((ArrayNode) root.get("states")).remove(1),
            root -> ((ArrayNode) root.get("steps")).removeAll(),
            root -> ((ObjectNode) root.get("states").get(0)).put("operator", "INVALID"),
            root -> ((ObjectNode) root.get("states").get(0)).put("type", "arbitrary.class.Name"));
        for (var mutation : mutations) {
            byte[] bytes = mutate(mutation);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(bytes));
        }
    }

    @Test void exactNumericTagDoesNotAcceptNoncanonicalOrFloatingPointLiterals() throws Exception {
        var good = codec.encode(candidate(NumberExpr.exact("1/3")));
        assertInstanceOf(NumberExpr.class, codec.decode(good).target());
        for (String invalid : List.of("2/6", "1/0", "0/2", "-0", "01", "+1", "NaN", "0.5", "1e3")) {
            var root = (ObjectNode) JSON.readTree(good);
            ((ObjectNode) root.get("states").get(1)).put("value", invalid);
            byte[] bytes = JSON.writeValueAsBytes(root);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(bytes), invalid);
        }
    }

    @Test void symbolTagsCannotBeReplacedByLegacyNamesAndStringsAreWellFormedUnicode() throws Exception {
        var scoped = candidate(VariableExpr.scoped(new SymbolId(new UUID(0, 1), 1)));
        var root = (ObjectNode) JSON.readTree(codec.encode(scoped));
        var target = (ObjectNode) root.get("states").get(1);
        target.removeAll(); target.put("type", "variable").put("name", ((VariableExpr) scoped.target()).name());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JSON.writeValueAsBytes(root)));
        byte[] valid = codec.encode(candidate(new VariableExpr("x")));
        byte[] broken = valid.clone(); broken[0] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(broken));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(candidate(new VariableExpr("\uD800"))));
        String escaped = new String(valid, StandardCharsets.UTF_8).replace("\"name\":\"x\"", "\"name\":\"\\uD800\"");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(escaped.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void independentCallsDoNotRetainMutableMapsOrReuseAnOldSuccessfulDecode() throws Exception {
        byte[] first = codec.encode(candidate(new VariableExpr("first")));
        var decoded = codec.decode(first);
        java.util.Arrays.fill(first, (byte) 'x');
        assertEquals(new VariableExpr("first"), decoded.target());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(first));
        assertEquals(new VariableExpr("second"), codec.decode(codec.encode(candidate(new VariableExpr("second")))).target());
        assertThrows(UnsupportedOperationException.class, () -> decoded.steps().clear());
        assertThrows(UnsupportedOperationException.class, () -> decoded.steps().getFirst().assumptions().clear());
    }
}
