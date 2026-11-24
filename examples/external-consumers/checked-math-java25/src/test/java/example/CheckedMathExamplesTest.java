package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks the actual programs' results; each program also independently verifies its certificate. */
class CheckedMathExamplesTest {
    @Test void countsAllAndConditionalAssignments() {
        assertEquals(List.of("condition={}, count=5", "condition={1=true}, count=1", "condition={1=false}, count=4",
                "ASCENDING star: UNAVAILABLE (TERM_LIMIT)", "MIN_DEGREE star: 1048577"),
            output(() -> FiniteConstraintCount.main(new String[0])).lines().toList());
    }

    @Test void retainsTheWinningCreditsAndStrategy() {
        assertEquals(List.of("credit(a)=2, credit(b)=0", "Max edge(a)=0"),
            output(() -> EnergyCredits.main(new String[0])).lines().toList());
    }

    @Test void verifiesThePotentialOnItsActiveDomain() {
        String result = output(() -> SosPotential.main(new String[0]));
        assertTrue(result.contains("V=x^2; minimum decrease=1 on active x>=1; verified=true"), result);
        assertTrue(result.contains("termination under supplied transition/domain contract=true"), result);
    }

    @Test void computesTheConvolutionWithThreeProducts() {
        String result = output(() -> BilinearSearch.main(new String[0]));
        assertTrue(result.contains("variableMultiplications=3"), result);
        assertTrue(result.contains("discovered rational bilinear products: [3,3]; optimal=true"), result);
        assertTrue(result.lines().toList().contains("[8, 22, 15]"), result);
    }

    @Test void preservesTheOriginalMatrixInverseDomain() {
        assertEquals(List.of("singular X: UNDEFINED", "invertible X: DEFINED, row-major entries [2, 1, 0, 3]"),
            output(() -> RationalMatrixFormula.main(new String[0])).lines().toList());
    }

    @Test void keepsBothDefinedAndUndefinedQuotientComponents() {
        String result = output(() -> DynamicQuotient.main(new String[0]));
        assertTrue(result.contains("factor [4, 1] => undefined"), result);
        assertTrue(result.contains("factor [1, 1] => [2]"), result);
        assertTrue(result.contains("factor coverage and values: VERIFIED"), result);
    }

    private static String output(Runnable program) {
        var original = System.out;
        var bytes = new ByteArrayOutputStream();
        try (var capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            program.run();
        } finally {
            System.setOut(original);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
