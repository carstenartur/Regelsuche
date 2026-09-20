package de.regelsuche.evolution;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.search.moves.*;
import de.regelsuche.transform.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RegisteredExactMoveSearchTest {
    @Test void exactEvidenceAndWorkReachTheExistingFrontierWithoutInventedPrimitiveRules() {
        var evidence = VerifiedPolynomialFixture.unitEvidence("registered-exact");
        var transformation = Transformation.exactTheory(ExactTheoryEvidence.fromVerified(evidence));
        var definition = new Definition(REVISION, "exact-stage", Kind.REGISTERED_SCHEMA,
            "frozen-model/v1", "finite-polynomial/v1", Transport.PARSER_TEXT, Mathematics.EXACT, null);
        var registry = new Registry(List.of(new Registration(definition, (state, context, meter) -> new Source() {
            @Override public Optional<Transformation> next(long allowance) {
                meter.charge(Operation.MATCH, 3);
                meter.charge(transformation.executionWork());
                return Optional.of(transformation);
            }
            @Override public Status status() { return Status.EXHAUSTED; }
        })));
        var descriptor = new MoveProvider.Descriptor("exact-stage", "exact-polynomial", SearchMove.SourceKind.LEARNED,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "registered-exact/v1");
        var provider = new RegisteredIncrementalMoveProvider(descriptor, definition, registry);
        var result = new MoveSearch().search(new MoveSearch.Problem(evidence.data().sourceExpression(),
            MoveContext.frozen(evidence.data().transformedExpression()), List.of(provider), MovePriorityPolicy.INVENTORY_ORDER,
            RegisteredExactMoveSearchTest::verifyExactly, state -> 0, MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED_INCREMENTAL, new MoveSearch.Budget(0, 1, VerifiedPolynomialFixture.work(evidence), 10, 100000)));
        assertTrue(result.reached());
        assertEquals(0, result.witness().getFirst().target().primitiveDepth());
        assertTrue(result.witness().getFirst().move().primitiveExpansion().isEmpty());
        assertTrue(result.witness().getFirst().move().transformation().primitiveRuleIds().isEmpty());
        assertEquals(VerifiedPolynomialFixture.work(evidence), result.metrics().primitiveWork());
        assertTrue(result.metrics().verificationWork() > 0);
        assertTrue(result.witness().getFirst().verification().accepted());
        assertTrue(LearnedSchedulingArtifacts.resultJson(result).contains("frozen-model/v1"));
        assertTrue(LearnedSchedulingArtifacts.resultJson(result).contains("exactTheoryWorkUnits"));
    }
    private static MoveVerifier.Verification verifyExactly(MoveState source, SearchMove move, MoveContext context) {
        var proof = new ExactPolynomialPatternIdentityVerifier().verify(EvolutionGenomeCompiler.parsePattern(source.expression()),
            EvolutionGenomeCompiler.parsePattern(move.transformation().transformedExpression()));
        return new MoveVerifier.Verification(proof.proved(), (long) proof.visitedNodes() + proof.generatedTerms(),
            List.of(proof.proofHash()), proof.detailCode());
    }
}
