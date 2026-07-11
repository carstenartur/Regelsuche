package de.regelsuche.transform;

import de.regelsuche.ast.BinaryOperator;
import java.util.EnumSet;
import java.util.Set;

/**
 * Controls which algebraic equivalences may be used while recognizing the
 * left-hand side of a pattern rule.
 *
 * <p>The default profile is deliberately exact. Rules opt into broader
 * recognition explicitly, so adding a new equivalence never silently changes
 * the meaning or cost of every existing matcher.</p>
 */
public record RecognitionProfile(
    Set<BinaryOperator> associativeOperators,
    Set<BinaryOperator> commutativeOperators
) {
    public RecognitionProfile {
        associativeOperators = immutableEnumSet(associativeOperators);
        commutativeOperators = immutableEnumSet(commutativeOperators);
        if (!associativeOperators.containsAll(commutativeOperators)) {
            throw new IllegalArgumentException("commutative matching currently requires associative matching");
        }
    }

    public static RecognitionProfile exact() {
        return new RecognitionProfile(Set.of(), Set.of());
    }

    /**
     * Algebraic recognition modulo associativity and commutativity of addition
     * and multiplication.
     */
    public static RecognitionProfile arithmeticAc() {
        Set<BinaryOperator> operators = EnumSet.of(BinaryOperator.ADD, BinaryOperator.MUL);
        return new RecognitionProfile(operators, operators);
    }

    public boolean isAssociative(BinaryOperator operator) {
        return associativeOperators.contains(operator);
    }

    public boolean isCommutative(BinaryOperator operator) {
        return commutativeOperators.contains(operator);
    }

    private static Set<BinaryOperator> immutableEnumSet(Set<BinaryOperator> operators) {
        if (operators == null || operators.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(EnumSet.copyOf(operators));
    }
}
