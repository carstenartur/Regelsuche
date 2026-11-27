package de.regelsuche.solver.portfolio;

import static de.regelsuche.solver.portfolio.Z3ProofSyntax.*;
import static de.regelsuche.solver.portfolio.Z3ProofValues.*;

import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.Sort;
import de.regelsuche.solver.ir.SolverIr.Theory;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Checks bounded native equality/ring proofs without invoking an external solver. */
public final class Z3EqualityProofReplay {
    static final int MAX_BYTES = 131_072;
    private Z3EqualityProofReplay() { }

    /** Require a complete contradiction using every supplied premise and the exact negated goal. */
    public static boolean verifies(Obligation obligation, String object) {
        if (obligation == null || object == null || object.length() > MAX_BYTES
                || object.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES
                || !obligation.theories().equals(List.of(Theory.REAL_ARITHMETIC))
                || obligation.assumptions().size() > 128 || obligation.declarations().size() > 128
                || obligation.declarations().stream().anyMatch(d -> d.sort() != Sort.REAL)) return false;
        try {
            var checker = new Checker(obligation);
            for (var premise : obligation.assumptions()) {
                checker.addAssertion(checker.equality(premise.relation(), premise.left(), premise.right()));
            }
            var goal = obligation.goal();
            checker.addAssertion(checker.values.negate(checker.equality(goal.relation(), goal.left(), goal.right())));
            return checker.check(Z3ProofSyntax.parse(object));
        } catch (Invalid | IllegalArgumentException | ArithmeticException unsupported) {
            return false;
        }
    }

    /** Dependencies are the actual asserted leaves used by this checked derivation. */
    private record Step(Formula conclusion, BitSet dependencies) { }

    private static final class Checker {
        private final Map<Formula, Integer> assertions = new HashMap<>();
        private final Map<String, Step> steps = new HashMap<>();
        private final Z3ProofValues values;
        private final Z3PolynomialProofRewrites rewrites;

        Checker(Obligation obligation) {
            values = new Z3ProofValues(obligation.declarations().stream().map(d -> d.name()).collect(Collectors.toSet()));
            rewrites = new Z3PolynomialProofRewrites(obligation, values);
        }

        Equality equality(Relation relation, de.regelsuche.solver.ir.SolverIr.Expression left,
                          de.regelsuche.solver.ir.SolverIr.Expression right) {
            if (relation != Relation.EQUALS) throw new Invalid();
            return values.equality(values.expression(left), values.expression(right));
        }

        void addAssertion(Formula assertion) {
            // Native asserted leaves cannot recover distinct IDs for indistinguishable premises.
            if (assertions.putIfAbsent(assertion, assertions.size()) != null) throw new Invalid();
        }

        boolean check(Node root) {
            var envelope = root.items(2);
            var logic = envelope.get(0).items(2);
            if (!logic.get(0).is("set-logic") || !logic.get(1).is("ALL")) throw new Invalid();
            var proof = envelope.get(1).items(2);
            if (!proof.get(0).is("proof")) throw new Invalid();
            Node body = proof.get(1);
            while (body.head("let")) {
                var let = body.items(3);
                bindParallel(let.get(1));
                body = let.get(2);
            }
            Step result = step(body);
            return result.conclusion() == Truth.FALSE && result.dependencies().cardinality() == assertions.size();
        }

        private void bindParallel(Node node) {
            if (node.children() == null || node.children().isEmpty()) throw new Invalid();
            var names = new HashSet<String>();
            var pendingValues = new HashMap<String, Value>();
            var pendingSteps = new HashMap<String, Step>();
            for (Node binding : node.children()) {
                var pair = binding.items(2);
                String name = pair.get(0).atom();
                if (!names.add(name) || values.bound(name) || steps.containsKey(name)) throw new Invalid();
                if (name.matches("\\?x[0-9]+")) {
                    pendingValues.put(name, asReal(values.value(pair.get(1))));
                } else if (name.matches("\\$x[0-9]+")) {
                    pendingValues.put(name, values.formula(pair.get(1)));
                } else if (name.matches("@x[0-9]+")) {
                    pendingSteps.put(name, step(pair.get(1)));
                } else throw new Invalid();
            }
            // SMT lets are parallel: no RHS can observe a newly introduced sibling.
            values.bindAll(pendingValues);
            steps.putAll(pendingSteps);
        }

        private Step step(Node node) {
            if (node.token() != null) {
                Step bound = steps.get(node.atom());
                if (bound == null) throw new Invalid();
                return bound;
            }
            return switch (node.first()) {
                case "asserted" -> asserted(values.formula(node.items(2).get(1)));
                case "refl" -> reflexivity(node.items(2));
                case "symm" -> symmetry(node.items(3));
                case "trans", "trans*" -> transitivity(node);
                case "monotonicity" -> congruence(node);
                case "rewrite" -> rewrite(node.items(2));
                case "mp" -> modusPonens(node.items(4));
                case "unit-resolution" -> resolve(node.items(4));
                default -> throw new Invalid();
            };
        }

        private Step asserted(Formula value) {
            Integer position = assertions.get(value);
            if (position == null) throw new Invalid();
            var used = new BitSet();
            used.set(position);
            return new Step(value, used);
        }

        private Step reflexivity(List<Node> nodes) {
            Equality equality = asEquality(values.formula(nodes.get(1)));
            if (!equality.left().equals(equality.right())) throw new Invalid();
            return new Step(equality, new BitSet());
        }

        private Step symmetry(List<Node> nodes) {
            Step source = step(nodes.get(1));
            Equality expected = asEquality(source.conclusion()).reverse();
            if (!expected.equals(values.formula(nodes.get(2)))) throw new Invalid();
            return new Step(expected, source.dependencies());
        }

        private Step transitivity(Node node) {
            var chain = node.children();
            if (chain.size() < 4 || node.head("trans") && chain.size() != 4) throw new Invalid();
            Step start = step(chain.get(1));
            Equality first = asEquality(start.conclusion());
            var used = (BitSet) start.dependencies().clone();
            Value end = first.right();
            for (int i = 2; i < chain.size() - 1; i++) {
                Step next = step(chain.get(i));
                Equality link = asEquality(next.conclusion());
                if (!end.equals(link.left())) throw new Invalid();
                end = link.right();
                used.or(next.dependencies());
            }
            Equality expected = values.equality(first.left(), end);
            if (!expected.equals(values.formula(chain.getLast()))) throw new Invalid();
            return new Step(expected, used);
        }

        private Step congruence(Node node) {
            var parts = node.children();
            if (parts.size() < 2) throw new Invalid();
            Equality result = asEquality(values.formula(parts.getLast()));
            if (!sameOperator(result.left(), result.right())) throw new Invalid();
            var left = arguments(result.left());
            var right = arguments(result.right());
            if (left.size() != right.size()) throw new Invalid();
            var covered = new BitSet();
            var used = new BitSet();
            for (int i = 1; i < parts.size() - 1; i++) {
                Step premise = step(parts.get(i));
                Equality equality = asEquality(premise.conclusion());
                boolean relevant = false;
                boolean newChange = false;
                for (int j = 0; j < left.size(); j++) {
                    if (equality.left().equals(left.get(j)) && equality.right().equals(right.get(j))) {
                        newChange |= !covered.get(j) && !left.get(j).equals(right.get(j));
                        covered.set(j);
                        relevant = true;
                    }
                }
                if (!relevant || !premise.dependencies().isEmpty() && !newChange) throw new Invalid();
                used.or(premise.dependencies());
            }
            for (int i = 0; i < left.size(); i++) {
                if (!left.get(i).equals(right.get(i)) && !covered.get(i)) throw new Invalid();
            }
            return new Step(result, used);
        }

        private Step rewrite(List<Node> nodes) {
            Equality result = asEquality(values.formula(nodes.get(1)));
            if (!rewrites.verifies(result)) throw new Invalid();
            return new Step(result, new BitSet());
        }

        private Step modusPonens(List<Node> nodes) {
            Step premise = step(nodes.get(1)), bridge = step(nodes.get(2));
            Equality implication = asEquality(bridge.conclusion());
            Formula result = values.formula(nodes.get(3));
            if (!implication.left().equals(premise.conclusion()) || !implication.right().equals(result)) throw new Invalid();
            return new Step(result, union(premise, bridge));
        }

        private Step resolve(List<Node> nodes) {
            Step a = step(nodes.get(1)), b = step(nodes.get(2));
            boolean opposite = a.conclusion() instanceof Negation n && n.operand().equals(b.conclusion())
                || b.conclusion() instanceof Negation m && m.operand().equals(a.conclusion());
            if (!nodes.get(3).is("false") || !opposite) throw new Invalid();
            return new Step(Truth.FALSE, union(a, b));
        }

        private static BitSet union(Step first, Step second) {
            var result = (BitSet) first.dependencies().clone();
            result.or(second.dependencies());
            return result;
        }

        private static Equality asEquality(Formula formula) {
            if (!(formula instanceof Equality equality)) throw new Invalid();
            return equality;
        }
    }
}
