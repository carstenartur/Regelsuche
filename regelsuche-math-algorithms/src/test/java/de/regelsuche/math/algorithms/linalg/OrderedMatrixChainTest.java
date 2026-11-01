package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.linalg.ExactMatrixExpression.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderedMatrixChainTest {
    private static final String RULE = "ORDERED_MATRIX_CHAIN_MIN_MULTIPLIES";

    @Test void reassociatesEitherDirectionWithoutChangingFactorOrder() {
        Matrix a = matrix("a", 12, 2, 1), b = matrix("b", 2, 12, 2), c = matrix("c", 12, 2, 3);
        Product leftHeavy = new Product(new Product(a, b), c);
        ExactMatrixExpression first = optimized(leftHeavy);
        assertEquals(new Product(a, new Product(b, c)), first);
        assertEquals(96, cost(first));
        assertEquals(576, cost(leftHeavy));
        assertEquivalent(leftHeavy, first);

        a = matrix("renamedFirst", 2, 12, 1);
        b = matrix("renamedSecond", 12, 2, 2);
        c = matrix("renamedThird", 2, 12, 3);
        Product rightHeavy = new Product(a, new Product(b, c));
        ExactMatrixExpression second = optimized(rightHeavy);
        assertEquals(new Product(new Product(a, b), c), second);
        assertEquals(96, cost(second));
        assertEquivalent(rightHeavy, second);
    }

    @Test void agreesWithAnExhaustiveIndependentParenthesizationOracle() {
        for (int[] dimensions : List.of(new int[]{8, 2, 12, 3, 10}, new int[]{2, 9, 3, 8, 1, 7},
                new int[]{4, 1, 6, 2, 5, 3}, new int[]{3, 7, 2, 6})) {
            List<ExactMatrixExpression> factors = new ArrayList<>();
            for (int i = 1; i < dimensions.length; i++) {
                factors.add(matrix("factor" + i, dimensions[i - 1], dimensions[i], i));
            }
            List<ExactMatrixExpression> alternatives = allTrees(factors, 0, factors.size());
            long minimum = alternatives.stream().mapToLong(OrderedMatrixChainTest::cost).min().orElseThrow();
            for (ExactMatrixExpression source : alternatives) {
                List<MatrixOperatorRules.Rewrite> proposals = proposals(source);
                ExactMatrixExpression best = proposals.stream().filter(p -> p.rule().equals(RULE))
                    .map(MatrixOperatorRules.Rewrite::expression).findFirst().orElse(source);
                assertEquals(minimum, cost(best), source.display());
                assertEquals(factors, factors(best));
                assertEquivalent(source, best);
                assertEquals(proposals, proposals(source), "proposal ordering must be deterministic");
            }
        }
    }

    @Test void leavesAlreadyOptimalAndEqualCostChainsAlone() {
        Matrix a = matrix("a", 2, 2, 1), b = matrix("b", 2, 2, 2), c = matrix("c", 2, 2, 3);
        assertTrue(proposals(new Product(new Product(a, b), c)).stream().noneMatch(p -> p.rule().equals(RULE)));
        assertTrue(proposals(new Product(a, new Product(b, c))).stream().noneMatch(p -> p.rule().equals(RULE)));
        assertTrue(proposals(new Product(a, b)).stream().noneMatch(p -> p.rule().equals(RULE)));
    }

    @Test void keepsSumsOpaqueAndRetainsNoncommutingOrder() {
        Matrix a = matrix("a", 12, 2, 1), b = matrix("b", 2, 12, 2), c = matrix("c", 12, 2, 3);
        Sum sum = new Sum(b, matrix("another", 2, 12, -2));
        Product source = new Product(new Product(a, sum), c);
        ExactMatrixExpression best = optimized(source);
        assertEquals(List.of(a, sum, c), factors(best));
        assertEquivalent(source, best);

        var algebra = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(1_000_000));
        Matrix shear = literal("shear", new long[][]{{1, 1}, {0, 1}});
        Matrix scale = literal("scale", new long[][]{{2, 0}, {0, 3}});
        assertFalse(algebra.equal(algebra.evaluate(new Product(shear, scale)),
            algebra.evaluate(new Product(scale, shear))));
    }

    @Test void rejectsInvalidDimensionsAndFalseInverseBeforeProposing() {
        Matrix rectangular = matrix("r", 2, 3, 1);
        Matrix square = matrix("s", 2, 2, 2);
        assertThrows(ExactMatrixAlgebra.Unsupported.class, () -> proposals(new Product(rectangular, square)));
        Inverse falseInverse = new Inverse(square, literal("false", new long[][]{{1, 0}, {0, 1}}).value());
        assertThrows(ExactMatrixAlgebra.Unsupported.class,
            () -> proposals(new Product(new Product(falseInverse, square), new Identity(2))));
    }

    @Test void retainsTheExistingWorkBudgetAndDoesNotPartiallyReturnAnUnverifiedPlan() {
        Product source = new Product(new Product(matrix("a", 12, 2, 1), matrix("b", 2, 12, 2)),
            matrix("c", 12, 2, 3));
        var work = new ExactMatrixAlgebra.Work(1_000_000);
        var algebra = new ExactMatrixAlgebra(work);
        assertTrue(MatrixOperatorRules.prepare(source, algebra, work).stream().anyMatch(p -> p.rule().equals(RULE)));
        int consumed = work.ledger().consumedWorkUnits();
        assertTrue(consumed > 0);
        var tooSmall = new ExactMatrixAlgebra.Work(consumed - 1);
        assertThrows(ExactMatrixAlgebra.Exhausted.class,
            () -> MatrixOperatorRules.prepare(source, new ExactMatrixAlgebra(tooSmall), tooSmall));
        assertTrue(tooSmall.ledger().consumedWorkUnits() <= consumed - 1);
    }

    @Test void preparationIndependentlyAdmitsTheCostedPlan() {
        List<MatrixPreparation.NamedMatrix> catalog = List.of(
            declaration(matrix("a", 6, 1, 1)), declaration(matrix("b", 1, 6, 2)),
            declaration(matrix("c", 6, 1, 3)));
        var request = new MatrixPreparation.Request("", List.of("coordinate"),
            MatrixPreparation.Profile.EXPERIMENTAL_OPERATOR_V1, 1_000_000, catalog,
            List.of("((a*b)*c)"), "", false, "((a*b)*c)", java.util.Collections.nCopies(6, "0"));
        var preparation = new MatrixPreparation();
        var analysis = preparation.analyze(request);
        assertTrue(preparation.verify(analysis));
        assertTrue(analysis.attempts().stream().anyMatch(attempt ->
            attempt.origin().equals(RULE) && attempt.outcome().accepted()));
    }

    @Test void keepsExistingDepthCapsEvenWhenTheCheaperTreeIsDeeper() {
        ExactMatrixExpression deep = matrix("a", 2, 12, 1);
        for (int i = 0; i < 31; i++) deep = new Sum(deep, matrix("offset" + i, 2, 12, 0));
        Product source = new Product(deep, new Product(matrix("b", 12, 2, 2), matrix("c", 2, 12, 3)));
        assertTrue(proposals(source).stream().noneMatch(p -> p.rule().equals(RULE)));
    }

    @Test void capsTheFactorCountWithoutChangingTheExistingProposals() {
        List<ExactMatrixExpression> factors = new ArrayList<>();
        for (int i = 0; i < 33; i++) factors.add(matrix("factor" + i, i % 2 + 1, (i + 1) % 2 + 1, i));
        assertTrue(proposals(balanced(factors, 0, factors.size())).stream().noneMatch(p -> p.rule().equals(RULE)));
    }

    private static MatrixPreparation.NamedMatrix declaration(Matrix matrix) {
        return new MatrixPreparation.NamedMatrix(matrix.name(), matrix.value().entries().stream()
            .map(row -> row.stream().map(Polynomial::toCanonicalString).toList()).toList(), List.of());
    }

    private static ExactMatrixExpression balanced(List<ExactMatrixExpression> factors, int start, int end) {
        if (end == start + 1) return factors.get(start);
        int middle = (start + end) / 2;
        return new Product(balanced(factors, start, middle), balanced(factors, middle, end));
    }

    private static List<MatrixOperatorRules.Rewrite> proposals(ExactMatrixExpression source) {
        var work = new ExactMatrixAlgebra.Work(1_000_000);
        return MatrixOperatorRules.prepare(source, new ExactMatrixAlgebra(work), work);
    }

    private static ExactMatrixExpression optimized(ExactMatrixExpression source) {
        return proposals(source).stream().filter(p -> p.rule().equals(RULE)).findFirst()
            .orElseThrow(() -> new AssertionError("missing costed ordered chain proposal")).expression();
    }

    private static void assertEquivalent(ExactMatrixExpression source, ExactMatrixExpression target) {
        var algebra = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(1_000_000));
        assertTrue(algebra.equal(algebra.evaluate(source), algebra.evaluate(target)));
        List<Polynomial> vector = new ArrayList<>();
        for (int i = 0; i < columns(source); i++) vector.add(Polynomial.variable("coordinate" + i));
        assertEquals(algebra.apply(source, vector), algebra.apply(target, vector));
    }

    private static Matrix matrix(String name, int rows, int columns, int seed) {
        long[][] values = new long[rows][columns];
        for (int r = 0; r < rows; r++) for (int c = 0; c < columns; c++) values[r][c] = (r * 3 + c + seed) % 5 - 2;
        return literal(name, values);
    }

    private static Matrix literal(String name, long[][] values) {
        List<List<Polynomial>> rows = new ArrayList<>();
        for (long[] row : values) {
            List<Polynomial> entries = new ArrayList<>();
            for (long value : row) entries.add(Polynomial.constant(Rational.of(value)));
            rows.add(entries);
        }
        return new Matrix(name, new PolynomialMatrix(rows));
    }

    private static int rows(ExactMatrixExpression value) {
        if (value instanceof Product p) return rows(p.left());
        if (value instanceof Sum s) return rows(s.left());
        return ((Matrix) value).value().rows();
    }

    private static int columns(ExactMatrixExpression value) {
        if (value instanceof Product p) return columns(p.right());
        if (value instanceof Sum s) return columns(s.left());
        return ((Matrix) value).value().columns();
    }

    private static long cost(ExactMatrixExpression expression) {
        if (!(expression instanceof Product p)) return 0;
        return cost(p.left()) + cost(p.right()) + (long) rows(p.left()) * columns(p.left()) * columns(p.right());
    }

    private static List<ExactMatrixExpression> factors(ExactMatrixExpression expression) {
        if (!(expression instanceof Product p)) return List.of(expression);
        List<ExactMatrixExpression> result = new ArrayList<>(factors(p.left()));
        result.addAll(factors(p.right()));
        return List.copyOf(result);
    }

    private static List<ExactMatrixExpression> allTrees(List<ExactMatrixExpression> factors, int start, int end) {
        if (end == start + 1) return List.of(factors.get(start));
        List<ExactMatrixExpression> result = new ArrayList<>();
        for (int split = start + 1; split < end; split++) {
            for (ExactMatrixExpression left : allTrees(factors, start, split)) {
                for (ExactMatrixExpression right : allTrees(factors, split, end)) result.add(new Product(left, right));
            }
        }
        return result;
    }
}
