package de.regelsuche.moves.hypothesis;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects every subterm of an expression together with the structural metadata
 * ({@link TermOccurrence}) required to derive plausible parameters.
 *
 * <p>The index treats complex subtrees as first-class atoms: a pattern that
 * works for {@code x} works equally for {@code x + 1}, {@code sin(t)} or
 * {@code (p*q + r)^2}, as long as the subtree sits at the right structural
 * position.</p>
 */
public final class TermOccurrenceIndex {

    private final List<TermOccurrence> occurrences;
    private final Map<String, Integer> countsByCanonical;

    private TermOccurrenceIndex(List<TermOccurrence> rawOccurrences) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TermOccurrence occurrence : rawOccurrences) {
            counts.merge(occurrence.canonicalValue(), 1, Integer::sum);
        }
        List<TermOccurrence> enriched = new ArrayList<>(rawOccurrences.size());
        for (TermOccurrence occurrence : rawOccurrences) {
            enriched.add(occurrence.withOccurrenceCount(counts.get(occurrence.canonicalValue())));
        }
        enriched.sort(TermOccurrence.CANONICAL_ORDER);
        this.occurrences = List.copyOf(enriched);
        this.countsByCanonical = Map.copyOf(counts);
    }

    /** Builds an index over a single term. */
    public static TermOccurrenceIndex forExpression(Expr root) {
        List<TermOccurrence> raw = new ArrayList<>();
        collect(root, TermRole.ROOT, "", 0, "", raw);
        return new TermOccurrenceIndex(raw);
    }

    /** Builds an index over an equation, marking each side as {@link TermRole#EQUATION_SIDE}. */
    public static TermOccurrenceIndex forEquation(Equation equation) {
        List<TermOccurrence> raw = new ArrayList<>();
        collect(equation.left(), TermRole.EQUATION_SIDE, "=", 0, "L", raw);
        collect(equation.right(), TermRole.EQUATION_SIDE, "=", 0, "R", raw);
        return new TermOccurrenceIndex(raw);
    }

    /** @return all occurrences, deterministically ordered. */
    public List<TermOccurrence> occurrences() {
        return occurrences;
    }

    /** @return one occurrence per distinct canonical value (first in canonical order). */
    public List<TermOccurrence> distinctByCanonical() {
        Map<String, TermOccurrence> distinct = new LinkedHashMap<>();
        for (TermOccurrence occurrence : occurrences) {
            distinct.putIfAbsent(occurrence.canonicalValue(), occurrence);
        }
        return List.copyOf(distinct.values());
    }

    /** @return distinct composite subterms occurring at least twice (substitution candidates). */
    public List<TermOccurrence> repeatedComposites() {
        List<TermOccurrence> result = new ArrayList<>();
        for (TermOccurrence occurrence : distinctByCanonical()) {
            if (occurrence.occurrenceCount() >= 2 && occurrence.isComposite()) {
                result.add(occurrence);
            }
        }
        return List.copyOf(result);
    }

    /** @return how often the canonical value occurs (0 when absent). */
    public int occurrenceCount(String canonicalValue) {
        return countsByCanonical.getOrDefault(canonicalValue, 0);
    }

    /** @return whether the canonical value occurs at all. */
    public boolean contains(String canonicalValue) {
        return countsByCanonical.containsKey(canonicalValue);
    }

    private static void collect(Expr expr, TermRole role, String parentOperator, int depth, String path,
            List<TermOccurrence> out) {
        if (expr == null) {
            return;
        }
        String formatted = HypothesisExpressions.format(expr);
        out.add(new TermOccurrence(formatted, formatted, depth, 1, parentOperator, role, path));
        if (expr instanceof BinaryExpr binary) {
            BinaryOperator operator = binary.operator();
            TermRole leftRole = leftRole(operator);
            TermRole rightRole = rightRole(operator);
            collect(binary.left(), leftRole, operator.symbol(), depth + 1, child(path, 0), out);
            collect(binary.right(), rightRole, operator.symbol(), depth + 1, child(path, 1), out);
        } else if (expr instanceof FunctionExpr function) {
            String op = "fn:" + function.name();
            for (int i = 0; i < function.arguments().size(); i++) {
                collect(function.arguments().get(i), TermRole.ARGUMENT, op, depth + 1, child(path, i), out);
            }
        }
    }

    private static TermRole leftRole(BinaryOperator operator) {
        return switch (operator) {
            case ADD, SUB -> TermRole.SUMMAND;
            case MUL, DIV -> TermRole.FACTOR;
            case POW -> TermRole.EXPONENT_BASE;
        };
    }

    private static TermRole rightRole(BinaryOperator operator) {
        return switch (operator) {
            case ADD, SUB -> TermRole.SUMMAND;
            case MUL, DIV -> TermRole.FACTOR;
            case POW -> TermRole.EXPONENT;
        };
    }

    private static String child(String path, int index) {
        return path.isEmpty() ? Integer.toString(index) : path + "." + index;
    }
}
