package de.regelsuche.search.program;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure operator semantics for a prepared DAG. Code generation is optional, never required.
 * Implementations must keep type/signature semantics stable for the lifetime of a prepared plan.
 * Storage units are domain estimates, not measured bytes; operations must not mutate their inputs.
 */
public interface ComputationBackend {
    record Type(String id, Class<?> runtimeClass) {
        public Type {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("type identity required");
            Objects.requireNonNull(runtimeClass, "runtimeClass");
        }
        public void requireValue(Object value) {
            if (!runtimeClass.isInstance(value)) throw new IllegalArgumentException("value does not have type " + id);
        }
    }
    record Operation(String id, List<Type> arguments, Type result, long work, long storage) {
        public Operation {
            if (id == null || id.isBlank() || work < 0 || storage < 0) throw new IllegalArgumentException("invalid operation");
            arguments = List.copyOf(arguments);
            Objects.requireNonNull(result, "result");
        }
    }
    Type literalType(NumberExpr literal);
    Object literal(NumberExpr literal);
    Operation operation(Expr expression);
    Object apply(Operation operation, List<Object> arguments);
    default long leafStorage(Type type) { return 1; }
    /** Called for every execution, including output-only plans. No proof authorization is cached. */
    default void validateInputs(Map<String, Object> inputs) {}
}
