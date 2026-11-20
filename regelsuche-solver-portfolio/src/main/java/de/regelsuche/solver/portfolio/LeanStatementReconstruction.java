package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverIr.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

/**
 * Second lowering of v2 IR used only by the pinned rebuild. Deliberately independent of the
 * tactic/source renderer: close the conclusion backwards, give every variable a different
 * name, and use explicit constructors. Lean checks definitional equality with the first term.
 */
final class LeanStatementReconstruction {
    private LeanStatementReconstruction() { }

    static String theorem(Obligation o) {
        String type = relation(o.goal().relation(), o.goal().left(), o.goal().right());
        for (var p : o.assumptions().reversed())
            type = "(" + relation(p.relation(), p.left(), p.right()) + " → " + type + ")";
        for (var d : o.declarations().reversed())
            type = "(∀ (free_" + d.name() + " : " + sort(d.sort()) + "), " + type + ")";
        return "\ntheorem regelsuche_reconstructed : " + type + " := @regelsuche_lemma\n";
    }

    private static String sort(Sort s) {
        return switch (s) {
            case REAL -> "ℝ"; case NATURAL -> "ℕ"; case REAL_SET -> "Set ℝ";
            case PROPOSITION -> "Prop"; case REAL_SEQUENCE -> "ℕ → ℝ"; case REAL_FUNCTION -> "ℝ → ℝ";
            default -> throw new IllegalArgumentException("unsupported reconstructed sort " + s);
        };
    }

    private static String relation(Relation relation, Expression left, Expression right) {
        String lhs = term(left, 0);
        if (relation == Relation.HOLDS) return lhs;
        String rhs = term(right, 0);
        return switch (relation) {
            case EQUALS -> apply("Eq", lhs, rhs); case NOT_EQUALS -> apply("Ne", lhs, rhs);
            case LESS_OR_EQUAL -> apply("LE.le", lhs, rhs); case LESS_THAN -> apply("LT.lt", lhs, rhs);
            case GREATER_OR_EQUAL -> apply("LE.le", rhs, lhs); case GREATER_THAN -> apply("LT.lt", rhs, lhs);
            default -> throw new IllegalArgumentException("unsupported reconstructed relation " + relation);
        };
    }

    private static String term(Expression e, int level) {
        return switch (e) {
            case Literal l -> real(l);
            case Symbol s -> "free_" + s.name();
            case Bound b -> "local_" + (level - b.index() - 1);
            case Binary b -> arithmetic(b.operator(), term(b.left(), level), term(b.right(), level), b.right());
            case Binder b -> {
                String variable = "(local_" + level + " : " + sort(b.domain()) + ")";
                String body = term(b.body(), level + 1);
                yield switch (b.kind()) {
                    case FORALL -> "(∀ " + variable + ", " + body + ")";
                    case EXISTS -> "(Exists (fun " + variable + " => " + body + "))";
                    case SET_OF -> "((fun " + variable + " => " + body + ") : Set ℝ)";
                    case SEQUENCE, FUNCTION -> "(fun " + variable + " => " + body + ")";
                };
            }
            case Call c -> call(c, c.arguments().stream().map(a -> term(a, level)).toList());
        };
    }

    private static String real(Literal l) {
        BigDecimal d = new BigDecimal(l.value());
        if (d.scale() <= 0) return "(" + d.toBigIntegerExact() + " : ℝ)";
        return "((" + d.unscaledValue() + " : ℝ) / " + BigInteger.TEN.pow(d.scale()) + ")";
    }

    private static String arithmetic(BinaryOperator op, String l, String r, Expression exponent) {
        return switch (op) {
            case ADD -> apply("HAdd.hAdd", l, r); case MULTIPLY -> apply("HMul.hMul", l, r);
            case SUBTRACT -> apply("HSub.hSub", l, r); case DIVIDE -> apply("HDiv.hDiv", l, r);
            case POWER -> {
                if (exponent instanceof Literal literal) {
                    BigDecimal d = new BigDecimal(literal.value());
                    if (d.scale() <= 0 && d.signum() >= 0) yield "(" + l + " ^ (" + d.toBigIntegerExact() + " : ℕ))";
                }
                yield apply("Real.rpow", l, r);
            }
        };
    }

    private static String call(Call c, List<String> args) {
        String a = args.getFirst();
        if (args.size() == 1) {
            return switch (c.function()) {
                case "nat" -> "(" + ((Literal)c.arguments().getFirst()).value() + " : ℕ)";
                case "of_nat" -> "(" + a + " : ℝ)";
                case "succ" -> apply("Nat.succ", a); case "not" -> apply("Not", a);
                case "summable" -> apply("Summable", a); case "tsum" -> apply("tsum", a);
                case "exp" -> apply("Real.exp", a); case "log", "ln" -> apply("Real.log", a);
                case "sqrt" -> apply("Real.sqrt", a); case "abs" -> apply("abs", a);
                default -> throw new IllegalArgumentException("unsupported reconstructed call " + c.function());
            };
        }
        String b = args.get(1);
        return switch (c.function()) {
            case "eq" -> apply("Eq", a, b); case "ne" -> apply("Ne", a, b);
            case "lt" -> apply("LT.lt", a, b); case "le" -> apply("LE.le", a, b);
            case "and" -> apply("And", a, b); case "or" -> apply("Or", a, b);
            case "implies" -> "(" + a + " → " + b + ")";
            case "at" -> apply(a, b); case "has_sum" -> apply("HasSum", a, b);
            case "tends_to" -> apply("Filter.Tendsto", a, "Filter.atTop", apply("nhds", b));
            case "limit_at" -> apply("Filter.Tendsto", a, apply("nhds", b), apply("nhds", args.get(2)));
            case "mem" -> "(" + a + " ∈ " + b + ")"; case "subset" -> "(" + a + " ⊆ " + b + ")";
            case "union" -> "(" + a + " ∪ " + b + ")"; case "inter" -> "(" + a + " ∩ " + b + ")";
            case "pow" -> arithmetic(BinaryOperator.POWER, a, b, c.arguments().get(1));
            default -> throw new IllegalArgumentException("unsupported reconstructed call " + c.function());
        };
    }

    private static String apply(String function, String... args) {
        return "(" + function + " " + String.join(" ", args) + ")";
    }
}
