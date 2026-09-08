package de.regelsuche.scoring.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CostModelBoundaryTest {
    private int cost(CostModel model, String expression) {
        return model.cost(expression, new ExpressionParser().parseTerm(expression), new ExpressionScorer().score(expression));
    }

    @ParameterizedTest
    @ValueSource(strings = {"x ^ 2147483651", "x ^ 9999999999999999999999999999", "x ^ 2000000000 + y ^ 2000000000"})
    void hugePowersCannotBecomeNegativeOrCheap(String expression) {
        assertEquals(Integer.MAX_VALUE, cost(new NumericStabilityCost(), expression));
    }

    @Test
    void factorizationBonusesDoNotCreateNegativeCosts() {
        assertTrue(cost(new FactoredFormCost(), "((x + 1) ^ 2) ^ 2") >= 0);
    }

    @Test
    void symmetryBonusesDoNotCreateNegativeCosts() {
        assertTrue(cost(new SymmetryCost(), "a + b + a") >= 0);
    }

    @Test
    void ordinaryPowerCostsRemainOrdered() {
        assertEquals(2, cost(new NumericStabilityCost(), "x ^ 4"));
        assertEquals(3, cost(new NumericStabilityCost(), "x ^ 5"));
    }
}
