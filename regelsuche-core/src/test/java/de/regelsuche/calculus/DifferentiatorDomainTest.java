package de.regelsuche.calculus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DifferentiatorDomainTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final Differentiator differentiator = new Differentiator();

    @ParameterizedTest
    @CsvSource({"ln(x),1 / x", "log(x),1 / (x * ln(10))"})
    void resultCarriesPositiveDomainAlongsideTheFormula(String source, String formula) {
        var result = differentiator.differentiateWithAssumptions(parser.parseTerm(source), "x");

        assertEquals(parser.parseTerm(formula), result.formula());
        assertEquals(List.of(Assumption.positive("x")), result.assumptions());
    }

    @Test
    void nestedLogarithmsBindActualArgumentsAndTheirOwnPartialDomains() {
        var result = differentiator.differentiateWithAssumptions(parser.parseTerm("log(ln(1/y))"), "y");

        assertEquals(List.of(Assumption.nonZero("y"), Assumption.positive("1 / y"),
            Assumption.positive("ln(1 / y)")), result.assumptions());
        assertTrue(ExpressionFormatter.format(result.formula()).contains("ln(10)"));
    }

    @Test
    void quotientsRetainOriginalDenominatorsEvenWhenTheResultSimplifies() {
        var result = differentiator.differentiateWithAssumptions(parser.parseTerm("x / (1 / y)"), "x");

        assertEquals(List.of(Assumption.nonZero("y"), Assumption.nonZero("1 / y")), result.assumptions());
    }

    @Test
    void generalPowerCarriesPositiveBaseAndExponentDomains() {
        var result = differentiator.differentiateWithAssumptions(parser.parseTerm("(1/x)^ln(y)"), "x");

        assertEquals(List.of(Assumption.nonZero("x"), Assumption.positive("y"),
            Assumption.positive("1 / x")), result.assumptions());
    }

    @ParameterizedTest
    @CsvSource({"x^0.5,x > 0", "x^1.5,x > 0", "x^0,x != 0", "x^-2,x != 0"})
    void constantPowersRetainTheDocumentedSufficientDomain(String source, String guard) {
        var result = differentiator.differentiateWithAssumptions(parser.parseTerm(source), "x");

        assertEquals(List.of(guard), result.assumptions().stream().map(Assumption::expression).toList());
    }

    @Test
    void linearPowerHasNoArtificialZeroExclusion() {
        var result = differentiator.differentiateWithAssumptions(parser.parseTerm("x^1"), "x");

        assertEquals(new NumberExpr(1), result.formula());
        assertEquals(List.of(), result.assumptions());
    }

    @Test
    void unsupportedDerivativesRemainUnsupported() {
        assertThrows(UnsupportedOperationException.class,
            () -> differentiator.differentiateWithAssumptions(parser.parseTerm("abs(x)"), "x"));
    }
}
