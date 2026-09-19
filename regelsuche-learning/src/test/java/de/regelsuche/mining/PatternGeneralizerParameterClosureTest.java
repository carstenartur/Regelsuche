package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.scoring.ExpressionScore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PatternGeneralizerParameterClosureTest {
    @Test
    void allExpressionSlotsLeaveNoRoomForTheSecondNumericParameter() {
        assertTrue(new PatternGeneralizer().generalize(examples(25, false, false)).isEmpty(),
            "Namespace refusal must not leak raw numeric placeholders into a candidate");
    }

    @Test
    void theLastAvailableSlotStillSupportsACompleteProductSchema() {
        var pattern = new PatternGeneralizer().generalize(examples(24, false, false)).orElseThrow();
        assertEquals(24, pattern.expressionPlaceholderValues().size());
        assertTrue(pattern.parameterRelations().contains("N3 = Z"));
        assertFalse(pattern.leftPattern().matches(".*\\bN\\d+\\b.*"));
        assertFalse(pattern.rightPattern().matches(".*\\bN\\d+\\b.*"));
        assertTrue(new DynamicOperatorCompiler().compile("capacity-product", "test",
            pattern.leftPattern(), pattern.rightPattern()).isSuccess());
    }

    @Test
    void anUnmodeledNumericRelationCannotBeHiddenByExpressionAbstraction() {
        assertTrue(new PatternGeneralizer().generalize(examples(1, true, false)).isEmpty());
    }

    @Test
    void oneNumericParameterStillCoexistsWithAllExpressionSlots() {
        var pattern = new PatternGeneralizer().generalize(examples(25, false, true)).orElseThrow();
        assertEquals(25, pattern.expressionPlaceholderValues().size());
        assertTrue(pattern.leftPattern().startsWith("f(A,"));
        assertTrue(new DynamicOperatorCompiler().compile("capacity-single", "test",
            pattern.leftPattern(), pattern.rightPattern()).isSuccess());
    }

    private static List<SuccessfulTransformationPath> examples(int slots, boolean broken, boolean single) {
        int[] first = {3, 4, 6};
        int[] second = {5, 7, 11};
        int[] product = {15, 28, broken ? 67 : 66};
        var paths = new ArrayList<SuccessfulTransformationPath>();
        for (int row = 0; row < first.length; row++) {
            int example = row;
            String context = IntStream.rangeClosed(1, slots)
                .mapToObj(index -> example == 0 ? "x" : example == 1 ? "x+" + index : "x^" + (index + 1))
                .collect(Collectors.joining(","));
            String left = "f(" + (single ? first[row] : product[row]) + "," + context + ")";
            String right = "g(" + first[row] + (single ? "" : "," + second[row]) + "," + context + ")";
            // Only tests schema closure; f/g are not asserted mathematically equal.
            paths.add(new SuccessfulTransformationPath("row-" + row, left, right,
                List.of(left, right), List.of("synthetic-observation"),
                new ExpressionScore(100, 0, 0, 0, 0), new ExpressionScore(90, 0, 0, 0, 0),
                false, "synthetic namespace fixture, not proof", Map.of(), List.of()));
        }
        return paths;
    }
}
