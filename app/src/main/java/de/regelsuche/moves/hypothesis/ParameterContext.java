package de.regelsuche.moves.hypothesis;

import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.moves.RewriteMoveKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The immutable input passed to every {@link ParameterHypothesisGenerator}.
 *
 * <p>It bundles the parsed input (and optional target) expression, their
 * {@link TermOccurrenceIndex term occurrence indices}, the precomputed
 * {@link TermSkeleton skeletons} (complex subtrees replaced by placeholders), a
 * complexity bound and the set of allowed move kinds.</p>
 */
public final class ParameterContext {

    private static final int DEFAULT_MAX_COMPLEXITY = 32;

    private final String inputExpression;
    private final String targetExpression;
    private final Expr inputAst;
    private final Equation inputEquation;
    private final Expr targetAst;
    private final Equation targetEquation;
    private final TermOccurrenceIndex inputIndex;
    private final TermOccurrenceIndex targetIndex;
    private final List<TermSkeleton> skeletons;
    private final int maxComplexity;
    private final Set<RewriteMoveKind> allowedMoveKinds;

    private ParameterContext(
            String inputExpression,
            String targetExpression,
            Expr inputAst,
            Equation inputEquation,
            Expr targetAst,
            Equation targetEquation,
            TermOccurrenceIndex inputIndex,
            TermOccurrenceIndex targetIndex,
            List<TermSkeleton> skeletons,
            int maxComplexity,
            Set<RewriteMoveKind> allowedMoveKinds) {
        this.inputExpression = inputExpression;
        this.targetExpression = targetExpression;
        this.inputAst = inputAst;
        this.inputEquation = inputEquation;
        this.targetAst = targetAst;
        this.targetEquation = targetEquation;
        this.inputIndex = inputIndex;
        this.targetIndex = targetIndex;
        this.skeletons = skeletons;
        this.maxComplexity = maxComplexity;
        this.allowedMoveKinds = allowedMoveKinds;
    }

    /** Builds a context for an input with no target and the default bounds. */
    public static ParameterContext of(String inputExpression) {
        return of(inputExpression, null, DEFAULT_MAX_COMPLEXITY, allMoveKinds());
    }

    /** Builds a context for an input and an optional target with the default bounds. */
    public static ParameterContext of(String inputExpression, String targetExpression) {
        return of(inputExpression, targetExpression, DEFAULT_MAX_COMPLEXITY, allMoveKinds());
    }

    /** Builds a fully specified context. */
    public static ParameterContext of(
            String inputExpression,
            String targetExpression,
            int maxComplexity,
            Set<RewriteMoveKind> allowedMoveKinds) {
        String input = inputExpression == null ? "" : inputExpression.trim();
        String target = targetExpression == null ? "" : targetExpression.trim();
        int complexity = maxComplexity < 1 ? DEFAULT_MAX_COMPLEXITY : maxComplexity;
        Set<RewriteMoveKind> kinds = allowedMoveKinds == null || allowedMoveKinds.isEmpty()
                ? allMoveKinds()
                : Set.copyOf(allowedMoveKinds);

        Equation inputEquation = HypothesisExpressions.parseEquation(input).orElse(null);
        Expr inputAst = inputEquation == null ? HypothesisExpressions.parseTerm(input).orElse(null) : null;
        Equation targetEquation = HypothesisExpressions.parseEquation(target).orElse(null);
        Expr targetAst = targetEquation == null ? HypothesisExpressions.parseTerm(target).orElse(null) : null;

        TermOccurrenceIndex inputIndex = buildIndex(inputAst, inputEquation);
        TermOccurrenceIndex targetIndex = buildIndex(targetAst, targetEquation);
        List<TermSkeleton> skeletons = buildSkeletons(inputAst, inputIndex, complexity);

        return new ParameterContext(
                input,
                target,
                inputAst,
                inputEquation,
                targetAst,
                targetEquation,
                inputIndex,
                targetIndex,
                skeletons,
                complexity,
                kinds);
    }

    private static Set<RewriteMoveKind> allMoveKinds() {
        return Set.of(RewriteMoveKind.values());
    }

    private static TermOccurrenceIndex buildIndex(Expr ast, Equation equation) {
        if (equation != null) {
            return TermOccurrenceIndex.forEquation(equation);
        }
        if (ast != null) {
            return TermOccurrenceIndex.forExpression(ast);
        }
        return null;
    }

    /**
     * Builds one skeleton per distinct atom candidate (composite subtree or
     * variable), bounded by {@code maxComplexity}. Placeholder names are assigned
     * deterministically ({@code A}, {@code B}, ...).
     */
    private static List<TermSkeleton> buildSkeletons(Expr inputAst, TermOccurrenceIndex inputIndex, int maxComplexity) {
        if (inputAst == null || inputIndex == null) {
            return List.of();
        }
        String rootCanonical = HypothesisExpressions.format(inputAst);
        Set<String> atoms = new LinkedHashSet<>();
        for (TermOccurrence occurrence : inputIndex.distinctByCanonical()) {
            String canonical = occurrence.canonicalValue();
            // Whole-expression and pure numbers are not useful substitution atoms.
            if (canonical.equals(rootCanonical) || isNumeric(canonical)) {
                continue;
            }
            atoms.add(canonical);
        }
        List<TermSkeleton> skeletons = new ArrayList<>();
        int placeholderIndex = 0;
        for (String atomCanonical : atoms) {
            if (skeletons.size() >= maxComplexity) {
                break;
            }
            Optional<Expr> atom = HypothesisExpressions.parseTerm(atomCanonical);
            if (atom.isEmpty() || !HypothesisExpressions.isAtomCandidate(atom.get())) {
                continue;
            }
            String placeholder = placeholderName(placeholderIndex++);
            skeletons.add(TermSkeleton.forAtom(inputAst, atom.get(), placeholder));
        }
        return List.copyOf(skeletons);
    }

    private static boolean isNumeric(String canonical) {
        try {
            Double.parseDouble(canonical);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String placeholderName(int index) {
        // A, B, ... Z, then A1, B1, ... to stay collision-free for wide inputs.
        char letter = (char) ('A' + (index % 26));
        int suffix = index / 26;
        return suffix == 0 ? Character.toString(letter) : letter + Integer.toString(suffix);
    }

    public String inputExpression() {
        return inputExpression;
    }

    public Optional<String> targetExpression() {
        return targetExpression.isBlank() ? Optional.empty() : Optional.of(targetExpression);
    }

    public Optional<Expr> inputAst() {
        return Optional.ofNullable(inputAst);
    }

    public Optional<Equation> inputEquation() {
        return Optional.ofNullable(inputEquation);
    }

    public Optional<Expr> targetAst() {
        return Optional.ofNullable(targetAst);
    }

    public Optional<Equation> targetEquation() {
        return Optional.ofNullable(targetEquation);
    }

    public Optional<TermOccurrenceIndex> inputIndex() {
        return Optional.ofNullable(inputIndex);
    }

    public Optional<TermOccurrenceIndex> targetIndex() {
        return Optional.ofNullable(targetIndex);
    }

    public List<TermSkeleton> skeletons() {
        return skeletons;
    }

    public int maxComplexity() {
        return maxComplexity;
    }

    public Set<RewriteMoveKind> allowedMoveKinds() {
        return allowedMoveKinds;
    }

    /** @return whether the given move kind is allowed in this context. */
    public boolean allows(RewriteMoveKind kind) {
        return allowedMoveKinds.contains(kind);
    }

    /** @return whether the input is a (parseable) equation. */
    public boolean hasEquation() {
        return inputEquation != null;
    }
}
