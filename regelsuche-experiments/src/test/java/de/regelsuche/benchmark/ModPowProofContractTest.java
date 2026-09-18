package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Exercises the actual provider/verifier boundary, not only its domain helper. */
class ModPowProofContractTest {
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final Expr SOURCE = ModPowDagRediscoveryStudy.sourceProgram(false);
    private static final List<String> DOMAIN = AssumptionSignature.ofExpressions(
        ModPowDagRediscoveryStudy.DOMAIN_ASSUMPTIONS).normalizedAssumptions();

    @Test void generatedMovesRetainTheirConditionalProofPremises() throws Exception {
        var provider = provider();
        var batch = provider.candidates(MoveState.root(CODEC.encodeExpression(SOURCE)),
            new MoveContext("unused-goal", DOMAIN, MoveContext.Phase.FROZEN_EVALUATION));
        assertEquals(2, batch.moves().size());
        for (var move : batch.moves()) {
            assertEquals(DOMAIN, move.assumptions(), "conditional law must not be exported as unconditional");
            assertEquals(DOMAIN, move.transformation().assumptions());
            assertTrue(verifier().verify(state(), move, context(DOMAIN)).accepted());
        }
    }

    @Test void strippedPremisesCannotReceiveAProofReceipt() throws Exception {
        var original = move();
        var step = original.transformation();
        var stripped = new Transformation(step.rule(), step.transformedExpression(), step.kind(),
            step.mayIncreaseComplexity(), step.estimatedCostDelta(),
            step.equivalencePreservingByConstruction(), step.applicationKey(), List.of(),
            step.packId(), step.license());
        assertFalse(verifier().verify(state(), SearchMove.from(stripped, provider().descriptor(), 1),
            context(DOMAIN)).accepted(), "context assumptions do not excuse an unconditional retained edge");
    }

    @Test void everyRequiredDomainPremiseIsCheckedAtTheRealVerifierBoundary() throws Exception {
        var original = move();
        for (String omitted : DOMAIN) {
            var assumptions = new ArrayList<>(DOMAIN);
            assumptions.remove(omitted);
            assertFalse(verifier().verify(state(), original, context(assumptions)).accepted(),
                "missing proof premise: " + omitted);
        }
        assertFalse(verifier().verify(state(), original, context(List.of())).accepted());
    }

    @Test void retainedMetadataCannotBeForgedWhileKeepingTheSameTargetAndKey() throws Exception {
        var original = move().transformation();
        var forged = List.of(
            change(original, RewriteKind.EXPAND, true, 0, true, original.packId(), original.license()),
            change(original, original.kind(), false, 0, true, original.packId(), original.license()),
            change(original, original.kind(), true, -100, true, original.packId(), original.license()),
            change(original, original.kind(), true, 0, false, original.packId(), original.license()),
            change(original, original.kind(), true, 0, true, "forged-pack", original.license()),
            change(original, original.kind(), true, 0, true, original.packId(), "forged-license"));
        for (var step : forged) {
            assertFalse(verifier().verify(state(), SearchMove.from(step, provider().descriptor(), 1),
                context(DOMAIN)).accepted(), "forged metadata: " + step);
        }
    }

    private static Transformation change(Transformation step, RewriteKind kind, boolean grows,
            int delta, boolean equivalent, String pack, String license) {
        return new Transformation(step.rule(), step.transformedExpression(), kind, grows, delta,
            equivalent, step.applicationKey(), step.assumptions(), pack, license);
    }

    private static SearchMove move() throws Exception {
        return provider().candidates(MoveState.root(CODEC.encodeExpression(SOURCE)),
            new MoveContext("unused-goal", DOMAIN, MoveContext.Phase.FROZEN_EVALUATION)).moves().getFirst();
    }

    private static TypedMoveSearch.State state() {
        return new TypedMoveSearch.State(SOURCE, 0, 0, "", DOMAIN, Set.of(), 0);
    }

    private static TypedMoveSearch.Context context(List<String> assumptions) {
        return new TypedMoveSearch.Context(new VariableExpr("unused-goal"), assumptions,
            MoveContext.Phase.FROZEN_EVALUATION);
    }

    // Keep the research factories private; reflection is only a test-boundary adapter.
    private static TypedMoveSearch.TypedProvider provider() throws Exception {
        return (TypedMoveSearch.TypedProvider) factory("compositionProvider");
    }

    private static TypedMoveSearch.Verifier verifier() throws Exception {
        return (TypedMoveSearch.Verifier) factory("compositionVerifier");
    }

    private static Object factory(String name) throws Exception {
        Method method = ModPowDagRediscoveryStudy.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(null);
    }
}
