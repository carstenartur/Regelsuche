package de.regelsuche.evolution;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.search.moves.MoveVerifier;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.util.ArrayList;
import java.util.List;

/** Replay alone binds a move to a rule; this additional check establishes each polynomial identity. */
final class StructuralStepVerifier {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private StructuralStepVerifier() { }

    static TypedMoveSearch.Verifier checked(TypedMoveSearch.Verifier delegate) {
        return (source, move, context) -> {
            StructuralCheckedDomain.requireUnobserved();
            var replay = delegate.verify(source, move, context);
            if (!replay.accepted()) return replay;
            var work = new CheckedSchemaSupport.Work();
            try {
                StructuralCheckedDomain.inspect(source.expression(), work);
                String encodedTarget = move.transformation().transformedExpression();
                work.add(encodedTarget.length());
                var target = CODEC.decodeExpression(encodedTarget);
                StructuralCheckedDomain.inspect(target, work);
                String before = ExpressionFormatter.format(source.expression());
                String after = ExpressionFormatter.format(target);
                work.add((long) before.length() + after.length());
                ExactPolynomialAnalysis.withProductWork(work::add).requireEquivalent(before, after);
                var receipts = new ArrayList<>(replay.receipts());
                receipts.add("exact-polynomial-step/v1");
                return new MoveVerifier.Verification(true, Math.addExact(replay.work(), work.units), receipts,
                    "REPLAY_AND_EXACT_POLYNOMIAL_STEP_VERIFIED");
            } catch (IllegalArgumentException unsupportedOrUnequal) {
                return new MoveVerifier.Verification(false, Math.addExact(replay.work(), Math.max(1, work.units)),
                    List.of(), "STRUCTURAL_STEP_NOT_EXACT_OR_OUTSIDE_DOMAIN");
            }
        };
    }
}
