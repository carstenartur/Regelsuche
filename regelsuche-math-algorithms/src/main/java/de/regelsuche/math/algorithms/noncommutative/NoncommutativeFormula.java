package de.regelsuche.math.algorithms.noncommutative;

import de.regelsuche.scalar.ExactRational;
import java.util.Objects;

/** Division-free formula in the free associative algebra over Q. Products are ordered. */
public sealed interface NoncommutativeFormula {
    record Scalar(ExactRational value) implements NoncommutativeFormula {
        public Scalar { Objects.requireNonNull(value, "value"); }
    }

    /** Identifier, independent of its position in the explicitly supplied generator order. */
    record Variable(int id) implements NoncommutativeFormula {}

    enum Op { ADD, SUBTRACT, MULTIPLY }

    record Binary(Op op, NoncommutativeFormula left, NoncommutativeFormula right) implements NoncommutativeFormula {
        public Binary {
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    static Binary add(NoncommutativeFormula left, NoncommutativeFormula right) {
        return new Binary(Op.ADD, left, right);
    }

    static Binary subtract(NoncommutativeFormula left, NoncommutativeFormula right) {
        return new Binary(Op.SUBTRACT, left, right);
    }

    static Binary multiply(NoncommutativeFormula left, NoncommutativeFormula right) {
        return new Binary(Op.MULTIPLY, left, right);
    }
}
