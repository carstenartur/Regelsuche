package de.regelsuche.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExpressionFormatterPowerGroupingTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "2^(A*B)", "x^(A/B)", "x^(A*B+C)", "x^(A*(B+C))",
        "(x^A)^B", "x^(A^B)", "x^(A^(B*C))", "x^(-2)",
        "(-x)^2", "x^(2*3)"
    })
    void formattingPreservesExponentPrecedenceAndMeasuredOutput(String source) {
        var parser = new ExpressionParser();
        var expected = parser.parseTerm(source);
        var emitted = new AtomicLong();
        String rendered = ExpressionFormatter.formatMeasured(expected, emitted::addAndGet);
        assertEquals(expected, parser.parseTerm(rendered), source + " rendered as " + rendered);
        assertEquals(rendered.length(), emitted.get());
    }
}
