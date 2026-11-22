package de.regelsuche.sdk.optimization.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearProgram;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BilinearProgramSdkTest {
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
}
