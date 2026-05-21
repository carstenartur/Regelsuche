package de.regelsuche.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MathDomainDemosTest {

    private final MathDomainDemos demos = new MathDomainDemos();

    @Test
    void allFourDemosProduceExpectedResults() {
        // x + 3 = 7 -> x = 4
        assertEquals("x = 4", demos.linearEquation().resultExpression());
        // d/dx x^3 -> 3*x^2
        assertEquals("3 * x ^ 2", demos.derivativePowerRule().resultExpression());
        // -2*x < 4 -> x > -2
        assertEquals("x > -2", demos.inequalitySignFlip().resultExpression());
        // 1 - sin(x)^2 -> cos(x)^2
        assertEquals("cos(x) ^ 2", demos.trigIdentity().resultExpression());
    }
}
