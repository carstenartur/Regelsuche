package de.regelsuche.sdk.optimization.modular;

import de.regelsuche.math.algorithms.modular.MontgomeryReductionPlanner.Product;
import de.regelsuche.math.algorithms.modular.MontgomerySchedule.*;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MontgomeryScheduleSdkTest {
    @Test void delegatesProofReplayAndCancellationWithTheSameTypedRequest() {
        var parameters = new Parameters(16, 3329, 62209);
        var inputs = List.of(new Input("x", Word.SHORT, new Interval(-100, 100)),
                new Input("y", Word.SHORT, new Interval(-100, 100)));
        var result = MontgomeryScheduleSdk.dotProduct(parameters, inputs, List.of(new Product(0, 1), new Product(1, 0)),
                Word.INT, Observation.CANONICAL_RESIDUE, OptimizationBudget.DEFAULT, () -> false);
        var proof = result.certificate().orElseThrow();
        assertEquals(2, proof.sourceReductions());
        assertEquals(1, proof.targetReductions());
        assertTrue(MontgomeryScheduleSdk.verify(proof.request(), proof, OptimizationBudget.DEFAULT, () -> false).valid());
        assertEquals("CANCELLED", MontgomeryScheduleSdk.certify(proof.request(), OptimizationBudget.DEFAULT, () -> true).reason());
        assertFalse(MontgomeryScheduleSdk.verify(proof.request(), proof, OptimizationBudget.DEFAULT, () -> true).valid());
    }
}
