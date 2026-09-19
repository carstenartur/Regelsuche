package de.regelsuche.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.transform.PolynomialSemanticView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExactPowerGroupingTest {
    private final ExpressionParser parser = new ExpressionParser();

    @ParameterizedTest
    @ValueSource(strings = {
        "x^(A*B)", "x^(A/B)", "x^(A*(B+C))", "(x^A)^B", "x^(A^B)",
        "(-2)^2", "x^(-2)", "9007199254740993^(A*B)", "x^(0.125*A)"
    })
    void parserIssuedExactTermsRetainGroupingAndLiteralEvidence(String source) {
        var parsed = parser.parseExactTerm(source);
        String rendered = ExactExpressionFormatter.format(parsed.expression(), parsed);
        assertEquals(parsed.expression(), parser.parseExactTerm(rendered).expression(),
            source + " rendered as " + rendered);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "x^(A*B)", "x^(A/B)", "x^(A*(B+C))", "(x^A)^B", "x^(A^B)",
        "(-2)^2", "x^(-2)", "9007199254740993^(A*B)", "x^(0.125*A)"
    })
    void measuredAndUnmeasuredStructuralAtomsPreserveTheSameExactTree(String source) {
        // An uninterpreted function is one structural atom. Its argument must
        // keep its AST even when it is outside the polynomial fragment itself.
        var parsed = parser.parseExactTerm("f(" + source + ")");
        var semantic = new PolynomialSemanticView();
        var ordinary = semantic.analyze(parsed.source());
        var measured = semantic.analyze(parsed, PolynomialWorkAuthority.unbounded());
        assertTrue(ordinary.supported(), ordinary.detailCode());
        assertTrue(measured.analysis().supported(), measured.analysis().detailCode());
        assertEquals(1, measured.analysis().view().atoms().size());
        var atom = measured.analysis().view().atom(0);
        assertEquals(parsed.expression(), parser.parseExactTerm(atom.display()).expression());
        assertEquals(ordinary.view().atom(0).display(), atom.display());
        assertEquals(ordinary.view().canonicalMaterial(), measured.analysis().view().canonicalMaterial());
        assertTrue(measured.work().units("exact-parsed-view.atom-format-code-units")
            >= atom.display().length(), "Output including parentheses must remain charged");
    }

    @Test
    void exponentProductsCannotAliasMultiplicationOutsideThePower() {
        var parsed = parser.parseExactTerm("f(x^(A*B)) + f(x^A*B)");
        var semantic = new PolynomialSemanticView();
        var ordinary = semantic.analyze(parsed.source());
        var measured = semantic.analyze(parsed, PolynomialWorkAuthority.unbounded());
        assertTrue(ordinary.supported(), ordinary.detailCode());
        assertTrue(measured.analysis().supported(), measured.analysis().detailCode());
        assertEquals(2, ordinary.view().atoms().size());
        assertEquals(2, measured.analysis().view().atoms().size());
        assertEquals(ordinary.view().canonicalMaterial(), measured.analysis().view().canonicalMaterial());
    }
}
