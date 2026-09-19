package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NormalizedNodePowerGroupingTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "x^(2*3)", "x^(y*z)", "(x*y)^2", "(x^2)^3", "x^(y^z)",
        "(-2)^2", "(-x)^2", "x^(-2)", "(x+1)^2", "x^(y+1)",
        "(x*y)^(-2)", "((x+1)^2)^3"
    })
    void canonicalPowerTextPreservesTheNormalizedTreeWhenParsedAgain(String source) {
        var normalizer = new AstNormalizer();
        var expected = normalizer.normalize(source);
        String rendered = expected.canonicalString();
        assertEquals(expected, normalizer.normalize(rendered), source + " rendered as " + rendered);
    }
}
