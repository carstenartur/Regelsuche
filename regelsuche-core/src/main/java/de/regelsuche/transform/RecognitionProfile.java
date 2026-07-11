package de.regelsuche.transform;

import de.regelsuche.ast.BinaryOperator;
import java.util.EnumSet;
import java.util.Set;

/** Controls which equivalences may be used while recognizing a rule. */
public record RecognitionProfile(
    Set<BinaryOperator> associativeOperators,
    Set<BinaryOperator> commutativeOperators,
    boolean inferAlgebraicBindings,
    Set<String> recognitionRuleIds,
    int maxEquivalenceDepth
) {
    public RecognitionProfile(Set<BinaryOperator> associativeOperators, Set<BinaryOperator> commutativeOperators) {
        this(associativeOperators, commutativeOperators, false, Set.of(), 0);
    }

    public RecognitionProfile(
        Set<BinaryOperator> associativeOperators,
        Set<BinaryOperator> commutativeOperators,
        boolean inferAlgebraicBindings
    ) {
        this(associativeOperators, commutativeOperators, inferAlgebraicBindings, Set.of(), 0);
    }

    public RecognitionProfile {
        associativeOperators = immutableEnumSet(associativeOperators);
        commutativeOperators = immutableEnumSet(commutativeOperators);
        recognitionRuleIds = recognitionRuleIds == null ? Set.of() : Set.copyOf(recognitionRuleIds);
        if (!associativeOperators.containsAll(commutativeOperators)) {
            throw new IllegalArgumentException("commutative matching currently requires associative matching");
        }
        if (maxEquivalenceDepth < 0) {
            throw new IllegalArgumentException("maxEquivalenceDepth must not be negative");
        }
    }

    public static RecognitionProfile exact() {
        return new RecognitionProfile(Set.of(), Set.of(), false, Set.of(), 0);
    }

    public static RecognitionProfile arithmeticAc() {
        Set<BinaryOperator> operators = EnumSet.of(BinaryOperator.ADD, BinaryOperator.MUL);
        return new RecognitionProfile(operators, operators, false, Set.of(), 0);
    }

    public static RecognitionProfile algebraicAc() {
        Set<BinaryOperator> operators = EnumSet.of(BinaryOperator.ADD, BinaryOperator.MUL);
        return new RecognitionProfile(operators, operators, true, Set.of(), 0);
    }

    public RecognitionProfile withRecognitionRules(Set<String> ruleIds, int depth) {
        return new RecognitionProfile(
            associativeOperators,
            commutativeOperators,
            inferAlgebraicBindings,
            ruleIds,
            depth
        );
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
