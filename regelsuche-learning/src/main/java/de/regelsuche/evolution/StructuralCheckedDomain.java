package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedOperation;
import java.util.ArrayDeque;

/** Restrict textual polynomial projection to symbols the expression parser reads as one symbol. */
final class StructuralCheckedDomain {
    private StructuralCheckedDomain() { }

    static void requireUnobserved() {
        // This experimental checker has its own work ledger, but no nested native-ownership contract.
        if (RetainedOperation.isObserved()) throw new IllegalStateException("structural checker does not support nested native observation");
    }

    static int inspect(Expr expression, CheckedSchemaSupport.Work work) {
        requireUnobserved();
        CheckedSchemaSupport.domain(expression, CheckedLearnedSchemaModel.Bounds.defaults(), work);
        var pending = new ArrayDeque<Expr>();
        pending.push(expression);
        int nodes = 0;
        while (!pending.isEmpty()) {
            var node = pending.pop();
            nodes++;
            work.add(1);
            if (node instanceof VariableExpr variable) {
                String name = variable.name();
                // Mirrors ExpressionParser's character-by-character identifier grammar.
                work.add(name.length());
                if (!Character.isLetter(name.charAt(0))) throw new CheckedSchemaSupport.DomainRejected("symbol is not a parser identifier");
                for (int i = 1; i < name.length(); i++) {
                    char item = name.charAt(i);
                    if (!Character.isLetterOrDigit(item) && item != '_') {
                        throw new CheckedSchemaSupport.DomainRejected("symbol is not a parser identifier");
                    }
                }
            } else if (node instanceof BinaryExpr binary) {
                pending.push(binary.right());
                pending.push(binary.left());
            }
        }
        return nodes;
    }
}
