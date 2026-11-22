package de.regelsuche.math.algorithms.noncommutative;

import de.regelsuche.scalar.ExactRational;
import java.util.Objects;

/** A formula tree over Q with ordered products and occurrence-sensitive inverse gates. */
public sealed interface RationalFormula {
    record Scalar(ExactRational value) implements RationalFormula {
        public Scalar { Objects.requireNonNull(value, "value"); }
    }

    record Variable(int id) implements RationalFormula {}

    record Sum(RationalFormula left, RationalFormula right) implements RationalFormula {
        public Sum {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    record Product(RationalFormula left, RationalFormula right) implements RationalFormula {
        public Product {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    record Inverse(RationalFormula operand) implements RationalFormula {
        public Inverse { Objects.requireNonNull(operand, "operand"); }
    }
}
