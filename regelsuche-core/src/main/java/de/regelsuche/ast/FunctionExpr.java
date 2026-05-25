package de.regelsuche.ast;

import java.util.List;
import java.util.Objects;

/**
 * Function-call expression node, e.g. {@code sin(x)}, {@code log(x*y)}, or
 * {@code abs(a)}.
 *
 * <p>The {@link #name()} is the lowercase function identifier; arguments
 * are arbitrary {@link Expr} trees. The set of "well-known" function names
 * recognised by the parser/formatter is documented in
 * {@link #BUILTIN_NAMES} — additional names are accepted as long as they
 * survive parsing (any letter-prefix followed by a parenthesised argument
 * list).</p>
 *
 * <p>Functions intentionally support an arbitrary arity so the AST can also
 * represent multi-argument forms such as a future {@code log(base, x)}
 * without further changes; the current rule set uses single-argument
 * functions exclusively.</p>
 */
public record FunctionExpr(String name, List<Expr> arguments) implements Expr {
    /** Names recognised as built-ins by the parser/formatter/canonicalizer. */
    public static final List<String> BUILTIN_NAMES = List.of(
        "sin", "cos", "tan",
        "log", "ln",
        "sqrt", "exp", "abs"
    );

    public FunctionExpr {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("function name must not be blank");
        }
        Objects.requireNonNull(arguments, "arguments");
        arguments = List.copyOf(arguments);
    }

    public FunctionExpr(String name, Expr argument) {
        this(name, List.of(argument));
    }

    /** Convenience: first argument (the common single-arg case). */
    public Expr argument() {
        return arguments.get(0);
    }
}
