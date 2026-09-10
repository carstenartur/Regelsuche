package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.search.moves.*;
import de.regelsuche.transform.Transformation;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrimitiveReplayMoveVerifierTest {
    @Test void actualPrimitiveAndLearnedProgramReplayWithIndependentCostAndForgeryFails() {
        var inventory = TraceStrategyTransferExample.inventory();
        var context = MoveContext.frozen("unused");
        var state = MoveState.root("(x+y)*(x-y)+y*y");
        var verifier = new PrimitiveReplayMoveVerifier(inventory);
        var providers = PrimitiveMoveProviders.complete(new EvolutionGenomeCompiler().compile(inventory).rules(), inventory.contentHash());
        var primitive = providers.stream().filter(provider -> provider.descriptor().id().endsWith("_difference-product")).findFirst().orElseThrow();
        var move = primitive.candidates(state, context).moves().getFirst();
        var checked = verifier.verify(state, move, context);
        assertTrue(checked.accepted()); assertTrue(checked.work() > move.applicationCost()); assertEquals(1, checked.receipts().size());
        var fake = SearchMove.from(new Transformation(move.ruleId(), "x+7"), primitive.descriptor(), 1);
        assertFalse(verifier.verify(state, fake, context).accepted());
        var knowledge = new TraceRewriteStrategyLearner().learn(inventory, TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        var descriptor = new MoveProvider.Descriptor("frozen", "trace", SearchMove.SourceKind.LEARNED,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, knowledge.contentHash());
        var program = new EngineMoveProvider(descriptor, new EvolutionRewriteProgramCompiler().compile(inventory, knowledge.plan().orElseThrow()).engine(), false);
        var candidates = program.candidates(state, context).moves();
        assertFalse(candidates.isEmpty());
        for (var candidate : candidates) {
            var result = verifier.verify(state, candidate, context);
            assertTrue(result.accepted(), result.reason());
            assertEquals(candidate.transformation().primitiveStepCount(), result.receipts().size());
        }
    }
}
