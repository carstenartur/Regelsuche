package de.regelsuche.benchmark;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Independent, conditional replay of a proposed modular-exponent composition.
 * Uses the unchanged #1025 domain and one-step difference auditors, not a
 * learning score or retained training premises. The supported arithmetic
 * fragment is deliberately the same as that auditor's variable/product fragment.
 */
public final class ModPowCompositionReplay {
    public static final int MAXIMUM_NODES = 1024;
    public static final int MAXIMUM_DEPTH = 64;

    private ModPowCompositionReplay() {}

    /** A checked concrete application, not an unconditional learned rule. */
    public static final class CheckedComposition {
        private final Expr source;
        private final Expr target;
        private final String primitiveRule;
        private final List<String> assumptions;

        private CheckedComposition(Expr source, Expr target, String primitiveRule, List<String> assumptions) {
            this.source = source;
            this.target = target;
            this.primitiveRule = primitiveRule;
            this.assumptions = List.copyOf(assumptions);
        }
        public Expr source() { return source; }
        public Expr target() { return target; }
        public String primitiveRule() { return primitiveRule; }
        public List<String> assumptions() { return assumptions; }
    }

    /**
     * Replays exactly one primitive composition in the entire expression.
     * Missing premises and unsupported arithmetic produce no approval;
     * malformed or structurally oversized requests are explicit input errors.
     * Supplied premises are assumptions, not facts inferred by this adapter.
     */
    public static Optional<CheckedComposition> verify(Expr source, Expr target, List<String> assumptions) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        var retained = List.copyOf(Objects.requireNonNull(assumptions, "assumptions"));
        requireBounded(source);
        requireBounded(target);
        if (!ModPowDagRediscoveryStudy.domainContractSatisfied(source, retained)
                || !ModPowDagRediscoveryStudy.domainContractSatisfied(target, retained)) {
            return Optional.empty();
        }
        for (String primitive : List.of(ModPowDagRediscoveryStudy.RULE_LEFT, ModPowDagRediscoveryStudy.RULE_RIGHT)) {
            if (ModPowDagRediscoveryStudy.compositionDifferenceCount(source, target, primitive) == 1) {
                return Optional.of(new CheckedComposition(source, target, primitive, retained));
            }
        }
        return Optional.empty();
    }

    private record Pending(Expr expression, int depth) {}

    /** Count occurrences before invoking the existing recursive domain/shape auditor. */
    private static void requireBounded(Expr root) {
        var pending = new ArrayDeque<Pending>();
        pending.push(new Pending(root, 0));
        int visited = 0;
        while (!pending.isEmpty()) {
            var next = pending.pop();
            if (++visited > MAXIMUM_NODES || next.depth() > MAXIMUM_DEPTH) {
                throw new IllegalArgumentException("modpow replay structural limit exceeded");
            }
            if (next.expression() instanceof BinaryExpr binary) {
                pending.push(new Pending(binary.right(), next.depth() + 1));
                pending.push(new Pending(binary.left(), next.depth() + 1));
            } else if (next.expression() instanceof FunctionExpr function) {
                if (function.arguments().size() > MAXIMUM_NODES - visited) {
                    throw new IllegalArgumentException("modpow replay argument limit exceeded");
                }
                for (Expr argument : function.arguments()) {
                    pending.push(new Pending(argument, next.depth() + 1));
                }
            }
        }
    }
}
