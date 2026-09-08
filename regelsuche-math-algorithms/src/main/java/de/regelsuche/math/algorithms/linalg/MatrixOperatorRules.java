package de.regelsuche.math.algorithms.linalg;

import java.util.ArrayList;
import java.util.List;
import static de.regelsuche.math.algorithms.linalg.ExactMatrixExpression.*;

/** Finite one-step ordered rules; all dimension and inverse guards replay first. */
public final class MatrixOperatorRules {
    private MatrixOperatorRules() { }

    public record Rewrite(String rule, ExactMatrixExpression expression) { }

    public static List<Rewrite> prepare(ExactMatrixExpression source, ExactMatrixAlgebra algebra,
            ExactMatrixAlgebra.Work work) {
        algebra.evaluate(source);
        List<Rewrite> result = new ArrayList<>();
        visit(source, result, work);
        return List.copyOf(result);
    }

    private static void visit(ExactMatrixExpression source, List<Rewrite> result, ExactMatrixAlgebra.Work work) {
        work.consume(1);
        switch (source) {
            case Product product -> {
                productRules(product, result);
                for (Rewrite child : children(product.left(), work)) {
                    result.add(new Rewrite(child.rule(), new Product(child.expression(), product.right())));
                }
                for (Rewrite child : children(product.right(), work)) {
                    result.add(new Rewrite(child.rule(), new Product(product.left(), child.expression())));
                }
            }
            case Sum sum -> {
                for (Rewrite child : children(sum.left(), work)) {
                    result.add(new Rewrite(child.rule(), new Sum(child.expression(), sum.right())));
                }
                for (Rewrite child : children(sum.right(), work)) {
                    result.add(new Rewrite(child.rule(), new Sum(sum.left(), child.expression())));
                }
            }
            default -> { }
        }
    }

    private static List<Rewrite> children(ExactMatrixExpression expression, ExactMatrixAlgebra.Work work) {
        List<Rewrite> results = new ArrayList<>();
        visit(expression, results, work);
        return results;
    }

    private static void productRules(Product product, List<Rewrite> result) {
        if (product.left() instanceof Identity) {
            result.add(new Rewrite("LEFT_IDENTITY", product.right()));
        }
        if (product.right() instanceof Identity) {
            result.add(new Rewrite("RIGHT_IDENTITY", product.left()));
        }
        if (product.left() instanceof Product nested) {
            result.add(new Rewrite("ORDERED_ASSOCIATIVITY", new Product(nested.left(),
                new Product(nested.right(), product.right()))));
        }
        if (product.right() instanceof Sum sum) {
            result.add(new Rewrite("LEFT_DISTRIBUTIVITY", new Sum(
                new Product(product.left(), sum.left()), new Product(product.left(), sum.right()))));
        }
        if (product.left() instanceof Sum sum) {
            result.add(new Rewrite("RIGHT_DISTRIBUTIVITY", new Sum(
                new Product(sum.left(), product.right()), new Product(sum.right(), product.right()))));
        }
        cancellation(product.left(), product.right(), result);
        cancellation(product.right(), product.left(), result);
    }

    private static void cancellation(ExactMatrixExpression possibleInverse, ExactMatrixExpression other,
            List<Rewrite> result) {
        if (possibleInverse instanceof Inverse inverse && inverse.operand().equals(other)) {
            result.add(new Rewrite("VERIFIED_INVERSE_CANCELLATION", new Identity(inverse.witness().rows())));
        }
    }
}
