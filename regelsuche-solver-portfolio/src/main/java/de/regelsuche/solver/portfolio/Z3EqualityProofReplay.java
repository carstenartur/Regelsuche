package de.regelsuche.solver.portfolio;

import de.regelsuche.solver.ir.SolverIr.Expression;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.Sort;
import de.regelsuche.solver.ir.SolverIr.Symbol;
import de.regelsuche.solver.ir.SolverIr.Theory;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Checks a bounded fragment of Z3's native object without invoking any solver. */
public final class Z3EqualityProofReplay {
    static final int MAX_BYTES = 131_072;
    private Z3EqualityProofReplay() {}

    /** Require a complete contradiction using every supplied premise and the exact negated goal. */
    public static boolean verifies(Obligation obligation, String object) {
        if (obligation == null || object == null || object.length() > MAX_BYTES
                || !obligation.theories().equals(List.of(Theory.REAL_ARITHMETIC))
                || obligation.assumptions().size() > 128
                || obligation.declarations().stream().anyMatch(d -> d.sort() != Sort.REAL)) return false;
        try {
            var checker = new Checker();
            for (var premise : obligation.assumptions()) {
                checker.addAssertion(equality(premise.relation(), premise.left(), premise.right()));
            }
            var goal = obligation.goal();
            checker.addAssertion(equality(goal.relation(), goal.left(), goal.right()).negate());
            return checker.check(new Parser(object).read());
        } catch (Invalid unsupported) {
            return false;
        }
    }

    private record Formula(String left, String right, boolean negative) {
        Formula negate() { return new Formula(left, right, !negative); }
        Formula reverse() { return new Formula(right, left, negative); }
    }

    private static Formula equality(Relation relation, Expression left, Expression right) {
        if (relation != Relation.EQUALS || !(left instanceof Symbol a) || !(right instanceof Symbol b)) throw new Invalid();
        return new Formula(a.name(), b.name(), false);
    }

    /** A null conclusion represents false. Dependencies are the actual asserted leaves of this derivation. */
    private record Step(Formula conclusion, BitSet dependencies) {}

    private static final class Checker {
        private final Map<Formula, Integer> assertions = new HashMap<>();
        private final Map<String, Formula> formulas = new HashMap<>();
        private final Map<String, Step> steps = new HashMap<>();

        void addAssertion(Formula assertion) {
            // Indistinguishable duplicate premise IDs cannot be recovered from native asserted leaves.
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
                var binding = let.get(1).items(1).getFirst().items(2);
                bind(binding.get(0).atom(), binding.get(1));
                body = let.get(2);
            }
            Step result = step(body);
            return result.conclusion() == null && result.dependencies().cardinality() == assertions.size();
        }

        private void bind(String name, Node value) {
            if (name.matches("\\$x[0-9]+") && !formulas.containsKey(name)) {
                formulas.put(name, formula(value));
            } else if (name.matches("@x[0-9]+") && !steps.containsKey(name)) {
                steps.put(name, step(value));
            } else {
                throw new Invalid();
            }
        }

        private Formula formula(Node node) {
            if (node.head("=")) {
                var args = node.items(3);
                return new Formula(args.get(1).atom(), args.get(2).atom(), false);
            }
            if (node.head("not")) {
                Formula value = formula(node.items(2).get(1));
                if (value.negative()) throw new Invalid();
                return value.negate();
            }
            Formula bound = formulas.get(node.atom());
            if (bound == null) throw new Invalid();
            return bound;
        }

        private Step step(Node node) {
            if (node.atom != null) {
                Step bound = steps.get(node.atom);
                if (bound == null) throw new Invalid();
                return bound;
            }
            return switch (node.first()) {
                case "asserted" -> asserted(formula(node.items(2).get(1)));
                case "symm" -> symmetry(node.items(3));
                case "trans", "trans*" -> transitivity(node);
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

        private Step symmetry(List<Node> nodes) {
            Step source = step(nodes.get(1));
            Formula expected = positive(source).reverse();
            if (!expected.equals(formula(nodes.get(2)))) throw new Invalid();
            return new Step(expected, source.dependencies());
        }

        private Step transitivity(Node node) {
            var chain = node.children;
            if (chain.size() < 4 || (node.head("trans") && chain.size() != 4)) throw new Invalid();
            Step start = step(chain.get(1));
            Formula first = positive(start);
            var used = (BitSet) start.dependencies().clone();
            String end = first.right();
            for (int i = 2; i < chain.size() - 1; i++) {
                Step next = step(chain.get(i));
                Formula link = positive(next);
                if (!end.equals(link.left())) throw new Invalid();
                end = link.right();
                used.or(next.dependencies());
            }
            var expected = new Formula(first.left(), end, false);
            if (!expected.equals(formula(chain.getLast()))) throw new Invalid();
            return new Step(expected, used);
        }

        private Step resolve(List<Node> nodes) {
            Step a = step(nodes.get(1)), b = step(nodes.get(2));
            if (!nodes.get(3).is("false") || a.conclusion() == null
                    || !a.conclusion().negate().equals(b.conclusion())) throw new Invalid();
            var used = (BitSet) a.dependencies().clone();
            used.or(b.dependencies());
            return new Step(null, used);
        }

        private static Formula positive(Step step) {
            if (step.conclusion() == null || step.conclusion().negative()) throw new Invalid();
            return step.conclusion();
        }
    }

    /** Bounded wire syntax only. Formulas and proof steps have separate namespaces above. */
    private record Node(String atom, List<Node> children) {
        @Override public String atom() {
            if (atom == null) throw new Invalid();
            return atom;
        }
        boolean is(String value) { return value.equals(atom); }
        boolean head(String value) { return children != null && !children.isEmpty() && children.getFirst().is(value); }
        String first() { if (children == null || children.isEmpty()) throw new Invalid(); return children.getFirst().atom(); }
        List<Node> items(int arity) { if (children == null || children.size() != arity) throw new Invalid(); return children; }
    }

    private static final class Parser {
        private final String text;
        private int offset, nodes;

        Parser(String text) { this.text = text; }

        Node read() {
            Node root = read(0);
            space();
            if (offset != text.length()) throw new Invalid();
            return root;
        }

        private Node read(int depth) {
            space();
            if (++nodes > 8192 || depth > 128 || offset == text.length()) throw new Invalid();
            if (text.charAt(offset) != '(') {
                int begin = offset;
                while (offset < text.length() && !delimiter(text.charAt(offset))) offset++;
                if (begin == offset || offset - begin > 256) throw new Invalid();
                return new Node(text.substring(begin, offset), null);
            }
            offset++;
            var children = new ArrayList<Node>();
            space();
            while (offset < text.length() && text.charAt(offset) != ')') {
                children.add(read(depth + 1));
                space();
            }
            if (offset == text.length()) throw new Invalid();
            offset++;
            return new Node(null, children);
        }

        private static boolean delimiter(char c) { return c == '(' || c == ')' || Character.isWhitespace(c); }
        private void space() { while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) offset++; }
    }

    private static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
