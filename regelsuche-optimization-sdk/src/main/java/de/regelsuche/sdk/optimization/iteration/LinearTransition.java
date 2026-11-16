package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.Expr;
import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/** Immutable transport: states, constant one, then index powers in ascending degree. */
public record LinearTransition(List<List<Expr>> matrix, List<Expr> initial, int stateCount, int indexDegree) {
    public LinearTransition {
        if (!(matrix instanceof CheckedMatrix checked) || checked.transition.initial() != initial
                || checked.transition.stateCount() != stateCount || checked.transition.indexDegree() != indexDegree) {
            var transition = new de.regelsuche.math.algorithms.iteration.LinearTransition(
                matrix, initial, stateCount, indexDegree);
            matrix = new CheckedMatrix(transition);
            initial = transition.initial();
        }
    }

    @Override
    public List<List<Expr>> matrix() {
        return mathTransition().matrix();
    }

    static LinearTransition fromMath(de.regelsuche.math.algorithms.iteration.LinearTransition transition) {
        return new LinearTransition(new CheckedMatrix(transition), transition.initial(),
            transition.stateCount(), transition.indexDegree());
    }

    de.regelsuche.math.algorithms.iteration.LinearTransition mathTransition() {
        return ((CheckedMatrix) matrix).transition;
    }

    // Retain the exact checked matrix and vector without copying or walking their expressions again.
    private static final class CheckedMatrix extends AbstractList<List<Expr>> implements RandomAccess {
        private final de.regelsuche.math.algorithms.iteration.LinearTransition transition;

        private CheckedMatrix(de.regelsuche.math.algorithms.iteration.LinearTransition transition) {
            this.transition = transition;
        }

        @Override
        public List<Expr> get(int index) {
            return transition.matrix().get(index);
        }

        @Override
        public int size() {
            return transition.matrix().size();
        }
    }
}
