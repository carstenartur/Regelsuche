package de.regelsuche.math.algorithms.algebra;

import static de.regelsuche.math.algorithms.algebra.BilinearProgram.Status.INCONCLUSIVE;
import static de.regelsuche.math.algorithms.algebra.BilinearProgram.Status.REJECTED;
import static de.regelsuche.math.algorithms.algebra.BilinearProgram.Status.VERIFIED;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.algebra.BilinearProgram.Tensor;
import de.regelsuche.math.algorithms.algebra.BilinearRankBound.Axis;
import de.regelsuche.math.algorithms.algebra.BilinearRankBound.Certificate;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BilinearRankBoundTest {
    private static final IterationLimits LIMITS = new IterationLimits(1_000_000, 10_000, 10_000);
    private static final Tensor CONVOLUTION = new Tensor(2, 2,
        List.of(row(1, 0, 0, 0), row(0, 1, 1, 0), row(0, 0, 0, 1)));

    private static List<ExactRational> row(long... values) {
        return Arrays.stream(values).mapToObj(ExactRational::integer).toList();
    }

    private static Certificate prove(Tensor tensor) {
        var result = BilinearRankBound.compute(tensor, LIMITS, () -> false);
        assertEquals(VERIFIED, result.status(), result.reason());
        var proof = result.value().orElseThrow();
        var replay = BilinearRankBound.verify(tensor, proof, LIMITS, () -> false);
        assertEquals(VERIFIED, replay.status(), replay.reason());
        assertEquals(proof.lowerBound(), replay.value().orElseThrow());
        return proof;
    }

    @Test void getsRankThreeAcrossEachPossibleCutWithoutSupplyingForms() {
        var left = new Tensor(3, 2, List.of(row(1, 0, 0, 1, 0, 0), row(0, 0, 0, 0, 1, 0)));
        var right = new Tensor(2, 3, List.of(row(1, 0, 0, 0, 1, 0), row(0, 0, 1, 0, 0, 0)));
        for (var tensor : List.of(CONVOLUTION, left, right)) assertEquals(3, prove(tensor).lowerBound());
        assertEquals(Axis.OUTPUT, prove(CONVOLUTION).axis());
        assertEquals(Axis.LEFT, prove(left).axis());
        assertEquals(Axis.RIGHT, prove(right).axis());
    }

    @Test void handlesZeroScalarAndDependentOrSkippedRows() {
        assertEquals(0, prove(new Tensor(1, 2, List.of(row(0, 0)))).lowerBound());
        assertEquals(1, prove(new Tensor(1, 1, List.of(row(-6)))).lowerBound());
        var tensor = new Tensor(1, 4, List.of(row(0, 0, 1, 0), row(0, 0, 2, 0),
            row(0, 1, 0, 0), row(0, 0, 0, -1)));
        assertEquals(3, prove(tensor).lowerBound());
    }

    @Test void signedMinorCanBeReplayedInIndependentRowOrderAndAfterReconstruction() {
        var rows = List.of(List.of(ExactRational.ZERO, ExactRational.parse("1/2")),
                           List.of(ExactRational.parse("2/3"), ExactRational.ZERO));
        var tensor = new Tensor(1, 2, rows);
        var proof = new Certificate(new Tensor(1, 2, rows), Axis.OUTPUT, List.of(0, 1), List.of(0, 1),
            ExactRational.parse("-1/3"));
        assertEquals(VERIFIED, BilinearRankBound.verify(tensor, proof, LIMITS, () -> false).status());
        assertEquals(2, prove(tensor).lowerBound());
    }

    @Test void rejectsWrongSourceEvenOutsideTheMinorAndForgedMinorEvidence() {
        var original = new Tensor(1, 2, List.of(row(1, 0), row(0, 1)));
        var changed = new Tensor(1, 2, List.of(row(1, 0), row(2, 1)));
        var one = ExactRational.ONE;
        for (var proof : List.of(
                new Certificate(changed, Axis.OUTPUT, List.of(0), List.of(0), one),
                new Certificate(original, Axis.OUTPUT, List.of(1), List.of(0), one),
                new Certificate(original, Axis.OUTPUT, List.of(2), List.of(0), one),
                new Certificate(original, Axis.OUTPUT, List.of(1), List.of(-1), one),
                new Certificate(original, Axis.OUTPUT, List.of(0, 0), List.of(0, 1), one),
                new Certificate(original, Axis.OUTPUT, List.of(0, 1), List.of(1, 1), one),
                new Certificate(original, Axis.OUTPUT, List.of(0), List.of(), one),
                new Certificate(original, Axis.OUTPUT, List.of(0), List.of(0), ExactRational.ZERO))) {
            assertEquals(REJECTED, BilinearRankBound.verify(original, proof, LIMITS, () -> false).status());
        }
    }

    @Test void allBinaryTwoByTwoByTwoTensorsAgreeWithDirectTwoByTwoMinorEnumeration() {
        for (int mask = 0; mask < 256; mask++) {
            int[][][] values = new int[2][2][2];
            long[][] outputRows = new long[2][4];
            for (int k = 0; k < 2; k++) for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) {
                values[k][i][j] = (mask >>> (4*k + 2*i + j)) & 1;
                outputRows[k][2*i+j] = values[k][i][j];
            }
            int reference = mask == 0 ? 0 : 1;
            for (int a = 0; a < 4; a++) for (int b = a + 1; b < 4; b++) {
                int u = a/2, v = a%2, x = b/2, y = b%2;
                if (values[0][u][v]*values[1][x][y] != values[1][u][v]*values[0][x][y]
                    || values[u][0][v]*values[x][1][y] != values[u][1][v]*values[x][0][y]
                    || values[u][v][0]*values[x][y][1] != values[u][v][1]*values[x][y][0])
                    reference = 2;
            }
            var tensor = new Tensor(2, 2, List.of(row(outputRows[0]), row(outputRows[1])));
            assertEquals(reference, prove(tensor).lowerBound(), "binary tensor " + mask);
        }
    }

    @Test void claimsProductOptimalityOnlyWhenBothVerifiedBoundsCoincide() {
        var tensor = new Tensor(1, 1, List.of(row(1)));
        var forms = List.of(row(1));
        var request = new BilinearProgram.Request(tensor, forms, forms, 2);
        var proof = prove(tensor);
        var tight = new BilinearProgram(forms, forms, List.of(row(1)));
        var redundant = new BilinearProgram(List.of(row(1), row(1)), List.of(row(1), row(1)), List.of(row(0, 1)));
        var interval = BilinearRankBound.certify(request, tight, proof, LIMITS, () -> false).value().orElseThrow();
        assertEquals(new BilinearRankBound.Interval(1, 1), interval);
        assertTrue(interval.productOptimal());
        var wide = BilinearRankBound.certify(request, redundant, proof, LIMITS, () -> false).value().orElseThrow();
        assertEquals(new BilinearRankBound.Interval(1, 2), wide);
        assertFalse(wide.productOptimal());
        var wrongProgram = new BilinearProgram(forms, forms, List.of(row(0)));
        assertEquals(REJECTED, BilinearRankBound.certify(request, wrongProgram, proof, LIMITS, () -> false).status());
        var wrongMinor = new Certificate(tensor, Axis.OUTPUT, List.of(0), List.of(0), ExactRational.NEGATIVE_ONE);
        assertEquals(REJECTED, BilinearRankBound.certify(request, tight, wrongMinor, LIMITS, () -> false).status());
        assertEquals(INCONCLUSIVE, BilinearRankBound.certify(request, tight, proof,
            LIMITS.withMaximumWork(4), () -> false).status());
    }

    @Test void limitedComputationsAndReplayReturnNoBound() {
        var proof = prove(CONVOLUTION);
        for (var limits : List.of(LIMITS.withMaximumWork(2), new IterationLimits(1_000, 3, 10_000))) {
            assertEquals(INCONCLUSIVE, BilinearRankBound.compute(CONVOLUTION, limits, () -> false).status());
            assertEquals(INCONCLUSIVE, BilinearRankBound.verify(CONVOLUTION, proof, limits, () -> false).status());
        }
        assertEquals("CANCELLED", BilinearRankBound.compute(CONVOLUTION, LIMITS, () -> true).reason());
        assertEquals("CANCELLED", BilinearRankBound.verify(CONVOLUTION, proof, LIMITS, () -> true).reason());
    }
}
