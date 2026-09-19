package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TypedPrimitiveReplayTest {
    @Test void directReplayRequiresDynamicPremisesFromContextOrSource() {
        var x = new PatternExpr.LiteralVariable("x");
        var rule = new PatternRewriteRule("divide-self", PatternExpr.op(BinaryOperator.DIV, x, x), PatternExpr.num(1)) {
            @Override public List<Assumption> assumptions(Expr expression) {
                return List.of(Assumption.nonZero("x"));
            }
        };
        var transport = new AstRewriteTransport(List.of(rule), 8, 32);
        var descriptor = new MoveProvider.Descriptor("primitive", "*", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "test");
        var source = new BinaryExpr(new VariableExpr("x"), BinaryOperator.DIV, new VariableExpr("x"));
        var codec = new CompiledAstReplayCodec();
        var moves = TypedMoveSearch.primitiveProvider(descriptor, transport).candidates(
            MoveState.root(codec.encodeExpression(source)), MoveContext.frozen("unused")).moves();
        assertEquals(1, moves.size());
        var move = moves.getFirst();
        assertEquals(List.of("x != 0"), move.assumptions());
        var state = new TypedMoveSearch.State(source, 0, 0, "", List.of(), Set.of(), 0);
        var context = TypedMoveSearch.Context.frozen(new NumberExpr(1));
        var verifier = TypedMoveSearch.primitiveReplay(transport);
        var rejected = verifier.verify(state, move, context);
        assertFalse(rejected.accepted(), "regeneration alone does not discharge a conditional rewrite");
        assertEquals("TYPED_PRIMITIVE_ASSUMPTIONS_MISSING", rejected.reason());
        assertEquals(1, rejected.work());
        assertTrue(rejected.receipts().isEmpty());
        assertTrue(verifier.verify(state, move, new TypedMoveSearch.Context(new NumberExpr(1),
            List.of("x≠0"), MoveContext.Phase.PRODUCTION)).accepted());
        assertTrue(verifier.verify(new TypedMoveSearch.State(source, 0, 0, "", List.of("x≠0"), Set.of(), 0),
            move, context).accepted());
    }
}
