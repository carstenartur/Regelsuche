package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.linalg.ExactMatrixExpression.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.representation.RepresentationBridge;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatrixFactorizationRulesTest {
    @Test void extractsACommonLeftFactorWithoutReorderingRectangularProducts() {
        var a = matrix("transform", new long[][] {{1, 2, 0}, {0, 1, 3}});
        var b = matrix("first", new long[][] {{1, 0}, {2, 1}, {0, 1}});
        var c = matrix("second", new long[][] {{3, 1}, {0, 2}, {1, 1}});
        var source = new Sum(new Product(a, b), new Product(a, c));
        var result = rule(source, "COMMON_LEFT_FACTOR");
        assertEquals(new Product(a, new Sum(b, c)), result);
        replay(source, result);
    }

    @Test void extractsACommonRightFactorWithoutAssumingCommutativity() {
        var a = matrix("first", new long[][] {{1, 2, 3}, {4, 1, 0}});
        var b = matrix("second", new long[][] {{2, 0, 1}, {1, 3, 2}});
        var c = matrix("transform", new long[][] {{1}, {2}, {3}});
        var source = new Sum(new Product(a, c), new Product(b, c));
        var result = rule(source, "COMMON_RIGHT_FACTOR");
        assertEquals(new Product(new Sum(a, b), c), result);
        replay(source, result);
    }

    @Test void polynomialEqualityRatherThanANameOrObjectIdentityEstablishesTheFactor() {
        var value = new PolynomialMatrix(List.of(List.of(Polynomial.variable("coefficient"))));
        var a = new Matrix("originalName", value);
        var other = new Product(new Identity(1), new Matrix("unrelatedName", value));
        var b = matrix("b", new long[][] {{2}});
        var c = matrix("c", new long[][] {{3}});
        var source = new Sum(new Product(a, b), new Product(other, c));
        var result = rule(source, "COMMON_LEFT_FACTOR");
        assertEquals(new Product(a, new Sum(b, c)), result);
        replay(source, result);
    }

    @Test void coincidentNamesAreNotEvidenceOfEqualFactors() {
        var a = matrix("sameName", new long[][] {{1}});
        var b = matrix("sameName", new long[][] {{2}});
        var c = matrix("c", new long[][] {{3}});
        var d = matrix("d", new long[][] {{4}});
        var source = new Sum(new Product(a, c), new Product(b, d));
        assertTrue(factorizations(source).isEmpty());
    }

    @Test void doesNotMoveAFactorAcrossTheOtherOperand() {
        var a = matrix("a", new long[][] {{1, 1}, {0, 1}});
        var b = matrix("b", new long[][] {{1, 0}, {1, 1}});
        var source = new Sum(new Product(a, b), new Product(b, a));
        assertTrue(factorizations(source).isEmpty());
        var algebra = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(100_000));
        assertFalse(algebra.equal(algebra.evaluate(new Product(a, b)),
                algebra.evaluate(new Product(b, a))));
    }

    @Test void keepsTheSurroundingOrderedProductWhenFactoringASubexpression() {
        var a = matrix("a", new long[][] {{1, 2}, {0, 1}});
        var b = matrix("b", new long[][] {{1, 0}, {3, 1}});
        var c = matrix("c", new long[][] {{2, 1}, {0, 2}});
        var left = matrix("outer", new long[][] {{1, 2}});
        var source = new Product(left, new Sum(new Product(a, b), new Product(a, c)));
        var result = rule(source, "COMMON_LEFT_FACTOR");
        assertEquals(new Product(left, new Product(a, new Sum(b, c))), result);
        replay(source, result);
    }

    @Test void rejectsInvalidSourceDimensionsBeforeProposingARewrite() {
        var a = matrix("a", new long[][] {{1, 2}});
        var b = matrix("b", new long[][] {{1}, {2}});
        var c = matrix("c", new long[][] {{1}});
        var source = new Sum(new Product(a, b), new Product(a, c));
        assertThrows(ExactMatrixAlgebra.Unsupported.class, () -> proposals(source, 100_000));
    }

    @Test void anInvalidInverseWitnessCannotBeHiddenByFactoring() {
        var a = matrix("a", new long[][] {{2}});
        var invalid = new Inverse(a, new PolynomialMatrix(List.of(List.of(Polynomial.constant(Rational.ONE)))));
        var source = new Sum(new Product(invalid, a), new Product(invalid, new Identity(1)));
        assertThrows(ExactMatrixAlgebra.Unsupported.class, () -> proposals(source, 100_000));
    }

    @Test void exhaustedWorkDoesNotReturnAnUnverifiedPartialProposalList() {
        var a = matrix("a", new long[][] {{2}});
        var source = new Sum(new Product(a, new Identity(1)), new Product(a, a));
        assertThrows(ExactMatrixAlgebra.Exhausted.class, () -> proposals(source, 0));
    }

    private static List<MatrixOperatorRules.Rewrite> proposals(ExactMatrixExpression source, int budget) {
        var work = new ExactMatrixAlgebra.Work(budget);
        return MatrixOperatorRules.prepare(source, new ExactMatrixAlgebra(work), work);
    }

    private static List<MatrixOperatorRules.Rewrite> factorizations(ExactMatrixExpression source) {
        return proposals(source, 100_000).stream().filter(r -> r.rule().startsWith("COMMON_")).toList();
    }

    private static ExactMatrixExpression rule(ExactMatrixExpression source, String rule) {
        return proposals(source, 100_000).stream().filter(r -> r.rule().equals(rule))
                .map(MatrixOperatorRules.Rewrite::expression).findFirst().orElseThrow();
    }

    private static Matrix matrix(String name, long[][] entries) {
        var rows = new ArrayList<List<Polynomial>>();
        for (long[] row : entries) {
            var values = new ArrayList<Polynomial>();
            for (long entry : row) values.add(Polynomial.constant(Rational.of(entry)));
            rows.add(values);
        }
        return new Matrix(name, new PolynomialMatrix(rows));
    }

    private static void replay(ExactMatrixExpression source, ExactMatrixExpression candidate) {
        var algebra = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(100_000));
        var coefficients = algebra.evaluate(source);
        var rows = new ArrayList<String>();
        var coordinates = new ArrayList<String>();
        for (int i = 0; i < coefficients.rows(); i++) rows.add("source-row-" + i);
        for (int i = 0; i < coefficients.columns(); i++) coordinates.add("coordinate" + i);
        var obligation = new MatrixRepresentationBridge.Obligation(coefficients, rows, coordinates,
                "independent-test-source", candidate, List.of("ordered factorization"));
        var bridge = new MatrixRepresentationBridge();
        var proof = bridge.analyze(obligation, new RepresentationBridge.Budget(100_000));
        assertTrue(proof.represented(), proof.detailCode());
        assertTrue(bridge.verify(obligation, proof));
        var vectorBridge = new MatrixRepresentationBridge.VectorReplay();
        var replay = vectorBridge.analyze(proof.representation().orElseThrow(),
                new RepresentationBridge.Budget(100_000));
        assertTrue(replay.represented(), replay.detailCode());
        assertTrue(vectorBridge.verify(proof.representation().orElseThrow(), replay));
    }
}
