package de.regelsuche.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpressionParserNodeRangeTest {
    private static final String MISSING_NUMERIC_EVIDENCE =
        "numeric AST node lacks verified exact literal evidence";

    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void retainsExactSourceRangesForRootAndNestedNodes() {
        String source = " 0.10*x + 0002 ";
        ExactParsedTerm parsed = parser.parseExactTerm(source);
        BinaryExpr sum = (BinaryExpr) parsed.expression();
        BinaryExpr product = (BinaryExpr) sum.left();

        assertEquals(
            new ExactParsedTerm.SourceRange(1, 14),
            parsed.rootSourceRange());
        assertEquals(
            "0.10*x + 0002",
            parsed.sourceTextFor(sum).orElseThrow());
        assertEquals(
            "0.10*x",
            parsed.sourceTextFor(product).orElseThrow());
        assertEquals(
            "0.10",
            parsed.sourceTextFor(product.left()).orElseThrow());
        assertEquals(
            "x",
            parsed.sourceTextFor(product.right()).orElseThrow());
        assertEquals(
            "0002",
            parsed.sourceTextFor(sum.right()).orElseThrow());
    }

    @Test
    void groupedLeafRangePreservesParenthesesWithoutChangingLiteralRange() {
        ExactParsedTerm parsed = parser.parseExactTerm(" ((01)) ");
        NumberExpr number = (NumberExpr) parsed.expression();

        assertEquals(
            "((01))",
            parsed.sourceTextFor(number).orElseThrow());
        assertEquals(
            new ExactParsedTerm.SourceRange(1, 7),
            parsed.sourceRangeFor(number).orElseThrow());
        assertEquals(3, parsed.literals().getFirst().startInclusive());
        assertEquals(5, parsed.literals().getFirst().endExclusive());
        assertEquals("01", parsed.literals().getFirst().sourceLexeme());
    }

    @Test
    void syntheticUnaryZeroHasNoSourceRange() {
        ExactParsedTerm parsed = parser.parseExactTerm(" ((-0.25)) ");
        BinaryExpr unary = (BinaryExpr) parsed.expression();

        assertEquals(
            "((-0.25))",
            parsed.sourceTextFor(unary).orElseThrow());
        assertTrue(parsed.sourceRangeFor(unary.left()).isEmpty());
        assertEquals(
            "0.25",
            parsed.sourceTextFor(unary.right()).orElseThrow());
    }

    @Test
    void equalNodesKeepDistinctOccurrenceRangesByIdentity() {
        ExactParsedTerm parsed = parser.parseExactTerm("1 + 1");
        BinaryExpr sum = (BinaryExpr) parsed.expression();
        NumberExpr left = (NumberExpr) sum.left();
        NumberExpr right = (NumberExpr) sum.right();

        assertEquals(left, right);
        assertNotSame(left, right);
        assertEquals(
            new ExactParsedTerm.SourceRange(0, 1),
            parsed.sourceRangeFor(left).orElseThrow());
        assertEquals(
            new ExactParsedTerm.SourceRange(4, 5),
            parsed.sourceRangeFor(right).orElseThrow());
        assertTrue(parsed.sourceRangeFor(new NumberExpr(1)).isEmpty());
    }

    @Test
    void functionAndGroupedPowerRangesRetainTheirOriginalSyntax() {
        ExactParsedTerm parsed = parser.parseExactTerm(
            " f(x, (y ^ 02)) ");
        FunctionExpr function = (FunctionExpr) parsed.expression();
        BinaryExpr power = (BinaryExpr) function.arguments().get(1);

        assertEquals(
            "f(x, (y ^ 02))",
            parsed.sourceTextFor(function).orElseThrow());
        assertEquals(
            "x",
            parsed.sourceTextFor(function.arguments().getFirst())
                .orElseThrow());
        assertEquals(
            "(y ^ 02)",
            parsed.sourceTextFor(power).orElseThrow());
        assertEquals(
            "y",
            parsed.sourceTextFor(power.left()).orElseThrow());
        assertEquals(
            "02",
            parsed.sourceTextFor(power.right()).orElseThrow());
    }

    @Test
    void validatesLongLeftAssociativeTreesWithoutStackRecursion() {
        int termCount = 12_000;
        StringBuilder source = new StringBuilder(termCount * 4);
        for (int index = 0; index < termCount; index++) {
            if (index > 0) {
                source.append(" + ");
            }
            source.append('x');
        }

        ExactParsedTerm parsed = parser.parseExactTerm(source.toString());

        assertEquals(
            new ExactParsedTerm.SourceRange(0, source.length()),
            parsed.rootSourceRange());
        assertTrue(parsed.literals().isEmpty());
    }

    @Test
    void rejectsRangeEntriesForNodesOutsideTheParsedTree() {
        VariableExpr root = new VariableExpr("x");
        VariableExpr foreign = new VariableExpr("y");
        IdentityHashMap<Expr, ExactParsedTerm.SourceRange> ranges =
            new IdentityHashMap<>();
        ranges.put(root, new ExactParsedTerm.SourceRange(0, 1));
        ranges.put(foreign, new ExactParsedTerm.SourceRange(0, 1));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new ExactParsedTerm("x", root, List.of(), ranges));

        assertEquals(
            "AST source range belongs to a node outside the parsed tree",
            exception.getMessage());
    }

    @Test
    void rejectsARootRangeThatOmitsSourceSyntax() {
        VariableExpr root = new VariableExpr("x");
        IdentityHashMap<Expr, ExactParsedTerm.SourceRange> ranges =
            new IdentityHashMap<>();
        ranges.put(root, new ExactParsedTerm.SourceRange(0, 1));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new ExactParsedTerm("x+y", root, List.of(), ranges));

        assertEquals(
            "root AST source range omits source syntax",
            exception.getMessage());
    }

    @Test
    void rejectsARangeBackedNumberWithoutExactLiteralEvidence() {
        NumberExpr number = new NumberExpr(2);
        IdentityHashMap<Expr, ExactParsedTerm.SourceRange> ranges =
            new IdentityHashMap<>();
        ranges.put(number, new ExactParsedTerm.SourceRange(0, 1));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new ExactParsedTerm("2", number, List.of(), ranges));

        assertEquals(MISSING_NUMERIC_EVIDENCE, exception.getMessage());
    }

    @Test
    void rejectsARangeFreeZeroOutsideUnaryMinus() {
        NumberExpr zero = new NumberExpr(0);
        VariableExpr variable = new VariableExpr("x");
        BinaryExpr sum = new BinaryExpr(
            zero,
            BinaryOperator.ADD,
            variable);
        Map<Expr, ExactParsedTerm.SourceRange> ranges = ranges(
            sum, 0, 5,
            variable, 4, 5);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new ExactParsedTerm("0 + x", sum, List.of(), ranges));

        assertEquals(MISSING_NUMERIC_EVIDENCE, exception.getMessage());
    }

    @Test
    void rejectsAForgedBinarySubtractionAsUnaryMinus() {
        NumberExpr zero = new NumberExpr(0);
        VariableExpr variable = new VariableExpr("x");
        BinaryExpr subtraction = new BinaryExpr(
            zero,
            BinaryOperator.SUB,
            variable);
        Map<Expr, ExactParsedTerm.SourceRange> ranges = ranges(
            subtraction, 0, 5,
            variable, 4, 5);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new ExactParsedTerm(
                "0 - x",
                subtraction,
                List.of(),
                ranges));

        assertEquals(MISSING_NUMERIC_EVIDENCE, exception.getMessage());
    }

    @Test
    void rejectsCollapsedOrUnbalancedUnaryMinusSourceShapes() {
        for (String source : List.of("--x", "(-x")) {
            NumberExpr zero = new NumberExpr(0);
            VariableExpr variable = new VariableExpr("x");
            BinaryExpr unary = new BinaryExpr(
                zero,
                BinaryOperator.SUB,
                variable);
            IdentityHashMap<Expr, ExactParsedTerm.SourceRange> ranges =
                new IdentityHashMap<>();
            ranges.put(
                unary,
                new ExactParsedTerm.SourceRange(0, source.length()));
            ranges.put(
                variable,
                new ExactParsedTerm.SourceRange(
                    source.length() - 1,
                    source.length()));

            IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new ExactParsedTerm(
                    source,
                    unary,
                    List.of(),
                    ranges));

            assertEquals(MISSING_NUMERIC_EVIDENCE, exception.getMessage());
        }
    }

    @Test
    void rejectsASyntheticUnaryZeroWithAForgedSourceRange() {
        NumberExpr zero = new NumberExpr(0);
        VariableExpr variable = new VariableExpr("x");
        BinaryExpr unary = new BinaryExpr(
            zero,
            BinaryOperator.SUB,
            variable);
        IdentityHashMap<Expr, ExactParsedTerm.SourceRange> ranges =
            new IdentityHashMap<>();
        ranges.put(unary, new ExactParsedTerm.SourceRange(0, 2));
        ranges.put(zero, new ExactParsedTerm.SourceRange(0, 1));
        ranges.put(variable, new ExactParsedTerm.SourceRange(1, 2));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new ExactParsedTerm("-x", unary, List.of(), ranges));

        assertEquals(MISSING_NUMERIC_EVIDENCE, exception.getMessage());
    }

    @Test
    void rejectsBinaryChildRangesThatOverlapOrAreOutOfOrder() {
        VariableExpr left = new VariableExpr("x");
        VariableExpr right = new VariableExpr("y");
        BinaryExpr sum = new BinaryExpr(
            left,
            BinaryOperator.ADD,
            right);
        IdentityHashMap<Expr, ExactParsedTerm.SourceRange> ranges =
            new IdentityHashMap<>();
        ranges.put(sum, new ExactParsedTerm.SourceRange(0, 5));
        ranges.put(left, new ExactParsedTerm.SourceRange(4, 5));
        ranges.put(right, new ExactParsedTerm.SourceRange(0, 1));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new ExactParsedTerm("x + y", sum, List.of(), ranges));

        assertEquals(
            "binary AST source ranges overlap or are out of order",
            exception.getMessage());
    }

    @Test
    void rejectsFunctionArgumentRangesThatAreOutOfOrder() {
        VariableExpr first = new VariableExpr("x");
        VariableExpr second = new VariableExpr("y");
        FunctionExpr function = new FunctionExpr(
            "f",
            List.of(first, second));
        IdentityHashMap<Expr, ExactParsedTerm.SourceRange> ranges =
            new IdentityHashMap<>();
        ranges.put(function, new ExactParsedTerm.SourceRange(0, 6));
        ranges.put(first, new ExactParsedTerm.SourceRange(4, 5));
        ranges.put(second, new ExactParsedTerm.SourceRange(2, 3));

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new ExactParsedTerm(
                "f(x,y)",
                function,
                List.of(),
                ranges));

        assertEquals(
            "function argument source ranges overlap or are out of order",
            exception.getMessage());
    }

    @Test
    void rejectsOneNodeIdentityReusedAtTwoTreeOccurrences() {
        VariableExpr shared = new VariableExpr("x");
        BinaryExpr sum = new BinaryExpr(
            shared,
            BinaryOperator.ADD,
            shared);
        Map<Expr, ExactParsedTerm.SourceRange> ranges = ranges(
            sum, 0, 5,
            shared, 0, 1);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new ExactParsedTerm("x + x", sum, List.of(), ranges));

        assertEquals(
            "AST node identity occurs more than once in the parsed tree",
            exception.getMessage());
    }

    private static Map<Expr, ExactParsedTerm.SourceRange> ranges(
        Expr first,
        int firstStart,
        int firstEnd,
        Expr second,
        int secondStart,
        int secondEnd
    ) {
        IdentityHashMap<Expr, ExactParsedTerm.SourceRange> ranges =
            new IdentityHashMap<>();
        ranges.put(
            first,
            new ExactParsedTerm.SourceRange(firstStart, firstEnd));
        ranges.put(
            second,
            new ExactParsedTerm.SourceRange(secondStart, secondEnd));
        return ranges;
    }
}
