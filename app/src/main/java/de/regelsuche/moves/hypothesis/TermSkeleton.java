package de.regelsuche.moves.hypothesis;

import de.regelsuche.ast.Expr;

/**
 * A skeleton of an expression in which a complex subtree (the <em>atom</em>) is
 * replaced by a single placeholder symbol.
 *
 * <p>Example: {@code (a+b)^2 + 6*(a+b) + 5} with atom {@code a + b} becomes the
 * skeleton {@code A^2 + 6*A + 5}. This lets known patterns be recognised
 * independently of how complex the underlying subtree is.</p>
 *
 * @param placeholder   the placeholder variable name used in the skeleton
 * @param atomCanonical canonical form of the replaced atom
 * @param atomOriginal  original form of the replaced atom
 * @param skeleton      the skeleton AST (atom replaced by {@code placeholder})
 */
public record TermSkeleton(String placeholder, String atomCanonical, String atomOriginal, Expr skeleton) {

    public TermSkeleton {
        if (placeholder == null || placeholder.isBlank()) {
            throw new IllegalArgumentException("placeholder must not be blank");
        }
        if (skeleton == null) {
            throw new IllegalArgumentException("skeleton must not be null");
        }
        atomCanonical = atomCanonical == null ? "" : atomCanonical;
        atomOriginal = atomOriginal == null || atomOriginal.isBlank() ? atomCanonical : atomOriginal;
    }

    /** Builds a skeleton by replacing all occurrences of {@code atom} in {@code root}. */
    public static TermSkeleton forAtom(Expr root, Expr atom, String placeholder) {
        String atomCanonical = HypothesisExpressions.format(atom);
        Expr skeleton = HypothesisExpressions.replaceAtom(root, atomCanonical, placeholder);
        return new TermSkeleton(placeholder, atomCanonical, atomCanonical, skeleton);
    }

    /** @return the skeleton rendered back into infix form, e.g. {@code A^2 + 6*A + 5}. */
    public String skeletonText() {
        return HypothesisExpressions.format(skeleton);
    }
}
