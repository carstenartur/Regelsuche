package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverIr;
import de.regelsuche.solver.ir.SolverIr.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Lossless real-expression translation; never invents axioms or opaque analytic functions. */
public final class LeanSourceRenderer {
    private static final String TACTICS = """
          solve
          | simp_all [Real.log_exp, Real.exp_log, Real.exp_add, Real.log_mul,
              Real.log_div, Real.rpow_zero, Real.rpow_one]
          | ring
          | field_simp <;> ring
          | positivity
          | nlinarith
        """;

    public record Material(String theoremType, String proof, Map<String, String> mapping,
                           List<String> issues) {
        public Material {
            mapping = Map.copyOf(mapping);
            issues = List.copyOf(issues);
        }
        public boolean supported() { return issues.isEmpty(); }
    }

    public Material render(Obligation obligation) {
        List<String> issues = new ArrayList<>();
        if (obligation.declarations().size() > 128 || obligation.assumptions().size() > 128) {
            return new Material("", "", Map.of(), List.of("OBLIGATION_SIZE_LIMIT"));
        }
        for (Theory theory : obligation.theories()) {
            if (theory != Theory.REAL_ARITHMETIC && theory != Theory.TRANSCENDENTAL_FUNCTIONS)
                issues.add("UNSUPPORTED_THEORY:" + theory);
        }
        StringBuilder binders = new StringBuilder();
        for (SymbolDeclaration declaration : obligation.declarations()) {
            if (declaration.sort() != Sort.REAL) issues.add("UNSUPPORTED_SORT:" + declaration.sort());
            binders.append(" (rs_").append(declaration.name()).append(" : Real)");
        }
        StringBuilder body = new StringBuilder();
        for (Predicate predicate : obligation.assumptions()) {
            body.append(relation(predicate.relation(), predicate.left(), predicate.right(),
                obligation, issues)).append(" → ");
        }
        body.append(relation(obligation.goal().relation(), obligation.goal().left(),
            obligation.goal().right(), obligation, issues));
        String type = (binders.isEmpty() ? "" : "∀" + binders + ", ") + body;
        String intros = (binders.isEmpty() && obligation.assumptions().isEmpty()) ? "" : "  intros\n";
        return new Material(type, "by\n" + intros + TACTICS,
            Map.of("goal.closedType", type), issues.stream().distinct().sorted().toList());
    }

    private static String relation(Relation relation, Expression left, Expression right,
                                   Obligation obligation, List<String> issues) {
        if (relation == Relation.IS_INTEGER) {
            issues.add("UNSUPPORTED_RELATION:IS_INTEGER");
            return "False";
        }
        String op = switch (relation) {
            case EQUALS -> "=";
            case NOT_EQUALS -> "≠";
            case LESS_THAN -> "<";
            case LESS_OR_EQUAL -> "≤";
            case GREATER_THAN -> ">";
            case GREATER_OR_EQUAL -> "≥";
            case IS_INTEGER -> throw new IllegalStateException();
        };
        return "(" + expression(left, obligation, issues, 0) + " " + op + " "
            + expression(right, obligation, issues, 0) + ")";
    }

    private static String expression(Expression expression, Obligation obligation,
                                     List<String> issues, int depth) {
        if (depth > 128) { issues.add("EXPRESSION_DEPTH_LIMIT"); return "0"; }
        if (expression instanceof Literal literal) {
            if (literal.value().length() > 4096) { issues.add("LITERAL_SIZE_LIMIT"); return "0"; }
            BigDecimal d = new BigDecimal(literal.value());
            if (d.scale() <= 0) return "(" + d.toBigIntegerExact() + " : Real)";
            return "((" + d.unscaledValue() + " : Real) / " + java.math.BigInteger.TEN.pow(d.scale()) + ")";
        }
        if (expression instanceof Symbol symbol) return "rs_" + symbol.name();
        if (expression instanceof Binary b) {
            String left = expression(b.left(), obligation, issues, depth + 1);
            String right = expression(b.right(), obligation, issues, depth + 1);
            if (b.operator() == BinaryOperator.DIVIDE && !nonzero(b.right(), obligation))
                issues.add("DIVISION_DOMAIN_NOT_ENCODED:" + b.right().canonicalMaterial());
            if (b.operator() == BinaryOperator.POWER) {
                if (b.right() instanceof Literal l) {
                    BigDecimal d = new BigDecimal(l.value());
                    if (d.signum() >= 0 && d.stripTrailingZeros().scale() <= 0)
                        return "(" + left + " ^ (" + d.toBigIntegerExact() + " : Nat))";
                }
                if (!positive(b.left(), obligation)) issues.add("REAL_POWER_POSITIVE_BASE_REQUIRED");
                return "(Real.rpow " + left + " " + right + ")";
            }
            String operator = switch (b.operator()) {
                case ADD -> "+"; case SUBTRACT -> "-"; case MULTIPLY -> "*"; case DIVIDE -> "/";
                case POWER -> throw new IllegalStateException();
            };
            return "(" + left + " " + operator + " " + right + ")";
        }
        Call call = (Call) expression;
        int arity = call.function().equals("pow") ? 2 : 1;
        if (call.arguments().size() != arity) { issues.add("UNSUPPORTED_ARITY:" + call.function()); return "0"; }
        Expression argument = call.arguments().get(0);
        String a = expression(argument, obligation, issues, depth + 1);
        return switch (call.function()) {
            case "exp" -> "(Real.exp " + a + ")";
            case "log", "ln" -> {
                if (!positive(argument, obligation)) issues.add("LOG_POSITIVE_ARGUMENT_REQUIRED:" + argument.canonicalMaterial());
                yield "(Real.log " + a + ")";
            }
            case "sqrt" -> {
                if (!nonnegative(argument, obligation)) issues.add("SQRT_NONNEGATIVE_ARGUMENT_REQUIRED");
                yield "(Real.sqrt " + a + ")";
            }
            case "abs" -> "(abs " + a + ")";
            case "pow" -> expression(new Binary(BinaryOperator.POWER, argument,
                call.arguments().get(1)), obligation, issues, depth + 1);
            default -> { issues.add("UNSUPPORTED_CALL:" + call.function()); yield "0"; }
        };
    }

    private static boolean zero(Expression expression) {
        return expression instanceof Literal l && new BigDecimal(l.value()).signum() == 0;
    }
    private static boolean positive(Expression e, Obligation o) {
        if (e instanceof Literal l) return new BigDecimal(l.value()).signum() > 0;
        if (e instanceof Call c && c.function().equals("exp") && c.arguments().size() == 1) return true;
        return o.assumptions().stream().anyMatch(p ->
            p.relation() == Relation.GREATER_THAN && p.left().equals(e) && zero(p.right())
            || p.relation() == Relation.LESS_THAN && p.right().equals(e) && zero(p.left()));
    }
    private static boolean nonnegative(Expression e, Obligation o) {
        if (positive(e,o) || zero(e)) return true;
        return o.assumptions().stream().anyMatch(p ->
            p.relation() == Relation.GREATER_OR_EQUAL && p.left().equals(e) && zero(p.right())
            || p.relation() == Relation.LESS_OR_EQUAL && p.right().equals(e) && zero(p.left()));
    }
    private static boolean nonzero(Expression e, Obligation o) {
        if (positive(e,o)) return true;
        if (e instanceof Literal l) return new BigDecimal(l.value()).signum() != 0;
        return o.assumptions().stream().anyMatch(p ->
            (p.relation() == Relation.NOT_EQUALS || p.relation() == Relation.LESS_THAN
             || p.relation() == Relation.GREATER_THAN)
             && (p.left().equals(e) && zero(p.right()) || e.equals(p.right()) && zero(p.left())));
    }

    public String source(Obligation obligation, String nonce) {
        Material material = render(obligation);
        if (!material.supported()) throw new IllegalArgumentException(String.join(",", material.issues()));
        return "import Mathlib\nimport Lean.Util.CollectAxioms\n"
            + "set_option autoImplicit false\nset_option Elab.async false\nset_option maxHeartbeats 1000000\n"
            + "theorem regelsuche_lemma : " + material.theoremType() + " := " + material.proof()
            + audit(material.theoremType(), obligation.contentHash(), nonce);
    }

    /** The wrapper kernel-checks the exact closed request, not merely the printed goal. */
    public String audit(String expectedType, String obligationHash, String nonce) {
        if (!obligationHash.matches("sha256:[0-9a-f]{64}") || !nonce.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("invalid audit binding");
        return "\ntheorem regelsuche_bound : " + expectedType + " := @regelsuche_lemma\n"
            + "open Lean Elab Command in\nrun_cmd do\n"
            + "  let env ← getEnv\n"
            + "  match env.find? `regelsuche_bound with\n"
            + "  | some (.thmInfo _) => pure ()\n"
            + "  | _ => throwError \"expected a checked theorem\"\n"
            + "  let axioms ← Lean.collectAxioms `regelsuche_bound\n"
            + "  let allowed := #[`propext, `Classical.choice, `Quot.sound]\n"
            + "  for ax in axioms do\n"
            + "    unless allowed.contains ax do\n"
            + "      throwError m!\"unapproved proof axiom: {ax}\"\n"
            + "  let report := Json.mkObj [(\"schema\", toJson \"regelsuche.lean-audit/v1\"),\n"
            + "    (\"obligationHash\", toJson \"" + obligationHash + "\"),\n"
            + "    (\"nonce\", toJson \"" + nonce + "\"),\n"
            + "    (\"theorem\", toJson \"regelsuche_bound\"),\n"
            + "    (\"axioms\", toJson (axioms.map Name.toString))]\n"
            + "  liftIO <| IO.println (\"REGELSUCHE_LEAN_AUDIT \" ++ report.compress)\n";
    }
}
