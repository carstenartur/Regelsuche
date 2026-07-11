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
    Set<BinaryOperator> commutativeOperators,
    boolean inferAlgebraicBindings
) {
    public RecognitionProfile(Set<BinaryOperator> associativeOperators, Set<BinaryOperator> commutativeOperators) {
        this(associativeOperators, commutativeOperators, false);
    }

    public RecognitionProfile {
        associativeOperators = immutableEnumSet(associativeOperators);
        commutativeOperators = immutableEnumSet(commutativeOperators);
        if (!associativeOperators.containsAll(commutativeOperators)) {
            throw new IllegalArgumentException("commutative matching currently requires associative matching");
        }
    }

    public static RecognitionProfile exact() {
        return new RecognitionProfile(Set.of(), Set.of(), false);
    }

    /**
     * Recognition modulo associativity and commutativity of addition and
     * multiplication, without solving algebraic placeholder bindings.
     */
    public static RecognitionProfile arithmeticAc() {
        Set<BinaryOperator> operators = EnumSet.of(BinaryOperator.ADD, BinaryOperator.MUL);
        return new RecognitionProfile(operators, operators, false);
    }

    /**
     * AC recognition plus bounded monomial binding inference. This profile can
     * infer bindings such as {@code A = 3/2*a} from a square term
     * {@code 9/4*a^2} and checks every later occurrence modulo normalized
     * numeric coefficients and powers.
     */
    public static RecognitionProfile algebraicAc() {
        Set<BinaryOperator> operators = EnumSet.of(BinaryOperator.ADD, BinaryOperator.MUL);
        return new RecognitionProfile(operators, operators, true);
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
