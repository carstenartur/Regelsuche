package de.regelsuche.sdk.optimization.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearProgram;
import de.regelsuche.math.algorithms.algebra.BilinearIntegerSchedule.Profile;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BilinearProgramSdkTest {
    @Test void sdkVerifiesTheStrongerBoundAndBindsBothInputBasesAndTarget() {
        var one = ExactRational.ONE;
        var zero = ExactRational.ZERO;
        var neg = ExactRational.NEGATIVE_ONE;
        var tensor = new BilinearProgram.Tensor(2, 2, List.of(List.of(one,zero,zero,neg), List.of(zero,one,one,zero)));
        var budget = new OptimizationBudget(5_000_000, 10_000, 100, 10_000);
        var token = CancellationToken.NONE;
        var found = BilinearProgramSdk.discover(tensor, 3, budget, token).value().orElseThrow();
        var proof = BilinearProgramSdk.computeRationalRankBound(tensor, budget, token).value().orElseThrow();
        assertEquals(3, BilinearProgramSdk.verifyRationalRankBound(tensor, proof, budget, token).value().orElseThrow());
        assertTrue(BilinearProgramSdk.certifyRationalProductBounds(found.request(), found.witness().program(), proof,
            budget, token).value().orElseThrow().productOptimal());
        var swap = List.of(List.of(zero,one), List.of(one,zero));
        var identity = List.of(List.of(one,zero), List.of(zero,one));
        var change = new de.regelsuche.math.algorithms.algebra.BilinearProductLearning.InputBases(swap, identity);
        var target = new BilinearProgram.Tensor(2, 2, List.of(List.of(zero,neg,one,zero),List.of(one,zero,zero,one)));
        var moved = BilinearProgramSdk.applyInputBases(found.request(), found.witness().program(), change, target,
            budget, token).value().orElseThrow();
        assertEquals(moved.witness().cost(), BilinearProgramSdk.verifyInputBases(found.request(), found.witness().program(),
            change, moved.target(), moved.witness().program(), budget, token).value().orElseThrow());
        assertEquals("CANCELLED", BilinearProgramSdk.applyInputBases(found.request(), found.witness().program(), change,
            target, budget, () -> true).reason());
    }

    @Test void typedEntryPointsFindCheckAndRunOneSharedProduct() {
        var one = List.of(ExactRational.ONE);
        var two = List.of(ExactRational.integer(2));
        var request = new BilinearProgram.Request(new BilinearProgram.Tensor(1,1,List.of(one,two)),
            List.of(one), List.of(one), 1);
        var budget = OptimizationBudget.DEFAULT;
        var witness = BilinearProgramSdk.search(request, budget, CancellationToken.NONE).value().orElseThrow();
        assertEquals(new BilinearProgram.Cost(1,0,1,2), witness.cost());
        assertEquals(witness.cost(), BilinearProgramSdk.verify(request, witness.program(), budget,
            CancellationToken.NONE).value().orElseThrow());
        assertEquals(List.of(ExactRational.integer(4), ExactRational.integer(8)),
            BilinearProgramSdk.evaluate(request, witness.program(), two, two, budget,
                CancellationToken.NONE).value().orElseThrow());
        assertEquals(BilinearProgram.Status.INCONCLUSIVE,
            BilinearProgramSdk.search(request, budget, () -> true).status());
    }

    @Test void verifiedExistingWitnessExecutesInTheRequestedIntegerProfile() {
        var one = List.of(ExactRational.ONE);
        var tensor = new BilinearProgram.Tensor(1, 1, List.of(one));
        var budget = OptimizationBudget.DEFAULT;
        var token = CancellationToken.NONE;
        var found = BilinearProgramSdk.discover(tensor, 1, budget, token).value().orElseThrow();
        var program = found.witness().program();
        var profile = new Profile(List.of(2), List.of(3));
        var plan = BilinearProgramSdk.scheduleInteger(found.request(), program, profile, budget, token)
            .value().orElseThrow();
        var y = List.of(BigInteger.valueOf(7));
        assertEquals(List.of(BigInteger.valueOf(-21)), BilinearProgramSdk.evaluateInteger(plan,
            List.of(BigInteger.valueOf(-3)), y, budget, token).value().orElseThrow());
        assertEquals("INPUT_OUTSIDE_PROFILE", BilinearProgramSdk.evaluateInteger(plan,
            List.of(BigInteger.valueOf(-4)), y, budget, token).reason());
        var changed = new BilinearProgram(program.u(), program.v(), List.of(List.of(ExactRational.ZERO)));
        assertEquals("COEFFICIENT_MISMATCH", BilinearProgramSdk.scheduleInteger(found.request(), changed,
            profile, budget, token).reason());
        assertEquals("PROFILE_SHAPE", BilinearProgramSdk.scheduleInteger(found.request(), program,
            new Profile(List.of(2, 2), List.of(3)), budget, token).reason());
        assertEquals("CANCELLED", BilinearProgramSdk.scheduleInteger(found.request(), program,
            profile, budget, () -> true).reason());
    }

    @Test void discoversFromSourceAndReplaysBothCertificates() {
        var one = List.of(ExactRational.ONE);
        var tensor = new BilinearProgram.Tensor(1,1,List.of(one));
        var budget = OptimizationBudget.DEFAULT;
        var found = BilinearProgramSdk.discover(tensor, 1, budget, CancellationToken.NONE).value().orElseThrow();
        assertEquals(found.witness().cost(), BilinearProgramSdk.verify(found.request(), found.witness().program(),
            budget, CancellationToken.NONE).value().orElseThrow());
        assertEquals(found.lowerBound(), BilinearProgramSdk.computeRankBound(tensor, budget,
            CancellationToken.NONE).value().orElseThrow());
        assertEquals(1, BilinearProgramSdk.verifyRankBound(tensor, found.lowerBound(), budget,
            CancellationToken.NONE).value().orElseThrow());
        assertTrue(BilinearProgramSdk.certifyProductBounds(found.request(), found.witness().program(),
            found.lowerBound(), budget, CancellationToken.NONE).value().orElseThrow().productOptimal());
        assertEquals("CANCELLED", BilinearProgramSdk.discover(tensor, 1, budget, () -> true).reason());
    }
}
