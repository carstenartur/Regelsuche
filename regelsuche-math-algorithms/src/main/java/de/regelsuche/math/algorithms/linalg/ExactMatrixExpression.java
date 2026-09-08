package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import java.util.List;
import java.util.Objects;

/** Ordered matrix syntax. Scalar rewrite rules never operate on these values. */
public sealed interface ExactMatrixExpression {
    record Matrix(String name, PolynomialMatrix value) implements ExactMatrixExpression {
        public Matrix {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("matrix name must not be blank");
            }
            Objects.requireNonNull(value, "value");
        }
    }

    record Identity(int dimension) implements ExactMatrixExpression {
        public Identity {
            if (dimension < 1) {
                throw new IllegalArgumentException("identity dimension must be positive");
            }
        }
    }

    record Product(ExactMatrixExpression left, ExactMatrixExpression right)
            implements ExactMatrixExpression {
        public Product {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    record Sum(ExactMatrixExpression left, ExactMatrixExpression right)
            implements ExactMatrixExpression {
        public Sum {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    /** A proposed inverse is evidence only after both ordered products equal I. */
    record Inverse(ExactMatrixExpression operand, PolynomialMatrix witness)
            implements ExactMatrixExpression {
        public Inverse {
            Objects.requireNonNull(operand, "operand");
            Objects.requireNonNull(witness, "witness");
        }
    }

    record BlockDiagonal(List<ExactMatrixExpression> blocks) implements ExactMatrixExpression {
        public BlockDiagonal {
            blocks = List.copyOf(blocks);
            if (blocks.isEmpty()) {
                throw new IllegalArgumentException("blocks must not be empty");
            }
        }
    }

    /** The permutations map each displayed block index back to its source index. */
    record Mapped(ExactMatrixExpression expression, List<Integer> rows, List<Integer> columns)
            implements ExactMatrixExpression {
        public Mapped {
            Objects.requireNonNull(expression, "expression");
            rows = permutation(rows);
            columns = permutation(columns);
        }

        private static List<Integer> permutation(List<Integer> values) {
            List<Integer> copy = List.copyOf(values);
            for (int i = 0; i < copy.size(); i++) {
                if (!copy.contains(i)) {
                    throw new IllegalArgumentException("mapping must be a complete permutation");
                }
            }
            return copy;
        }
    }

    default String display() {
        return switch (this) {
            case Matrix m -> m.name();
            case Identity i -> "I(" + i.dimension() + ")";
            case Product p -> "(" + p.left().display() + " * " + p.right().display() + ")";
            case Sum s -> "(" + s.left().display() + " + " + s.right().display() + ")";
            case Inverse i -> "inverse(" + i.operand().display() + ")";
            case BlockDiagonal b -> "blockDiagonal(" + String.join(", ",
                b.blocks().stream().map(ExactMatrixExpression::display).toList()) + ")";
            case Mapped m -> "restoreOrder(" + m.expression().display()
                + ", rows=" + m.rows() + ", columns=" + m.columns() + ")";
        };
    }
}
