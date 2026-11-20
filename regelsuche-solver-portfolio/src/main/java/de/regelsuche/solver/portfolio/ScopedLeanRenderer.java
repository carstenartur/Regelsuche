package de.regelsuche.solver.portfolio;

import static de.regelsuche.solver.ir.SolverIr.Sort.*;

import de.regelsuche.solver.ir.SolverIr.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Typed lowering for solver-obligation/v2. Generated binder names occupy a disjoint namespace. */
final class ScopedLeanRenderer {
    private static final String TACTICS = """
        by
          intros
          solve
          | assumption
          | rfl
          | exact ⟨_, rfl⟩
          | exact tendsto_const_nhds
          | apply HasSum.summable <;> assumption
          | apply Summable.add <;> assumption
          | apply Filter.Tendsto.add <;> assumption
          | simp_all
          | ring
          | positivity
          | nlinarith
          | fun_prop
          | solve_by_elim
          | exact?
        """;
    private record Term(String source, Sort sort) { }
    private final Obligation obligation;
    private final List<String> issues = new ArrayList<>();
    private final Map<String, Sort> free = new HashMap<>();
    private final List<Sort> scope = new ArrayList<>();
    // Keys are typed, fully rendered real terms, whose bound names encode lexical levels.
    private record Guard(String source, boolean positive, boolean nonnegative, boolean nonzero) { }
    private final List<Guard> guards = new ArrayList<>();

    ScopedLeanRenderer(Obligation obligation) { this.obligation = obligation; }

    LeanSourceRenderer.Material render() {
        var mapping = new HashMap<String,String>();
        try {
            if (obligation.declarations().size() > 128 || obligation.assumptions().size() > 128)
                throw new IllegalArgumentException("OBLIGATION_SIZE_LIMIT");
            if (obligation.theories().contains(Theory.INTEGER_ARITHMETIC))
                throw new IllegalArgumentException("UNSUPPORTED_THEORY:INTEGER_ARITHMETIC");
            StringBuilder type = new StringBuilder();
            for (var d : obligation.declarations()) {
                free.put(d.name(), d.sort());
                type.append(" (rs_").append(d.name()).append(" : ").append(sortName(d.sort())).append(")");
            }
            if (!type.isEmpty()) type.insert(0, "∀").append(", ");
            collectGlobalGuards();
            for (var p : obligation.assumptions()) {
                String premise = relation(p.relation(), p.left(), p.right(), null);
                mapping.put("assumption." + p.id(), premise);
                type.append(premise).append(" → ");
            }
            var goal = obligation.goal();
            type.append(relation(goal.relation(), goal.left(), goal.right(), mapping));
            mapping.put("goal.closedType", type.toString());
            return new LeanSourceRenderer.Material(type.toString(), TACTICS, mapping, issues);
        } catch (IllegalArgumentException unsupported) {
            issues.add(unsupported.getMessage());
            return new LeanSourceRenderer.Material("", "", Map.of(), issues);
        }
    }

    private String relation(Relation relation, Expression l, Expression r, Map<String,String> mapping) {
        Term left = term(l, 0);
        if (relation == Relation.HOLDS) {
            expect(left, PROPOSITION);
            if (mapping != null) mapping.put("goal.proposition", left.source);
            return left.source;
        }
        if (relation == Relation.IS_INTEGER) throw new IllegalArgumentException("UNSUPPORTED_RELATION:IS_INTEGER");
        Term right = term(r, 0);
        if (mapping != null) {
            mapping.put("goal.left", left.source);
            mapping.put("goal.right", right.source);
        }
        return compare(switch (relation) {
            case EQUALS -> "="; case NOT_EQUALS -> "≠"; case LESS_THAN -> "<"; case LESS_OR_EQUAL -> "≤";
            case GREATER_THAN -> ">"; case GREATER_OR_EQUAL -> "≥"; default -> throw new IllegalStateException();
        }, left, right).source;
    }

    static String sortName(Sort sort) {
        return switch (sort) {
            case REAL -> "Real"; case NATURAL -> "Nat"; case PROPOSITION -> "Prop"; case REAL_SET -> "Set Real";
            case REAL_SEQUENCE -> "Nat → Real"; case REAL_FUNCTION -> "Real → Real";
            default -> throw new IllegalArgumentException("UNSUPPORTED_SORT:" + sort);
        };
    }

    private Term term(Expression e, int depth) {
        if (depth > 128) throw new IllegalArgumentException("EXPRESSION_DEPTH_LIMIT");
        return switch (e) {
            case Literal l -> new Term(LeanSourceRenderer.literal(l, issues), REAL);
            case Symbol s -> new Term("rs_" + s.name(), free.get(s.name()));
            case Bound b -> {
                int level = scope.size() - 1 - b.index();
                yield new Term("b" + level, scope.get(level));
            }
            case Binder b -> binder(b, depth);
            case Binary b -> {
                var left = expect(term(b.left(), depth + 1), REAL);
                var right = expect(term(b.right(), depth + 1), REAL);
                yield binary(b, left, right);
            }
            case Call c -> application(c, depth);
        };
    }

    private Term binder(Binder binder, int depth) {
        String name = "b" + scope.size();
        String param = name + " : " + sortName(binder.domain());
        scope.add(binder.domain());
        Term body = term(binder.body(), depth + 1);
        scope.removeLast();
        return switch (binder.kind()) {
            case FORALL, EXISTS -> {
                String quantifier = binder.kind() == BinderKind.FORALL ? "∀" : "∃";
                yield new Term("(" + quantifier + " (" + param + "), " + expect(body, PROPOSITION) + ")", PROPOSITION);
            }
            case SET_OF -> {
                require(binder.domain() == REAL, "SET_OF_DOMAIN");
                yield new Term("(setOf (fun (" + param + ") => " + expect(body, PROPOSITION) + "))", REAL_SET);
            }
            case SEQUENCE, FUNCTION -> {
                boolean sequence = binder.kind() == BinderKind.SEQUENCE;
                require(binder.domain() == (sequence ? NATURAL : REAL), "FUNCTION_DOMAIN");
                yield new Term("(fun (" + param + ") => " + expect(body, REAL) + ")", sequence ? REAL_SEQUENCE : REAL_FUNCTION);
            }
        };
    }

    private Term application(Call c, int depth) {
        if (c.function().equals("implies") && c.arguments().size() == 2) {
            var antecedent = term(c.arguments().getFirst(), depth + 1);
            expect(antecedent, PROPOSITION);
            int before = guards.size();
            collectGuards(c.arguments().getFirst(), depth + 1);
            var consequent = term(c.arguments().get(1), depth + 1);
            guards.subList(before, guards.size()).clear();
            return infix("→", antecedent, PROPOSITION, consequent, PROPOSITION, PROPOSITION);
        }
        var args = c.arguments().stream().map(e -> term(e, depth + 1)).toList();
        if (args.size() == 1) return unary(c, args.getFirst());
        if (args.size() == 3 && c.function().equals("limit_at"))
            return new Term("(Filter.Tendsto " + expect(args.get(0), REAL_FUNCTION) + " (nhds " + expect(args.get(1), REAL)
                + ") (nhds " + expect(args.get(2), REAL) + "))", PROPOSITION);
        require(args.size() == 2, "UNSUPPORTED_ARITY:" + c.function());
        return binaryCall(c, args.get(0), args.get(1));
    }

    private Term unary(Call c, Term a) {
        return switch (c.function()) {
            case "summable" -> new Term("(Summable " + expect(a, REAL_SEQUENCE) + ")", PROPOSITION);
            case "tsum" -> new Term("(tsum " + expect(a, REAL_SEQUENCE) + ")", REAL);
            case "not" -> new Term("(¬ " + expect(a, PROPOSITION) + ")", PROPOSITION);
            case "of_nat" -> new Term("((" + expect(a, NATURAL) + ") : Real)", REAL);
            case "succ" -> new Term("(Nat.succ " + expect(a, NATURAL) + ")", NATURAL);
            case "nat" -> {
                if (!(c.arguments().getFirst() instanceof Literal l)) throw new IllegalArgumentException("NAT_LITERAL_REQUIRED");
                BigDecimal n = new BigDecimal(l.value());
                require(n.scale() <= 0 && n.signum() >= 0, "NAT_LITERAL_REQUIRED");
                yield new Term("(" + l.value() + " : Nat)", NATURAL);
            }
            default -> {
                Expression argument = c.arguments().getFirst();
                String source = expect(a, REAL);
                boolean positive = LeanSourceRenderer.positive(argument, obligation)
                    || guards.stream().anyMatch(g -> g.source.equals(source) && g.positive);
                boolean nonnegative = LeanSourceRenderer.nonnegative(argument, obligation)
                    || guards.stream().anyMatch(g -> g.source.equals(source) && g.nonnegative);
                yield new Term(LeanSourceRenderer.realCall(c.function(), argument, source, issues, positive, nonnegative), REAL);
            }
        };
    }

    private Term binaryCall(Call c, Term l, Term r) {
        return switch (c.function()) {
            case "eq" -> compare("=", l, r); case "ne" -> compare("≠", l, r);
            case "le" -> compare("≤", l, r); case "lt" -> compare("<", l, r);
            case "and" -> infix("∧", l, PROPOSITION, r, PROPOSITION, PROPOSITION);
            case "or" -> infix("∨", l, PROPOSITION, r, PROPOSITION, PROPOSITION);
            case "mem" -> infix("∈", l, REAL, r, REAL_SET, PROPOSITION);
            case "subset" -> infix("⊆", l, REAL_SET, r, REAL_SET, PROPOSITION);
            case "union" -> infix("∪", l, REAL_SET, r, REAL_SET, REAL_SET);
            case "inter" -> infix("∩", l, REAL_SET, r, REAL_SET, REAL_SET);
            case "at" -> {
                require(l.sort == REAL_SEQUENCE || l.sort == REAL_FUNCTION, "FUNCTION_REQUIRED");
                yield new Term("(" + l.source + " " + expect(r, l.sort == REAL_SEQUENCE ? NATURAL : REAL) + ")", REAL);
            }
            case "has_sum" -> new Term("(HasSum " + expect(l, REAL_SEQUENCE) + " " + expect(r, REAL) + ")", PROPOSITION);
            case "tends_to" -> new Term("(Filter.Tendsto " + expect(l, REAL_SEQUENCE) + " Filter.atTop (nhds "
                + expect(r, REAL) + "))", PROPOSITION);
            case "pow" -> binary(new Binary(BinaryOperator.POWER, c.arguments().get(0), c.arguments().get(1)),
                expect(l, REAL), expect(r, REAL));
            default -> throw new IllegalArgumentException("UNSUPPORTED_CALL:" + c.function());
        };
    }

    private Term binary(Binary b, String left, String right) {
        boolean positive = LeanSourceRenderer.positive(b.left(), obligation)
            || guards.stream().anyMatch(g -> g.source.equals(left) && g.positive);
        boolean nonzero = LeanSourceRenderer.nonzero(b.right(), obligation)
            || guards.stream().anyMatch(g -> g.source.equals(right) && g.nonzero);
        return new Term(LeanSourceRenderer.binary(b, issues, left, right, positive, nonzero), REAL);
    }

    private void collectGlobalGuards() {
        // Premise identifiers order the wire representation, not the domain conditions.
        // Type-check guard operands now. Recheck all their partial-operation domains below
        // when rendering every full premise with the complete set of global facts.
        for (var p : obligation.assumptions()) {
            if (p.relation() == Relation.HOLDS) collectGuards(p.left(), 0);
        }
        issues.clear();
    }

    private void collectGuards(Expression condition, int depth) {
        if (!(condition instanceof Call c) || c.arguments().size() != 2) return;
        var left = c.arguments().get(0);
        var right = c.arguments().get(1);
        if (c.function().equals("and")) {
            collectGuards(left, depth + 1);
            collectGuards(right, depth + 1);
            return;
        }
        boolean less = c.function().equals("lt"), le = c.function().equals("le"), ne = c.function().equals("ne");
        if (!(less || le || ne)) return;
        boolean zeroLeft = LeanSourceRenderer.zero(left);
        if (!zeroLeft && !LeanSourceRenderer.zero(right)) return;
        Term guarded = term(zeroLeft ? right : left, depth + 1);
        if (guarded.sort == REAL)
            guards.add(new Guard(guarded.source, zeroLeft && less, zeroLeft && (less || le), less || ne));
    }

    private static Term compare(String op, Term l, Term r) {
        require(op.equals("=") || op.equals("≠") || l.sort == REAL || l.sort == NATURAL, "NUMERIC_ORDER_REQUIRED");
        return infix(op, l, l.sort, r, l.sort, PROPOSITION);
    }

    private static Term infix(String op, Term l, Sort left, Term r, Sort right, Sort result) {
        return new Term("(" + expect(l, left) + " " + op + " " + expect(r, right) + ")", result);
    }

    private static String expect(Term term, Sort sort) {
        require(term.sort == sort, "SORT_MISMATCH:" + term.sort + ":" + sort);
        return term.source;
    }

    private static void require(boolean ok, String reason) {
        if (!ok) throw new IllegalArgumentException(reason);
    }
}
