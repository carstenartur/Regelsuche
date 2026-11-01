package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.linalg.ExactMatrixExpression.Product;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Bounded matrix-chain parenthesization, never a permutation of factors.
 * The score counts dense scalar multiplications, not measured execution time.
 * Scalar costs, sparsity and allocation costs are deliberately not inferred.
 */
final class OrderedMatrixChain {
    private static final int MAX_FACTORS = 32;

    private record Shape(int rows, int columns, long multiplications) { }

    private OrderedMatrixChain() { }

    /** The caller validates the complete original expression before entering. */
    static Optional<ExactMatrixExpression> propose(ExactMatrixExpression source,
            ExactMatrixAlgebra algebra, ExactMatrixAlgebra.Work work) {
        if (!(source instanceof Product)) return Optional.empty();
        List<ExactMatrixExpression> factors = new ArrayList<>();
        List<Shape> shapes = new ArrayList<>();
        Shape original = collect(source, factors, shapes, algebra, work);
        if (original == null || factors.size() < 3) return Optional.empty();

        int count = factors.size();
        work.consume(2L * count * count); // Reserve bounded DP storage before allocation.
        long[][] costs = new long[count][count];
        int[][] splits = new int[count][count];
        for (int length = 2; length <= count; length++) {
            for (int start = 0; start + length <= count; start++) {
                int end = start + length - 1;
                costs[start][end] = Long.MAX_VALUE;
                for (int middle = start; middle < end; middle++) {
                    work.consume(1);
                    long product = (long) shapes.get(start).rows()
                        * shapes.get(middle).columns() * shapes.get(end).columns();
                    long candidate = costs[start][middle] + costs[middle + 1][end] + product;
                    if (candidate < costs[start][end]) {
                        costs[start][end] = candidate;
                        splits[start][end] = middle;
                    }
                }
            }
        }
        if (costs[0][count - 1] >= original.multiplications()) return Optional.empty();
        ExactMatrixExpression candidate = rebuild(factors, splits, 0, count - 1, work);
        try {
            // A new grouping can exceed the existing depth/intermediate-polynomial caps.
            // Such a plan is unavailable, even if its final polynomial would be small.
            new ExactMatrixAlgebra(work).evaluate(candidate);
        } catch (ExactMatrixAlgebra.Unsupported outsideExistingCaps) {
            return Optional.empty();
        }
        // This is only a proposal. MatrixRepresentationBridge independently checks
        // every ordered coefficient and VectorReplay checks the staged application.
        return Optional.of(candidate);
    }

    private static Shape collect(ExactMatrixExpression expression,
            List<ExactMatrixExpression> factors, List<Shape> shapes,
            ExactMatrixAlgebra algebra, ExactMatrixAlgebra.Work work) {
        work.consume(1);
        if (expression instanceof Product product) {
            Shape left = collect(product.left(), factors, shapes, algebra, work);
            if (left == null) return null;
            Shape right = collect(product.right(), factors, shapes, algebra, work);
            if (right == null) return null;
            return new Shape(left.rows(), right.columns(), left.multiplications() + right.multiplications()
                + (long) left.rows() * left.columns() * right.columns());
        }
        if (factors.size() == MAX_FACTORS) return null;
        var value = algebra.evaluate(expression);
        Shape shape = new Shape(value.rows(), value.columns(), 0);
        factors.add(expression);
        shapes.add(shape);
        return shape;
    }

    private static ExactMatrixExpression rebuild(List<ExactMatrixExpression> factors, int[][] splits,
            int start, int end, ExactMatrixAlgebra.Work work) {
        work.consume(1);
        if (start == end) return factors.get(start);
        int middle = splits[start][end];
        return new Product(rebuild(factors, splits, start, middle, work),
            rebuild(factors, splits, middle + 1, end, work));
    }
}
