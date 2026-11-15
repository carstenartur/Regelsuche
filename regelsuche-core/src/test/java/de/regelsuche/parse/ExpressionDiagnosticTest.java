package de.regelsuche.parse;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import org.junit.jupiter.api.Test;

class ExpressionDiagnosticTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test void preservesOriginalUtf16OffsetsForTermsEquationsAndSystems() {
        check(InputType.TERM, "2x", 1, 2);
        check(InputType.TERM, "  2 (x)", 4, 5);
        check(InputType.TERM, "α + 💡", 4, 6);
        check(InputType.EQUATION, "  α = 2💡 + β", 7, 9);
        check(InputType.SYSTEM, "x = 1;  y = 2;\n  α = 4💡", 22, 24);
        check(InputType.EQUATION, "= 2", 0, 0);
        check(InputType.EQUATION, "x =   ", 6, 6);
        check(InputType.EQUATION, "x + 3", 5, 5);
        check(InputType.EQUATION, "x = 2 = 3", 6, 7);
        check(InputType.TERM, "a + (b", 6, 6);
        check(InputType.TERM, " 2. + x", 2, 3);
        check(InputType.TERM, " sin(x y)", 7, 8);
        check(InputType.TERM, "rsym_bogus + x", 0, 10);
        var exactDecimal = assertThrows(ExpressionParser.Diagnostic.class,
            () -> parser.parseTerm("2 + " + "9".repeat(10000)));
        assertEquals(4, exactDecimal.startInclusive());
        assertEquals(10004, exactDecimal.endExclusive());
        assertDoesNotThrow(() -> parser.parseTerm("2*x"));
        assertDoesNotThrow(() -> parser.parseTerm("f(α)"));
        var exact = assertThrows(ExpressionParser.Diagnostic.class, () -> parser.parseExactTerm("   .2"));
        assertEquals(3, exact.startInclusive());
        assertEquals(4, exact.endExclusive());
    }

    private void check(InputType type, String text, int start, int end) {
        var error = assertThrows(ExpressionParser.Diagnostic.class, () -> parser.parse(new InputRequest(type, text)));
        assertEquals(start, error.startInclusive(), text);
        assertEquals(end, error.endExclusive(), text);
    }
}
