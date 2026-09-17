package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.ast.BinaryOperator.DIV;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.*;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class CompiledAstReplayBoundaryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CompiledAstReplayCodec codec = new CompiledAstReplayCodec();

    // A data record is intentionally not a proof. Actual execution is tested separately below.
    private static AstRewriteTransport.Step step(Expr source, Expr target, List<String> conditions) {
        return new AstRewriteTransport.Step(source, target, "r", RewriteKind.NORMALIZE, false, 0, false,
            conditions, "test", "PROJECT");
    }

    private static CompiledAstRewriteProgram.Candidate record(Expr expression) {
        return new CompiledAstRewriteProgram.Candidate("data-only", List.of("source"), List.of(step(expression, expression, List.of())));
    }

    @Test void documentAndScalarBoundsApplyInBothDirections() throws Exception {
        var small = record(new VariableExpr("x"));
        byte[] bytes = codec.encode(small);
        byte[] exact = java.util.Arrays.copyOf(bytes, CompiledAstReplayCodec.MAXIMUM_BYTES);
        java.util.Arrays.fill(exact, bytes.length, exact.length, (byte) ' ');
        assertEquals(small, codec.decode(exact));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(new byte[exact.length + 1]));
        assertThrows(NullPointerException.class, () -> codec.decode(null));
        assertThrows(NullPointerException.class, () -> codec.encode(null));
        String limit = "x".repeat(CompiledAstReplayCodec.MAXIMUM_TEXT_CHARACTERS);
        var atLimit = record(new VariableExpr(limit));
        assertEquals(atLimit, codec.decode(codec.encode(atLimit)));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(record(new VariableExpr(limit + "x"))));
        var json = (ObjectNode) JSON.readTree(bytes);
        ((ObjectNode) json.get("states").get(0)).put("name", limit + "x");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JSON.writeValueAsBytes(json)));
    }

    @Test void depth128RoundTripsButAnAdditionalFunctionLevelIsRejected() throws Exception {
        Expr expression = new VariableExpr("x");
        for (int i = 0; i < AstRewriteTransport.MAXIMUM_DEPTH; i++) expression = new FunctionExpr("f", expression);
        var candidate = record(expression);
        byte[] bytes = codec.encode(candidate);
        assertEquals(candidate, codec.decode(bytes));
        var root = (ObjectNode) JSON.readTree(bytes);
        var wrapped = JSON.createObjectNode().put("type", "function").put("name", "f");
        wrapped.putArray("arguments").add(root.get("states").get(0));
        ((ArrayNode) root.get("states")).set(0, wrapped);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JSON.writeValueAsBytes(root)));
    }

    @Test void nodeCountLimitIsCheckedBeforeBuildingAnOversizedExpression() throws Exception {
        var expression = new FunctionExpr("f", Collections.nCopies(AstRewriteTransport.MAXIMUM_NODES - 1, new VariableExpr("x")));
        var candidate = record(expression);
        var bytes = codec.encode(candidate);
        assertEquals(candidate, codec.decode(bytes));
        var root = (ObjectNode) JSON.readTree(bytes);
        ((ArrayNode) root.get("states").get(0).get("arguments")).add(JSON.createObjectNode().put("type", "variable").put("name", "x"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JSON.writeValueAsBytes(root)));
    }

    @Test void everyIntermediateStepAndConditionHasABoundedCanonicalRepresentation() throws Exception {
        Expr x = new VariableExpr("x");
        var one = step(x, x, List.of());
        var eight = new CompiledAstRewriteProgram.Candidate("eight", Collections.nCopies(8, "s"), Collections.nCopies(8, one));
        assertEquals(eight, codec.decode(codec.encode(eight)));
        var root = (ObjectNode) JSON.readTree(codec.encode(eight));
        ((ArrayNode) root.get("steps")).add(root.get("steps").get(0).deepCopy());
        ((ArrayNode) root.get("sourceIds")).add("s");
        ((ArrayNode) root.get("states")).add(root.get("states").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JSON.writeValueAsBytes(root)));
        var conditions = new ArrayList<String>();
        for (int i = 0; i < CompiledAstReplayCodec.MAXIMUM_ASSUMPTIONS; i++) conditions.add("condition-" + i);
        var valid = new CompiledAstRewriteProgram.Candidate("p", List.of("s"), List.of(step(x, x, conditions)));
        assertEquals(valid, codec.decode(codec.encode(valid)));
        conditions.add("extra");
        var tooMany = new CompiledAstRewriteProgram.Candidate("p", List.of("s"), List.of(step(x, x, conditions)));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(tooMany));
        var duplicates = (ObjectNode) JSON.readTree(codec.encode(valid));
        var array = (ArrayNode) duplicates.get("steps").get(0).get("assumptions");
        array.removeAll(); array.add("x != 0").add("x != 0");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(JSON.writeValueAsBytes(duplicates)));
    }

    @Test void encodingOversizedDataFailsRatherThanReturningATruncatedDocument() {
        var conditions = new ArrayList<String>();
        for (int i = 0; i < 100; i++) conditions.add(i + "-" + "x".repeat(3_000));
        Expr x = new VariableExpr("x");
        var step = step(x, x, conditions);
        var large = new CompiledAstRewriteProgram.Candidate("large", Collections.nCopies(8, "s"), Collections.nCopies(8, step));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(large));
        assertEquals(record(x), codec.decode(codec.encode(record(x))));
    }

    @Test void changedPerStepConditionsCannotHideBehindAnUnchangedAggregate() throws Exception {
        var a = PatternExpr.var("A");
        var rule = new PatternRewriteRule("division", PatternExpr.op(DIV, a, a), PatternExpr.num(1)) {
            @Override public List<Assumption> assumptions(Expr source) {
                return List.of(Assumption.nonZero(ExpressionFormatter.format(((BinaryExpr) source).right())));
            }
        };
        var engine = new PreparedAstRewriteTransformationEngine(List.of(rule), 64, 128);
        var program = new CompiledLinearRewriteEngine(new RewriteProgram.Sequence(RewriteProgram.NodeMetadata.named("p"), List.of(
            new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("one"), engine),
            new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("two"), engine))), 128).compileAst();
        Expr source = new ExpressionParser().parseTerm("f(x/x,y/y)");
        var actual = program.transformMeasured(source).candidates().getFirst();
        assertEquals(List.of("x != 0"), actual.steps().get(0).assumptions());
        assertEquals(List.of("y != 0"), actual.steps().get(1).assumptions());
        byte[] bytes = codec.encode(actual);
        assertEquals(actual.target(), program.replayEncoded(source, bytes).target());
        var root = (ObjectNode) JSON.readTree(bytes);
        var first = (ObjectNode) root.get("steps").get(0);
        var second = (ObjectNode) root.get("steps").get(1);
        var saved = first.get("assumptions");
        first.set("assumptions", second.get("assumptions")); second.set("assumptions", saved);
        byte[] changed = JSON.writeValueAsBytes(root);
        assertEquals(actual.assumptions(), codec.decode(changed).assumptions());
        assertThrows(IllegalArgumentException.class, () -> program.replayEncoded(source, changed));
    }

    @Test void equalEndpointsWithDifferentHistoriesStayDifferentInTheArchive() {
        Expr x = new VariableExpr("x"), a = new VariableExpr("a"), b = new VariableExpr("b"), z = new VariableExpr("z");
        var first = new CompiledAstRewriteProgram.Candidate("p", List.of("one", "two"), List.of(step(x, a, List.of()), step(a, z, List.of())));
        var second = new CompiledAstRewriteProgram.Candidate("p", List.of("one", "two"), List.of(step(x, b, List.of()), step(b, z, List.of())));
        assertEquals(first.target(), second.target());
        assertNotEquals(codec.contentHash(first), codec.contentHash(second));
        assertEquals(List.of(x, a, z), codec.decode(codec.encode(first)).states());
        assertEquals(List.of(x, b, z), codec.decode(codec.encode(second)).states());
    }
}
