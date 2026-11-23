package de.regelsuche.solver.ir;

import de.regelsuche.solver.ir.SolverIr.Obligation;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/** Bounded proof proposals over a v2 obligation. Mathematical typing belongs to the checked backend. */
public final class ScopedProofDag {
    public enum Rule {
        DECLARATION(0), PREMISE(0), LOCAL(0), ZERO(0), SUCC(1),
        INTRO(1), APPLY(2), SPECIALIZE(2), HAVE(2), LET(2), UNFOLD(1),
        PAIR(2), AND_LEFT(1), AND_RIGHT(1), WITNESS(2), EXISTS_ELIM(2),
        CASES(3), BY_CONTRADICTION(2), REWRITE(2),
        INDUCTION(2), STRONG_INDUCTION(1), DECREASE(2), NORMALIZE(0), SOLVE_LEAF(0);
        private final int arity;
        Rule(int arity) { this.arity = arity; }
    }
    public enum Local { VARIABLE, DEFINITION, SMALLER_CASES }
    public record Node(Rule rule, int operand, List<Integer> children) {
        public Node {
            Objects.requireNonNull(rule);
            children = List.copyOf(children);
            if (children.size() != rule.arity) throw new IllegalArgumentException("wrong proof-rule arity");
            boolean indexed = switch (rule) {
                case DECLARATION, PREMISE, LOCAL, UNFOLD, INDUCTION -> true;
                default -> false;
            };
            if (indexed ? operand < 0 : operand != -1) throw new IllegalArgumentException("invalid proof-rule operand");
        }
        public static Node of(Rule rule, int... children) { return indexed(rule, -1, children); }
        public static Node indexed(Rule rule, int operand, int... children) {
            return new Node(rule, operand, java.util.Arrays.stream(children).boxed().toList());
        }
        public static Node local(int index) { return indexed(Rule.LOCAL, index); }
        public static Node premise(int index) { return indexed(Rule.PREMISE, index); }
    }

    public interface Fold<T> { T apply(Node node, List<Local> scope, List<T> children); }

    public static final String SCHEMA = "regelsuche.scoped-proof-dag/v1";
    private final Obligation obligation;
    private final List<Node> nodes;

    private ScopedProofDag(Obligation obligation, List<Node> nodes) {
        if (!SolverIr.SCOPED_OBLIGATION_SCHEMA.equals(obligation.schema()) || nodes.isEmpty() || nodes.size() > 128)
            throw new IllegalArgumentException("scoped DAG requires a bounded v2 obligation");
        this.obligation = obligation;
        this.nodes = List.copyOf(nodes);
        fold((node, scope, children) -> Boolean.TRUE);
    }
    public static ScopedProofDag create(Obligation obligation, List<Node> nodes) { return new ScopedProofDag(obligation, nodes); }
    public Obligation obligation() { return obligation; }
    public List<Node> nodes() { return nodes; }
    public String hash() { return SolverIr.sha256(canonical()); }

    public <T> T fold(Fold<T> operator) {
        var observed = new BitSet(nodes.size());
        T result = visit(nodes.size()-1, List.of(), operator, new int[]{0}, observed);
        if (observed.cardinality() != nodes.size()) throw new IllegalArgumentException("unreachable proof nodes");
        return result;
    }
    private <T> T visit(int i, List<Local> scope, Fold<T> fold, int[] work, BitSet observed) {
        if (++work[0] > 512 || scope.size() > 64) throw new IllegalArgumentException("proof expansion budget exhausted");
        observed.set(i);
        Node node = nodes.get(i);
        checkOperand(node, scope);
        var values = new ArrayList<T>();
        for (int n = 0; n < node.children().size(); n++) {
            int child = node.children().get(n);
            if (child < 0 || child >= i) throw new IllegalArgumentException("non-topological proof edge");
            values.add(visit(child, childScope(scope, node.rule(), n), fold, work, observed));
        }
        return fold.apply(node, scope, List.copyOf(values));
    }

    private void checkOperand(Node node, List<Local> scope) {
        int index = node.operand();
        boolean valid = switch (node.rule()) {
            case DECLARATION -> index < obligation.declarations().size();
            case PREMISE -> index < obligation.assumptions().size();
            case INDUCTION -> index < obligation.declarations().size()
                && obligation.declarations().get(index).sort() == SolverIr.Sort.NAT_PREDICATE;
            case LOCAL -> index < scope.size() && scope.get(index) != Local.SMALLER_CASES;
            case UNFOLD -> index < scope.size() && scope.get(index) == Local.DEFINITION;
            case DECREASE -> scope.contains(Local.SMALLER_CASES);
            default -> true;
        };
        if (!valid) throw new IllegalArgumentException("unavailable or incorrectly scoped proof operand");
    }

    private static List<Local> childScope(List<Local> scope, Rule rule, int child) {
        return switch (rule) {
            case INTRO, BY_CONTRADICTION -> extend(scope, Local.VARIABLE);
            case HAVE, CASES -> child == 0 ? scope : extend(scope, Local.VARIABLE);
            // The continuation has the witness property at index 0 and the witness at index 1.
            case EXISTS_ELIM -> child == 0 ? scope : extend(extend(scope, Local.VARIABLE), Local.VARIABLE);
            case LET -> child == 0 ? scope : extend(scope, Local.DEFINITION);
            case STRONG_INDUCTION -> extend(extend(scope, Local.VARIABLE), Local.SMALLER_CASES);
            default -> scope;
        };
    }
    private static List<Local> extend(List<Local> scope, Local local) {
        var next = new ArrayList<Local>();
        next.add(local); next.addAll(scope);
        return List.copyOf(next);
    }

    public String canonical() {
        var result = new StringBuilder(SCHEMA).append('\n').append(obligation.contentHash()).append('\n');
        for (var node : nodes) {
            result.append(node.rule()).append(' ').append(node.operand());
            for (int edge : node.children()) result.append(' ').append(edge);
            result.append('\n');
        }
        return result.toString();
    }

    public static ScopedProofDag read(Obligation obligation, String text) {
        if (text.length() > 16_384) throw new IllegalArgumentException("proof document exceeds limit");
        var lines = text.lines().toList();
        if (lines.size() < 3 || lines.size() > 130 || !lines.get(0).equals(SCHEMA)
                || !lines.get(1).equals(obligation.contentHash())) throw new IllegalArgumentException("proof obligation differs");
        var nodes = new ArrayList<Node>();
        for (String line : lines.subList(2,lines.size())) {
            var fields = line.split(" ", -1);
            if (fields.length < 2) throw new IllegalArgumentException("malformed proof node");
            var rule = Rule.valueOf(fields[0]);
            var numbers = java.util.Arrays.stream(fields,1,fields.length).map(Integer::valueOf).toList();
            nodes.add(new Node(rule,numbers.getFirst(),numbers.subList(1,numbers.size())));
        }
        var dag = create(obligation, nodes);
        if (!dag.canonical().equals(text)) throw new IllegalArgumentException("noncanonical proof document");
        return dag;
    }
    @Override public boolean equals(Object o) { return o instanceof ScopedProofDag d && obligation.equals(d.obligation) && nodes.equals(d.nodes); }
    @Override public int hashCode() { return Objects.hash(obligation, nodes); }
}
