package de.regelsuche.search.moves;

import static de.regelsuche.ast.BinaryOperator.ADD;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TypedPrimitiveCandidateRetentionTest {
    @Test void generatedPremiseTextCannotEscapeTheRetentionLimit() {
        String symbol = "p".repeat(1600);
        // Pure, fixed conditional metadata through the explicit transport contract.
        var rule = new PatternRewriteRule("guarded-zero", PatternExpr.op(ADD,
                PatternExpr.var("A"), PatternExpr.num(0)), PatternExpr.var("A")) {
            @Override public List<Assumption> assumptions(Expr subtree) {
                return List.of(Assumption.nonZero(symbol));
            }
        };
        var descriptor = new MoveProvider.Descriptor("guarded-zero", "guarded-zero", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "fixed-conditional-v1");
        var cache = TypedPrimitiveCandidateCache.forDeterministicTransport(descriptor,
            new AstRewriteTransport(List.of(rule), 64, 128), 8, 1000);
        var expression = new BinaryExpr(new VariableExpr("x"), ADD, new NumberExpr(0));
        var state = new MoveState(new CompiledAstReplayCodec().encodeExpression(expression),
            0, 0, "", List.of(), Set.of(), 0);
        var result = cache.candidates(state, MoveContext.frozen(""));
        assertFalse(result.moves().isEmpty(), "oversized candidate must still be returned");
        assertTrue(result.moves().getFirst().transformation().assumptions().stream()
            .anyMatch(premise -> premise.contains(symbol)), "exercise actual generated condition metadata");
        assertEquals(0, cache.statistics().entries(), "generated premises belong in the retained text budget");
        assertEquals(1, cache.statistics().bypasses());
        assertEquals(0, cache.statistics().retainedCharacters());
    }
}
