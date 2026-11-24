package de.regelsuche.math.algorithms.algebra;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.algebra.BilinearIntegerSchedule.Profile;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Status;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Tensor;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BilinearPrimitiveDirectionTest {
    @Test void integralProductsSurviveProjectiveNormalizationsWithNonunitLeadingCoefficients() {
        // Two signed observations, 3p and -2p, where p=(-2x+3y)(4v-5w).
        var tensor = new Tensor(2, 3, List.of(row(0, -24, 30, 0, 36, -45), row(0, 16, -20, 0, -24, 30)));
        var limits = new IterationLimits(1_000_000, 30_000, 10_000);
        var profile = new Profile(List.of(9, 8), List.of(10, 5, 7));
        var result = BilinearIntegerPortfolio.select(tensor, 1, 400, 4, profile, limits, () -> false);
        assertEquals(Status.VERIFIED, result.status(), result.reason());
        var selected = result.value().orElseThrow().selected();
        assertEquals(1, selected.schedule().cost().variableProducts());
        var replay = BilinearProgramVerifier.verify(selected.request(), selected.witness().program(), limits, () -> false);
        assertEquals(Status.VERIFIED, replay.status(), replay.reason());
        var evaluation = BilinearIntegerSchedule.evaluate(selected.schedule(), integers(7, -9), integers(88, 6, -2),
            limits, () -> false);
        assertEquals(integers(-4182, 2788), evaluation.value().orElseThrow());
    }

    private static List<ExactRational> row(long... values) {
        return Arrays.stream(values).mapToObj(ExactRational::integer).toList();
    }

    private static List<BigInteger> integers(long... values) {
        return Arrays.stream(values).mapToObj(BigInteger::valueOf).toList();
    }
}
