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
        assertTrue(result.lines().anyMatch(line -> line.startsWith("8-bit schedule: products=4,")
            && line.endsWith("exact=true")), result);
        assertTrue(result.lines().anyMatch(line -> line.startsWith("2048-bit schedule: products=3,")
            && line.endsWith("exact=true")), result);
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

    @Test void usesTheNewBoundedApisFromTheResolvedSdk() {
        assertEquals(List.of("62-variable OR: 4611686018427387903; replay=true",
                "fixed MIN_FILL: count=3; replay=true", "rational simplification: VERIFIED; replay=true",
                "missing rational condition: REQUIRES_ASSUMPTIONS", "new denominator hole: rejected=true",
                "complex multiplication rational rank: 3; replay=true"),
            output(() -> BoundedPlanning.main(new String[0])).lines().toList());
    }

    @Test void comparesRationalFormulasAtMatrixCenters() {
        assertEquals(List.of("scalar grid: INCONCLUSIVE",
                "matrix-center equality: EQUAL_ON_SHARED_DOMAIN; replay=true",
                "same center, different functions: DISTINCT; replay=true"),
            output(() -> MatrixCenterIdentity.main(new String[0])).lines().toList());
    }

    @Test void executesSpectralRepairAndKeepsTheCheaperBaseline() {
        assertEquals(List.of("repair supports: [[0], [1], [2]]; forward rows=2; inverse rows=2",
                "repair output: [[3, 4, 3], [-8, -2, 1], [-1, -6, -3]]",
                "batch operations: repair=162, direct=21; selected=DIRECT"),
            output(() -> SpectralRepair.main(new String[0])).lines().toList());
    }

    @Test void executesTheOptimizedComposedStages() {
        assertEquals(List.of("centered product: [49/4, 25/4, 9/4, 1/4, 1/4, 9/4, 25/4, 49/4]",
                "operations per pair: direct=248, composed=54; replay=true", "searched stages: [U, V, W]"),
            output(() -> ComposedBilinearPlans.main(new String[0])).lines().toList());
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
