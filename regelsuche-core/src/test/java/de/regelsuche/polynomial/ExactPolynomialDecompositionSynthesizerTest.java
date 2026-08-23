package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactPolynomialDecompositionSynthesizerTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final PolynomialSemanticView semanticView =
        new PolynomialSemanticView();

    @Test
    void synthesizesSophieGermainWithoutANamedIdentityOrSubstitution() {
        PolynomialSemanticView.View source = view("x^4 + 4*y^4");
        ExactPolynomialDecompositionSynthesizer.Result result =
            new ExactPolynomialDecompositionSynthesizer().synthesize(source);

        assertTrue(result.synthesized(), result.detailCode());
        assertFalse(result.candidates().isEmpty());
        ExactPolynomialDecompositionSynthesizer.Candidate candidate =
            result.candidates().getFirst();
        assertEquals(source.polynomial().canonical(),
            semanticView.analyze(candidate.factoredExpression())
                .view().polynomial().canonical());
        assertEquals(List.of("2", "-2", "1"),
            candidate.certificate().factorCoefficients());
        assertEquals(List.of("2", "2", "1"),
            candidate.certificate().quotientCoefficients());
        assertEquals("EXACT_TEMPLATE_DIVISION", candidate.certificate().method());
        assertTrue(candidate.certificate().certificateHash()
            .matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void reusesTheSameTheoryProcedureForCompositeAndFunctionGenerators() {
        for (String expression : List.of(
                "(x + 1)^4 + 4*y^4",
                "sin(t)^4 + 4*z^4")) {
            PolynomialSemanticView.View source = view(expression);
            ExactPolynomialDecompositionSynthesizer.Result result =
                new ExactPolynomialDecompositionSynthesizer().synthesize(source);

            assertTrue(result.synthesized(), expression + ": " + result.detailCode());
            assertEquals(source.polynomial().canonical(),
                semanticView.analyze(result.candidates().getFirst().factoredExpression())
                    .view().polynomial().canonical());
        }
    }

    @Test
    void synthesizesAnotherHomogeneousBivariateFactorization() {
        PolynomialSemanticView.View source =
            view("x^4 + 5*x^2*y^2 + 4*y^4");
        ExactPolynomialDecompositionSynthesizer.Result result =
            new ExactPolynomialDecompositionSynthesizer().synthesize(source);

        assertTrue(result.synthesized(), result.detailCode());
        String factored = ExpressionFormatter.format(
            result.candidates().getFirst().factoredExpression());
        assertTrue(factored.contains("x ^ 2 + y ^ 2"), factored);
        assertTrue(factored.contains("x ^ 2 + 4 * y ^ 2"), factored);
        assertEquals(source.polynomial().canonical(),
            semanticView.analyze(result.candidates().getFirst().factoredExpression())
                .view().polynomial().canonical());
    }

    @Test
    void nearMissesDoNotManufactureTheSophieGermainFactors() {
        ExactPolynomialDecompositionSynthesizer.Result coefficientNearMiss =
            new ExactPolynomialDecompositionSynthesizer()
                .synthesize(view("x^4 + 3*y^4"));
        ExactPolynomialDecompositionSynthesizer.Result nonHomogeneous =
            new ExactPolynomialDecompositionSynthesizer()
                .synthesize(view("x^4 + 4*y^3"));

        assertEquals(
            ExactPolynomialDecompositionSynthesizer.Status.NO_DECOMPOSITION_FOUND,
            coefficientNearMiss.status());
        assertEquals(
            ExactPolynomialDecompositionSynthesizer.Status.UNSUPPORTED,
            nonHomogeneous.status());
        assertTrue(coefficientNearMiss.candidates().isEmpty());
        assertTrue(nonHomogeneous.candidates().isEmpty());
    }

    @Test
    void reportsTemplateBudgetExhaustionAndIsDeterministic() {
        PolynomialSemanticView.View source = view("x^4 + 4*y^4");
        ExactPolynomialDecompositionSynthesizer.Result exhausted =
            new ExactPolynomialDecompositionSynthesizer(
                new ExactPolynomialDecompositionSynthesizer.Budget(8, 4, 8, 1, 8))
                .synthesize(source);
        ExactPolynomialDecompositionSynthesizer synthesizer =
            new ExactPolynomialDecompositionSynthesizer();
        ExactPolynomialDecompositionSynthesizer.Result first =
            synthesizer.synthesize(source);
        ExactPolynomialDecompositionSynthesizer.Result second =
            synthesizer.synthesize(source);

        assertEquals(
            ExactPolynomialDecompositionSynthesizer.Status.BUDGET_EXCEEDED,
            exhausted.status());
        assertEquals("MAX_ENUMERATED_TEMPLATES_EXCEEDED", exhausted.detailCode());
        assertEquals(
            first.candidates().stream()
                .map(candidate -> candidate.certificate().certificateHash())
                .toList(),
            second.candidates().stream()
                .map(candidate -> candidate.certificate().certificateHash())
                .toList());
        assertEquals(first.work(), second.work());
    }

    private PolynomialSemanticView.View view(String expression) {
        Expr parsed = parser.parse(new InputRequest(InputType.TERM, expression))
            .terms().getFirst();
        PolynomialSemanticView.Result result = semanticView.analyze(parsed);
        assertTrue(result.supported(), result.detailCode());
        return result.view();
    }
}
