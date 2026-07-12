package de.regelsuche.value;

import de.regelsuche.ast.Expr;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** One syntax root projected into a shared mathematical value graph. */
public final class ExprValueProjection {
    private final Expr syntaxRoot;
    private final ExprValue valueRoot;
    private final Map<Expr, ExprValue> valuesBySyntaxIdentity;

    ExprValueProjection(
            Expr syntaxRoot,
            ExprValue valueRoot,
            IdentityHashMap<Expr, ExprValue> valuesBySyntaxIdentity) {
        this.syntaxRoot = Objects.requireNonNull(syntaxRoot, "syntaxRoot");
        this.valueRoot = Objects.requireNonNull(valueRoot, "valueRoot");
        IdentityHashMap<Expr, ExprValue> copy = new IdentityHashMap<>(valuesBySyntaxIdentity);
        this.valuesBySyntaxIdentity = Collections.unmodifiableMap(copy);
    }

    public Expr syntaxRoot() {
        return syntaxRoot;
    }

    public ExprValue valueRoot() {
        return valueRoot;
    }

    /** Looks up by syntax-object identity, not by structural {@code Expr.equals}. */
    public Optional<ExprValue> valueOf(Expr syntaxOccurrence) {
        return Optional.ofNullable(valuesBySyntaxIdentity.get(syntaxOccurrence));
    }

    public Map<Expr, ExprValue> valuesBySyntaxIdentity() {
        return valuesBySyntaxIdentity;
    }
}
