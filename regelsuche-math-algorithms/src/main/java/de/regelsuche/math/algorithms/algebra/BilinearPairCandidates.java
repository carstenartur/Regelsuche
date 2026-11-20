package de.regelsuche.math.algorithms.algebra;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * Proposes the three-product identity for joint commutative bilinear outputs.
 * These are candidates, not equality certificates; the consumer must verify them.
 * The adapter admits exact algebraic operations and owns its language's type semantics.
 */
public final class BilinearPairCandidates {
    private BilinearPairCandidates() {}

    public enum Operation { ADD, SUBTRACT, MULTIPLY, MOD }

    public record Binary<E>(Operation operation, E left, E right) {
        public Binary {
            Objects.requireNonNull(operation);
            Objects.requireNonNull(left);
            Objects.requireNonNull(right);
        }
    }

    /** An empty view excludes a term from algebraic pattern matching. */
    public interface Algebra<E> {
        Optional<Binary<E>> view(E expression);
        E make(Operation operation, E left, E right);
    }

    private record Eligible<E>(int index, Binary<E> expression, E modulus) {}

    /**
     * Returns immutable full output lists, changing only the paired sum output.
     * The caller's work callback is invoked before scans, pairing and allocation;
     * it may throw to enforce a shared budget or cooperative cancellation.
     */
    public static <E> List<List<E>> generate(List<E> outputs, int maximum,
            Algebra<E> algebra, LongConsumer charge) {
        Objects.requireNonNull(outputs);
        Objects.requireNonNull(algebra);
        Objects.requireNonNull(charge);
        if (maximum <= 0) return List.of();
        var subtractions = new ArrayList<Eligible<E>>();
        var additions = new ArrayList<Eligible<E>>();
        for (var iterator = outputs.listIterator(); iterator.hasNext();) {
            charge.accept(1);
            int index = iterator.nextIndex();
            var expression = algebra.view(iterator.next()).orElse(null);
            E modulus = null;
            if (expression != null && expression.operation() == Operation.MOD) {
                modulus = expression.right();
                expression = algebra.view(expression.left()).orElse(null);
            }
            if (expression == null || (expression.operation() != Operation.ADD
                    && expression.operation() != Operation.SUBTRACT)) continue;
            if (!isProduct(expression.left(), algebra) || !isProduct(expression.right(), algebra)) continue;
            (expression.operation() == Operation.SUBTRACT ? subtractions : additions)
                .add(new Eligible<>(index, expression, modulus));
        }
        var proposals = new ArrayList<List<E>>();
        for (var subtraction : subtractions) for (var addition : additions) {
            charge.accept(1);
            if (!Objects.equals(subtraction.modulus(), addition.modulus())) continue;
            var left = subtraction.expression();
            var right = addition.expression();
            var p = algebra.view(left.left()).orElseThrow();
            var q = algebra.view(left.right()).orElseThrow();
            for (int first = 0; first < 2; first++) for (int second = 0; second < 2; second++) {
                charge.accept(1);
                E a = first == 0 ? p.left() : p.right();
                E c = first == 0 ? p.right() : p.left();
                E b = second == 0 ? q.left() : q.right();
                E d = second == 0 ? q.right() : q.left();
                if (!(product(right.left(), a, d, algebra) && product(right.right(), b, c, algebra))
                        && !(product(right.right(), a, d, algebra) && product(right.left(), b, c, algebra))) continue;
                charge.accept(2L * outputs.size());
                E shared = algebra.make(Operation.MULTIPLY, algebra.make(Operation.ADD, a, b),
                    algebra.make(Operation.ADD, c, d));
                E imaginary = algebra.make(Operation.SUBTRACT,
                    algebra.make(Operation.SUBTRACT, shared, left.left()), left.right());
                var changed = new ArrayList<>(outputs);
                changed.set(addition.index(), addition.modulus() == null ? imaginary
                    : algebra.make(Operation.MOD, imaginary, addition.modulus()));
                proposals.add(List.copyOf(changed));
                if (proposals.size() == maximum) return List.copyOf(proposals);
                break; // Keep the next first-factor orientation, even when this one matched.
            }
        }
        return List.copyOf(proposals);
    }

    private static <E> boolean isProduct(E expression, Algebra<E> algebra) {
        return algebra.view(expression).map(b -> b.operation() == Operation.MULTIPLY).orElse(false);
    }

    private static <E> boolean product(E expression, E a, E b, Algebra<E> algebra) {
        var value = algebra.view(expression).orElse(null);
        return value != null && value.operation() == Operation.MULTIPLY
            && ((value.left().equals(a) && value.right().equals(b))
                || (value.left().equals(b) && value.right().equals(a)));
    }
}
