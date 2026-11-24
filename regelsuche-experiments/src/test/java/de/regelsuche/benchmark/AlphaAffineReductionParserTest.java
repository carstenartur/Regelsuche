package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.ast.iteration.AffineReduction;
import de.regelsuche.ast.iteration.AffineReduction.Form;
import de.regelsuche.ast.iteration.AffineReduction.Operator;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlphaAffineReductionParserTest {
    private static final String RENAMED = """
        // Rename every identifier, including the input's private coordinate.
        affine affine_case [size]->{:1<size}
        inputs A : {[privateAxis] : 0<=privateAxis<2size}
        outputs B : {[row,col] : 0<=row<=col and 2*col<=2size+row}
        let B[row,col] = reduce(+, [left,right], {: row<=left and 0<=right<=size+col-left} : A[right]);
        .
        """;

    @Test
    void importsWholeTypedSourceWithExactIntegerConstraints() {
        var parsed = AlphaAffineReductionParser.parse(RENAMED);
        assertEquals("affine_case", parsed.name());
        assertEquals(new AffineReduction(Operator.SUM, List.of(new Form(-2, 1, List.of())),
            List.of(new Form(0, 0, List.of(1)), new Form(-1, 2, List.of(-1))), 2,
            List.of(new Form(0, 0, List.of(1, 0)), new Form(0, 0, List.of(-1, 1)),
                new Form(0, 2, List.of(1, -2))), 2,
            List.of(new Form(0, 0, List.of(-1, 0, 1, 0)), new Form(0, 0, List.of(0, 0, 0, 1)),
                new Form(0, 1, List.of(0, 1, -1, -1))), 1), parsed.source());
    }

    @Test
    void supportsMaxAndEqualityWithAReusedDeclarationLocal() {
        var parsed = AlphaAffineReductionParser.parse("""
            affine fresh_name [n]->{:0<n}
            inputs vector : {[i] : 0<=i<=n}
            outputs result : {[i] : 0<=i<=n}
            let result[i] = reduce(max, [j], {:j=i} : vector[j]);
            .
            """);
        assertEquals(new AffineReduction(Operator.MAX, List.of(new Form(-1, 1, List.of())),
            List.of(new Form(0, 0, List.of(1)), new Form(0, 1, List.of(-1))), 1,
            List.of(new Form(0, 0, List.of(1)), new Form(0, 1, List.of(-1))), 1,
            List.of(new Form(0, 0, List.of(1, -1)), new Form(0, 0, List.of(-1, 1))), 0), parsed.source());
    }

    @Test
    void rejectsUnboundAndUnsupportedSyntaxBeforeAnyPlanCanUseIt() {
        for (String invalid : List.of(
                RENAMED.replace("A[right]", "other[right]"),
                RENAMED.replace("A[right]", "A[privateAxis]"),
                RENAMED.replace("A[right]", "A[col]"),
                RENAMED.replace("A[right]", "A[right+1]"),
                RENAMED.replace("A[right]", "A[right]/2"),
                RENAMED.replace("B[row,col]", "B[col,row]"),
                RENAMED.replace("[left,right]", "[right,right]"),
                RENAMED.replace("[left,right]", "[left,row]"),
                RENAMED.replace("2*col", "row*col"),
                RENAMED.replace("[privateAxis]", "[privateAxis,secondAxis]"),
                RENAMED.replace("2size+row", "2147483648size+row"),
                RENAMED.replace("2size+row", "-2147483648size-size+row"),
                RENAMED.replace("row<=left", "row<=left or 1=1"),
                RENAMED + "affine second")) {
            assertThrows(IllegalArgumentException.class, () -> AlphaAffineReductionParser.parse(invalid), invalid);
        }
    }
}
