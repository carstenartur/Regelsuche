package de.regelsuche.didactic;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pattern-based detector for typical student misconceptions (spec item 4).
 *
 * <p>The detector consumes a {@code (before, after)} pair — for example
 * the expression the student saw plus the step they wrote — and returns
 * the first matching {@link MisconceptionRule}, or {@link Optional#empty()}
 * if no known misconception fires.</p>
 *
 * <p>Detection is purely <em>structural</em>: we parse both sides and look
 * for known wrong-shape patterns. If an {@link EquivalenceService} is
 * supplied, the detector additionally insists that the step is actually
 * non-equivalent before reporting a misconception — so a coincidentally
 * correct cancellation (e.g. {@code (a*b)/b → a} with a clean factor)
 * does not get flagged as the false-cancellation misconception.</p>
 */
public final class MisconceptionDetector {

    private final ExpressionParser parser = new ExpressionParser();
    private final EquivalenceService equivalence;
    private final List<MisconceptionRule> catalogue;

    public MisconceptionDetector() {
        this(null);
    }

    public MisconceptionDetector(EquivalenceService equivalence) {
        this.equivalence = equivalence;
        this.catalogue = defaultCatalogue();
    }

    /** The built-in misconception catalogue. */
    public List<MisconceptionRule> catalogue() {
        return List.copyOf(catalogue);
    }

    /**
     * Detect a misconception for a term-level step ("term → term"). The
     * {@code before} expression is what was shown to the student; the
     * {@code after} expression is what the student wrote.
     */
    public Optional<MisconceptionRule> detectTermStep(String before, String after) {
        Expr beforeAst = tryParseTerm(before);
        Expr afterAst  = tryParseTerm(after);
        if (beforeAst == null || afterAst == null) {
            return Optional.empty();
        }
        // 1) (a + b) / b → a  (or (a + b) / c → a — false cancellation across +)
        if (isFalseCancellationOverSum(beforeAst, afterAst)
            && !isProvablyEquivalent(before, after)) {
            return ruleById("false_cancellation_sum_in_numerator");
        }
        // 2) -(a + b) → -a + b   (sign-distribution error: only the first summand was negated)
        if (isPartialNegationError(beforeAst, afterAst)
            && !isProvablyEquivalent(before, after)) {
            return ruleById("sign_distribution_partial");
        }
        return Optional.empty();
    }

    /**
     * Detect an inequality-flip omission: a strict / non-strict comparison
     * was divided or multiplied by a negative constant <em>without</em>
     * flipping the comparator. The inputs use the {@code <}, {@code <=},
     * {@code >}, {@code >=} infix syntax (the project does not parse those
     * as terms, so a lightweight string analysis is appropriate).
     */
    public Optional<MisconceptionRule> detectInequalityStep(String before, String after) {
        Comparator beforeCmp = Comparator.parse(before);
        Comparator afterCmp  = Comparator.parse(after);
        if (beforeCmp == null || afterCmp == null) {
            return Optional.empty();
        }
        // If the comparator did not change but the step looks like a
        // division/multiplication by a negative number, that's the
        // classical "vergessen, das Vergleichszeichen umzudrehen" mistake.
        if (beforeCmp.symbol.equals(afterCmp.symbol)
            && dividedByNegative(beforeCmp, afterCmp)) {
            return ruleById("inequality_missing_flip");
        }
        return Optional.empty();
    }

    private boolean isProvablyEquivalent(String left, String right) {
        if (equivalence == null) {
            return false;
        }
        try {
            return equivalence.areEquivalent(left, right);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    // -------- structural pattern checks --------

    /** {@code (a + b) / c → a}  or  {@code (a - b) / c → a}. */
    private static boolean isFalseCancellationOverSum(Expr before, Expr after) {
        if (!(before instanceof BinaryExpr div) || div.operator() != BinaryOperator.DIV) {
            return false;
        }
        if (!(div.left() instanceof BinaryExpr sum)) {
            return false;
        }
        if (sum.operator() != BinaryOperator.ADD && sum.operator() != BinaryOperator.SUB) {
            return false;
        }
        // result equals (structurally) just the first summand
        return astEquals(after, sum.left());
    }

    /** {@code -(a + b) → -a + b} or {@code -1 * (a + b) → -a + b}. */
    private static boolean isPartialNegationError(Expr before, Expr after) {
        Expr negated = unwrapNegationOfSum(before);
        if (negated == null) {
            return false;
        }
        // negated is the inner sum (a + b)
        if (!(negated instanceof BinaryExpr sum) || sum.operator() != BinaryOperator.ADD) {
            return false;
        }
        // after should look like (-a + b): an ADD whose left is -a and right is b (unchanged!)
        if (!(after instanceof BinaryExpr addAfter) || addAfter.operator() != BinaryOperator.ADD) {
            return false;
        }
        if (!isNegationOf(addAfter.left(), sum.left())) {
            return false;
        }
        return astEquals(addAfter.right(), sum.right());
    }

    /** Returns the inner sum of a unary negation like {@code -X} or {@code -1*X}, else null. */
    private static Expr unwrapNegationOfSum(Expr expression) {
        // Pattern: (0 - X)  — the parser typically encodes -X as 0 - X.
        if (expression instanceof BinaryExpr sub
            && sub.operator() == BinaryOperator.SUB
            && sub.left() instanceof NumberExpr zero
            && zero.value() == 0.0) {
            return sub.right();
        }
        // Pattern: (-1) * X
        if (expression instanceof BinaryExpr mul
            && mul.operator() == BinaryOperator.MUL
            && mul.left() instanceof NumberExpr minusOne
            && minusOne.value() == -1.0) {
            return mul.right();
        }
        return null;
    }

    /** Is {@code candidate} the negation of {@code original}? Accepts {@code 0 - X} or {@code -1 * X}. */
    private static boolean isNegationOf(Expr candidate, Expr original) {
        Expr inner = unwrapNegationOfSum(candidate);
        return inner != null && astEquals(inner, original);
    }

    /** Structural equality on the AST. */
    private static boolean astEquals(Expr a, Expr b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.getClass() != b.getClass()) {
            return false;
        }
        if (a instanceof NumberExpr na && b instanceof NumberExpr nb) {
            return Double.compare(na.value(), nb.value()) == 0;
        }
        if (a instanceof VariableExpr va && b instanceof VariableExpr vb) {
            return va.name().equals(vb.name());
        }
        if (a instanceof BinaryExpr ba && b instanceof BinaryExpr bb) {
            return ba.operator() == bb.operator()
                && astEquals(ba.left(), bb.left())
                && astEquals(ba.right(), bb.right());
        }
        return false;
    }

    private Expr tryParseTerm(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            return parser
                .parse(new InputRequest(InputType.TERM, input))
                .terms()
                .getFirst();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Optional<MisconceptionRule> ruleById(String id) {
        for (MisconceptionRule rule : catalogue) {
            if (rule.id().equals(id)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    // -------- inequality helpers --------

    private static boolean dividedByNegative(Comparator before, Comparator after) {
        // Heuristic: at least one side of `before` contains a "negative coefficient"
        // (like "-2*x" or "-2x"), and the corresponding side of `after` does not.
        boolean hadNeg = looksLikeNegativeCoefficient(before.left)
                      || looksLikeNegativeCoefficient(before.right);
        boolean stillNeg = looksLikeNegativeCoefficient(after.left)
                        || looksLikeNegativeCoefficient(after.right);
        return hadNeg && !stillNeg;
    }

    private static boolean looksLikeNegativeCoefficient(String side) {
        if (side == null) {
            return false;
        }
        String trimmed = side.trim();
        if (!trimmed.startsWith("-")) {
            return false;
        }
        // Need at least "-N<something>" where the trailing "<something>"
        // shows we're looking at a coefficient*variable, not a bare
        // negative constant.
        if (trimmed.length() < 3) {
            return false;
        }
        if (!Character.isDigit(trimmed.charAt(1))) {
            return false;
        }
        // Walk past the digits and check that what follows is `*`, a
        // letter (variable) or `(` — i.e. the negative number multiplies
        // something.
        int i = 2;
        while (i < trimmed.length()
            && (Character.isDigit(trimmed.charAt(i)) || trimmed.charAt(i) == '.')) {
            i++;
        }
        if (i >= trimmed.length()) {
            return false;
        }
        char next = trimmed.charAt(i);
        if (Character.isWhitespace(next)) {
            // skip whitespace
            while (i < trimmed.length() && Character.isWhitespace(trimmed.charAt(i))) {
                i++;
            }
            if (i >= trimmed.length()) {
                return false;
            }
            next = trimmed.charAt(i);
        }
        return next == '*' || next == '(' || Character.isLetter(next);
    }

    /** Lightweight comparator parser that does not depend on the term parser. */
    private record Comparator(String left, String symbol, String right) {
        static Comparator parse(String input) {
            if (input == null) {
                return null;
            }
            // Try longest symbols first so "<=" wins over "<".
            for (String symbol : new String[] {"<=", ">=", "<", ">"}) {
                int idx = input.indexOf(symbol);
                if (idx > 0) {
                    String left  = input.substring(0, idx);
                    String right = input.substring(idx + symbol.length());
                    return new Comparator(left.trim(), symbol, right.trim());
                }
            }
            return null;
        }
    }

    // -------- default catalogue --------

    private static List<MisconceptionRule> defaultCatalogue() {
        List<MisconceptionRule> entries = new ArrayList<>();
        entries.add(new MisconceptionRule(
            "false_cancellation_sum_in_numerator",
            "(a + b) / c -> a",
            "Distributivität fälschlich auf einen Quotienten angewendet — "
                + "es wurde nur der erste Summand des Zählers behalten.",
            "Im Quotienten lässt sich nur ein gemeinsamer Faktor von Zähler "
                + "UND Nenner kürzen. Ein einzelner Summand des Zählers ist kein "
                + "gemeinsamer Faktor und darf nicht 'weggekürzt' werden.",
            "Zerlege den Zähler in einen gemeinsamen Faktor mit dem Nenner, "
                + "z.B. (a + b)/c = a/c + b/c — und kürze erst dann, wenn ein "
                + "gemeinsamer Faktor sichtbar wird."
        ));
        entries.add(new MisconceptionRule(
            "sign_distribution_partial",
            "-(a + b) -> -a + b",
            "Vorzeichen wurde nur auf den ersten Summanden verteilt.",
            "Das Minuszeichen vor einer Klammer wirkt auf jeden Summanden "
                + "innerhalb der Klammer. Es muss also jedes Vorzeichen "
                + "umgedreht werden, nicht nur das erste.",
            "Schreibe -(a + b) zunächst als (-1)·(a + b) und wende dann das "
                + "Distributivgesetz an: (-1)·a + (-1)·b = -a - b."
        ));
        entries.add(new MisconceptionRule(
            "inequality_missing_flip",
            "-2·x < 4  ->  x < -2",
            "Beim Dividieren oder Multiplizieren beider Seiten einer "
                + "Ungleichung mit einer negativen Zahl wurde das "
                + "Vergleichszeichen nicht umgedreht.",
            "Beim Teilen (oder Multiplizieren) einer Ungleichung durch eine "
                + "negative Zahl kippt das Vergleichszeichen: aus '<' wird '>', "
                + "aus '≤' wird '≥' und umgekehrt.",
            "Drehe das Vergleichszeichen um: -2·x < 4 wird zu x > -2, nicht "
                + "x < -2."
        ));
        return entries;
    }
}
