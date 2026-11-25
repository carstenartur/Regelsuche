package de.regelsuche.math.algorithms.algebra;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.algebra.BilinearProgram.Status;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Tensor;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BilinearProductLearningTest {
    private static final IterationLimits LIMITS = new IterationLimits(10_000_000, 100_000, 30_000);
    private static final Tensor TRAIN = new Tensor(2, 2, List.of(row(1,0,0,0), row(0,1,1,0), row(0,0,0,1)));

    @Test void inputBasisTransferPreservesWeightsAndMatchesIndependentSignedContractions() {
        var source = BilinearFormDiscovery.discover(TRAIN, 3, LIMITS, () -> false).value().orElseThrow();
        var program = source.witness().program();
        var left = List.of(row(1,2),row(0,1));
        var right = List.of(row(0,1),row(-1,0));
        var change = new BilinearProductLearning.InputBases(left, right);
        // old x=(a+2b,b), old y=(d,-c); the convolution has these three target matrices.
        var target = new Tensor(2, 2, List.of(row(0,1,0,2),row(-1,0,-2,1),row(0,0,-1,0)));
        var applied = BilinearProductLearning.applyInputBases(source.request(), program, change, target, LIMITS, () -> false);
        var transferred = applied.value().orElseThrow();
        var moved = transferred.witness().program();
        assertEquals(program.w(), moved.w());
        assertEquals(Status.VERIFIED, BilinearProgramVerifier.verifyInputBases(source.request(), program, change,
            transferred.target(), moved, LIMITS, () -> false).status());
        var x = row(3,-2);
        var y = row(4,5);
        assertEquals(row(-5,-6,8), BilinearProgramVerifier.evaluate(transferred.target(), moved, x, y, LIMITS, () -> false)
            .value().orElseThrow());
        var singular = new BilinearProductLearning.InputBases(List.of(row(1,2),row(2,4)), right);
        var failed = BilinearProductLearning.applyInputBases(source.request(), program, singular, target, LIMITS, () -> false);
        assertEquals(Status.REJECTED, failed.status());
        assertTrue(failed.work() > 0);
        var otherChange = new BilinearProductLearning.InputBases(List.of(row(1,1),row(0,1)), right);
        assertEquals(Status.REJECTED, BilinearProgramVerifier.verifyInputBases(source.request(), program, otherChange,
            transferred.target(), moved, LIMITS, () -> false).status());
        assertEquals(Status.REJECTED, BilinearProductLearning.applyInputBases(source.request(), program, change, TRAIN,
            LIMITS, () -> false).status());
        assertEquals(Status.INCONCLUSIVE, BilinearProductLearning.applyInputBases(source.request(), program, change, target,
            LIMITS.withMaximumWork(applied.work() - 1), () -> false).status());
    }

    @Test void aValidRescalingIsRejectedAsAClaimOfExactFormTransport() {
        var t = new Tensor(1, 1, List.of(row(1)));
        var units = List.of(row(1));
        var source = new BilinearProgram.Request(t, units, units, 1);
        var original = new BilinearProgram(units, units, List.of(row(1)));
        var doubled = List.of(row(2));
        var target = new BilinearProgram.Request(t, doubled, units, 1);
        var substitute = new BilinearProgram(doubled, units, List.of(List.of(ExactRational.parse("1/2"))));
        var identity = new BilinearProductLearning.InputBases(units, units);
        assertEquals(Status.VERIFIED, BilinearProgramVerifier.verify(target, substitute, LIMITS, () -> false).status());
        assertEquals("TRANSFER_OUTPUT_WEIGHTS", BilinearProgramVerifier.verifyInputBases(source, original, identity,
            target, substitute, LIMITS, () -> false).reason());
        assertEquals("TERM_LIMIT", BilinearProductLearning.applyInputBases(source, original, identity, t,
            new IterationLimits(1_000, 2, 1_000), () -> false).reason());
    }

    @Test void transportsProductsButDerivesAndChecksDifferentOutputWeights() {
        var training = BilinearProductLearning.train("train", TRAIN, 3, LIMITS, () -> false);
        var basis = training.value().orElseThrow();
        var target = new Tensor(2, 2, List.of(row(2,1,1,0), row(1,0,0,1), row(0,-1,-1,3)));
        var application = BilinearProductLearning.apply(basis, target, 3, LIMITS, () -> false);
        var transfer = application.value().orElseThrow();
        var proof = transfer.discovery();
        assertEquals("train", transfer.reusedSource().orElseThrow());
        assertEquals(3, proof.lowerBound().lowerBound());
        assertEquals(3, proof.witness().cost().variableMultiplications());
        assertEquals(Status.VERIFIED, BilinearProgramVerifier.verify(proof.request(), proof.witness().program(),
            LIMITS, () -> false).status());
        assertEquals(List.of(ExactRational.integer(38), ExactRational.integer(23), ExactRational.integer(23)),
            BilinearProgramVerifier.evaluate(proof.request(), proof.witness().program(), row(2,3), row(4,5),
                LIMITS, () -> false).value().orElseThrow());
        assertTrue(training.work() > 0 && application.work() > 0);
    }

    @Test void failedTransferFallsBackOnTheSameBudgetAndNeverClaimsReuse() {
        var basis = BilinearProductLearning.train("train", TRAIN, 3, LIMITS, () -> false).value().orElseThrow();
        var outside = new Tensor(2, 2, List.of(row(0,1,0,0)));
        var fresh = BilinearFormDiscovery.discover(outside, 3, LIMITS, () -> false);
        var applied = BilinearProductLearning.apply(basis, outside, 3, LIMITS, () -> false);
        assertTrue(applied.value().orElseThrow().reusedSource().isEmpty());
        assertEquals(1, applied.value().orElseThrow().discovery().witness().cost().variableMultiplications());
        assertTrue(applied.work() > fresh.work());
        assertEquals(Status.INCONCLUSIVE, BilinearProductLearning.apply(basis, outside, 3,
            LIMITS.withMaximumWork(fresh.work()), () -> false).status());
    }

    @Test void anOpenBoundTriggersASearchForCheaperPrograms() {
        var basis = BilinearProductLearning.train("train", TRAIN, 3, LIMITS, () -> false).value().orElseThrow();
        var complex = new Tensor(2, 2, List.of(row(1,0,0,-1), row(0,1,1,0)));
        var transfer = BilinearProductLearning.apply(basis, complex, 3, LIMITS, () -> false).value().orElseThrow();
        assertEquals("train", transfer.reusedSource().orElseThrow());
        assertEquals(3, transfer.discovery().witness().cost().variableMultiplications());
        assertEquals(2, transfer.discovery().lowerBound().lowerBound());

        var single = new Tensor(2, 2, List.of(row(1,0,0,0)));
        var simpler = BilinearProductLearning.apply(basis, single, 3, LIMITS, () -> false).value().orElseThrow();
        assertTrue(simpler.reusedSource().isEmpty());
        assertEquals(1, simpler.discovery().witness().cost().variableMultiplications());
    }

    @Test void importedFactorsConferNoAuthorityAndCancellationStillApplies() {
        var forged = new BilinearProductLearning.Basis("untrusted", 2, 2, List.of(row(1,0)), List.of(row(1,0)));
        assertTrue(BilinearProductLearning.apply(forged, TRAIN, 3, LIMITS, () -> false)
            .value().orElseThrow().reusedSource().isEmpty());
        assertEquals("CANCELLED", BilinearProductLearning.apply(forged, TRAIN, 3, LIMITS, () -> true).reason());
        assertThrows(IllegalArgumentException.class, () -> new BilinearProductLearning.Basis("x", 2, 2,
            List.of(row(1)), List.of(row(1,0))));
    }

    private static List<ExactRational> row(long... entries) {
        return Arrays.stream(entries).mapToObj(ExactRational::integer).toList();
    }
}
