package de.regelsuche.sdk.optimization.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearProgram;
import de.regelsuche.math.algorithms.algebra.ComposedBilinearSearch;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;
import org.junit.jupiter.api.Test;

import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.Status.*;
import static de.regelsuche.scalar.ExactRational.*;
import static org.junit.jupiter.api.Assertions.*;

class ComposedBilinearSdkTest {
    private static final OptimizationBudget BUDGET = new OptimizationBudget(5_000_000, 250_000, 13, 10_000);

    @Test void discoversChecksPreparesAndExecutesWithTheSameSdkContract() {
        var tensor = new BilinearProgram.Tensor(2, 2, List.of(List.of(ONE, integer(2), integer(3), integer(6))));
        var found = ComposedBilinearSdk.discover(tensor, 1, 2, ComposedBilinearSearch.Policy.residualOnly(),
            BUDGET, CancellationToken.NONE);
        assertEquals(VERIFIED, found.status(), found.detail());
        var discovery = found.requireValue();
        var plan = discovery.prepared().plan();
        assertEquals(VERIFIED, ComposedBilinearSdk.verify(discovery.request(), 2, plan, BUDGET,
            CancellationToken.NONE).status());
        var prepared = ComposedBilinearSdk.prepare(discovery.request(), 2, plan, BUDGET, CancellationToken.NONE).requireValue();
        assertEquals(List.of(List.of(integer(98)), List.of(integer(-12))), ComposedBilinearSdk.apply(prepared,
            List.of(List.of(ONE, integer(2)), List.of(integer(-1), ONE)),
            List.of(List.of(integer(4), integer(5)), List.of(integer(2), integer(-4))),
            BUDGET, CancellationToken.NONE).requireValue());
        assertEquals(INVALID, ComposedBilinearSdk.apply(prepared, List.of(List.of(ONE, ONE)),
            List.of(List.of(ONE, ONE)), BUDGET, CancellationToken.NONE).status());
    }

    @Test void forwardsTheSingleGlobalProposalLimitAndChargesSeedVerification() {
        var tensor = new BilinearProgram.Tensor(1, 1, List.of(List.of(integer(6))));
        var program = new BilinearProgram(List.of(List.of(integer(2))), List.of(List.of(integer(3))), List.of(List.of(ONE)));
        var request = new BilinearProgram.Request(tensor, program.u(), program.v(), 1);
        var policy = ComposedBilinearSearch.Policy.residualOnly();
        var enough = new OptimizationBudget(5_000_000, 250_000, 12, 10_000);
        var found = ComposedBilinearSdk.search(request, program, 1, policy, enough, CancellationToken.NONE);
        assertEquals(VERIFIED, found.status(), found.detail());
        assertTrue(found.requireValue().accounting().suppliedSeed());
        assertTrue(found.requireValue().accounting().acquisitionWork() > 0);
        var tight = new OptimizationBudget(5_000_000, 250_000, 11, 10_000);
        var stopped = ComposedBilinearSdk.search(request, program, 1, policy, tight, CancellationToken.NONE);
        assertEquals(STOPPED, stopped.status());
        assertEquals("CANDIDATE_LIMIT", stopped.detail());
        assertEquals("CANCELLED", ComposedBilinearSdk.search(request, program, 1, policy, enough, () -> true).detail());
        assertEquals(List.of(List.of(integer(-60))), ComposedBilinearSdk.apply(found.requireValue().prepared(),
            List.of(List.of(integer(-2))), List.of(List.of(integer(5))), enough, CancellationToken.NONE).requireValue());
    }
}
