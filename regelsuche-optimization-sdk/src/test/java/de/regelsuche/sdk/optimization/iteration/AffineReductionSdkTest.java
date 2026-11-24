package de.regelsuche.sdk.optimization.iteration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.ast.iteration.AffineReduction;
import de.regelsuche.ast.iteration.AffineReduction.Form;
import de.regelsuche.ast.iteration.AffineReduction.Operator;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class AffineReductionSdkTest {
    @Test
    void evaluatesCheckedPrefixMaximaThroughThePublicSdk() {
        var domain = List.of(new Form(0, 0, List.of(1)), new Form(0, 1, List.of(-1)));
        var reduction = new AffineReduction(Operator.MAX, List.of(), domain, 1, domain, 1,
            List.of(new Form(0, 0, List.of(0, 1)), new Form(0, 0, List.of(1, -1))), 0);
        var plan = AffineReductionSdk.prepare(reduction, OptimizationBudget.DEFAULT, CancellationToken.NONE).plan();
        var result = AffineReductionSdk.evaluate(plan, 2,
            List.of(BigInteger.valueOf(-7), BigInteger.valueOf(-4), BigInteger.valueOf(-9)),
            OptimizationBudget.DEFAULT, CancellationToken.NONE);
        assertEquals(List.of(BigInteger.valueOf(-7), BigInteger.valueOf(-4), BigInteger.valueOf(-4)), result.values());
    }
}
