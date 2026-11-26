package de.regelsuche.math.algorithms.modular;

import static de.regelsuche.math.algorithms.modular.QuadraticRootProgram.Step.*;
import static java.math.BigInteger.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reproduces the documented deterministic ledger counts; it does not measure elapsed time. */
class QuadraticRootWorkModelTest {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 8192, 30_000);
    private static final BigInteger P61 = ONE.shiftLeft(61).subtract(ONE);
    private static final BigInteger P381 = new BigInteger("1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab", 16);

    @Test void documentedProgramsKeepExactResultsAtTheirReportedWorkLimits() throws IOException {
        var table = new StringBuilder("case\tsearchWork\tpreparationWork\texecutionPositiveNorm\texecutionNegativeNorm\treplayPositiveNorm\treplayNegativeNorm\n");
        for (var p : List.of(valueOf(43), P61, P381)) {
            var source = source(p);
            var found = QuadraticRootSearch.search(source, new SharedPowerPlan.Weights(1, 1, 1000), 64, LIMITS, () -> false);
            var certificate = found.certificate().orElseThrow();
            var preparation = QuadraticRootEvaluation.prepare(source, certificate, LIMITS, () -> false);
            var prepared = preparation.plan().orElseThrow();
            var positive = prepared.evaluate(ZERO, TWO, TWO, LIMITS, () -> false);
            var negative = prepared.evaluate(ZERO, TWO, p.subtract(TWO), LIMITS, () -> false);
            var replayPositive = QuadraticRootEvaluation.evaluate(source, certificate, ZERO, TWO, TWO, LIMITS, () -> false);
            var replayNegative = QuadraticRootEvaluation.evaluate(source, certificate, ZERO, TWO, p.subtract(TWO), LIMITS, () -> false);
            var expected = new QuadraticRootEvaluation.Pair(ONE, ONE);
            assertEquals(expected, positive.pair().orElseThrow());
            assertEquals(expected, negative.pair().orElseThrow());
            assertEquals(preparation.work() + positive.work(), replayPositive.work());
            assertEquals(preparation.work() + negative.work(), replayNegative.work());
            for (var norm : List.of(TWO, p.subtract(TWO))) {
                var execution = prepared.evaluate(ZERO, TWO, norm, LIMITS, () -> false);
                assertEquals(execution.pair(), prepared.evaluate(ZERO, TWO, norm,
                        LIMITS.withMaximumWork(execution.work()), () -> false).pair());
                assertEquals("WORK_LIMIT", prepared.evaluate(ZERO, TWO, norm,
                        LIMITS.withMaximumWork(execution.work() - 1), () -> false).reason());
            }
            table.append(p.bitLength()).append("-bit").append('\t').append(found.work()).append('\t')
                    .append(preparation.work()).append('\t').append(positive.work()).append('\t').append(negative.work())
                    .append('\t').append(replayPositive.work()).append('\t').append(replayNegative.work()).append('\n');
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/quadratic-root-work-model.tsv"), table);
    }

    private static QuadraticRootContract source(BigInteger p) {
        var half = p.add(ONE).shiftRight(1);
        var exponent = p.add(ONE).shiftRight(2);
        var prefix = List.of(constant(half), add(0, 2), multiply(4, 3), power(5, exponent), square(6));
        var program = new QuadraticRootProgram(prefix, 7, 5,
                new QuadraticRootProgram.Arm(List.of(multiply(1, 3), inverse(6), multiply(8, 9)), 6, 10, false),
                new QuadraticRootProgram.Arm(List.of(subtract(0, 2), multiply(8, 3), power(9, exponent),
                        multiply(1, 3), inverse(10), multiply(11, 12)), 10, 13, false));
        var proof = p.equals(P61) ? proof61() : PrimeEvidenceCatalog.forModulus(p).orElseThrow();
        return new QuadraticRootContract("documented-work-model", p, proof, program, QuadraticRootContract.Observation.EXACT_PAIR);
    }

    private static PrimeEvidence.Certificate proof61() {
        var factors = new java.util.ArrayList<PrimeEvidence.Factor>();
        for (int[] pair : new int[][]{{2, 1}, {3, 2}, {5, 2}, {7, 1}, {11, 1}, {13, 1}, {31, 1},
                {41, 1}, {61, 1}, {151, 1}, {331, 1}, {1321, 1}})
            factors.add(new PrimeEvidence.Factor(new PrimeEvidence.SmallPrime(valueOf(pair[0])), pair[1]));
        return new PrimeEvidence.Lucas(P61, valueOf(37), factors);
    }
}
