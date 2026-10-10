package de.regelsuche.ast;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.regelsuche.symbol.SymbolId;
import java.util.Objects;
import java.util.Optional;

/** One syntax occurrence. Scoped occurrences carry an ID, never a display label. */
public final class VariableExpr implements Expr {
    private final String name;
    private final SymbolId symbol;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public VariableExpr(@JsonProperty("name") String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        this.symbol = name.startsWith(SymbolId.IDENTIFIER_PREFIX) ? SymbolId.fromIdentifier(name) : null;
    }

    private VariableExpr(SymbolId symbol) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.name = symbol.identifier();
    }

    /** Creates a fresh occurrence of an existing mathematical symbol. */
    public static VariableExpr scoped(SymbolId symbol) {
        return new VariableExpr(symbol);
    }

    /** Legacy name or lossless identity transport, not the scoped symbol's display label. */
    @JsonProperty("name")
    public String name() {
        return name;
    }

    @JsonIgnore
    public Optional<SymbolId> symbol() {
        return Optional.ofNullable(symbol);
    }

    // Core identity avoids allocating an Optional while inspecting a value label.
    SymbolId identitySymbol() { return symbol; }

    @Override
    public boolean equals(Object other) {
        if (de.regelsuche.retention.RetainedOperation.isObserved()) return ExpressionIdentity.same(this, other);
        if (this == other) return true;
        if (!(other instanceof VariableExpr variable)) return false;
        return symbol == null ? variable.symbol == null && name.equals(variable.name) : symbol.equals(variable.symbol);
    }

    @Override
    public int hashCode() {
        if (de.regelsuche.retention.RetainedOperation.isObserved()) return ExpressionIdentity.valueHash(this);
        return symbol == null ? name.hashCode() : symbol.hashCode();
    }

    @Override
    public String toString() {
        return "VariableExpr[name=" + name + "]";
    }
}
