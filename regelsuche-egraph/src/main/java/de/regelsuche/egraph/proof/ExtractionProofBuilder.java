package de.regelsuche.egraph.proof;

import static de.regelsuche.egraph.proof.ExtractionCertificate.*;

import de.regelsuche.ast.*;
import de.regelsuche.transform.RewriteRule;

import java.util.*;

/** Finite ground congruence producer. Its output still requires independent replay. */
public final class ExtractionProofBuilder {
    private final ProofSupport syntax;
    private final List<GroundInstance> instances = new ArrayList<>();

    public ExtractionProofBuilder(Collection<? extends RewriteRule> rules, ProofLimits limits) {
        syntax = new ProofSupport(limits);
        try {
            syntax.registry(ProofSupport.nonNull(rules));
        } catch (LimitExceeded exhausted) {
            throw new LimitExceeded(exhausted.getMessage(), syntax.work, exhausted);
        } catch (UnsupportedRule unsupported) {
            throw new UnsupportedRule(unsupported.getMessage(), syntax.work, unsupported);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidInput(invalid.getMessage(), syntax.work, invalid);
        }
    }

    public static final class LimitExceeded extends RuntimeException {
        private final long workUsed;

        public LimitExceeded() {
            this("proof limit exceeded");
        }

        public LimitExceeded(String detail) {
            this(detail, 0, null);
        }

        private LimitExceeded(String detail, long workUsed, Throwable cause) {
            super(detail, cause);
            this.workUsed = workUsed;
        }

        /**
         * Consumed registry work when construction failed; otherwise use the live builder counter.
         */
        public long workUsed() {
            return workUsed;
        }
    }

    public static final class UnsupportedRule extends IllegalArgumentException {
        private final long workUsed;

        public UnsupportedRule(String detail) {
            this(detail, 0, null);
        }

        private UnsupportedRule(String detail, long workUsed, Throwable cause) {
            super(detail, cause);
            this.workUsed = workUsed;
        }

        /** Consumed registry work when construction failed. */
        public long workUsed() {
            return workUsed;
        }
    }

    /** Malformed registry input, retaining work even though no builder was returned. */
    public static final class InvalidInput extends IllegalArgumentException {
        private final long workUsed;

        private InvalidInput(String detail, long workUsed, Throwable cause) {
            super(detail, cause);
            this.workUsed = workUsed;
        }

        public long workUsed() {
            return workUsed;
        }
    }

    public record GroundInstance(int rule, Map<String, Integer> bindings, int before, int after) {
        public GroundInstance {
            bindings = Map.copyOf(bindings);
        }
    }

    public enum BuildStatus {
        BUILT,
        LIMIT_EXCEEDED,
        NO_DERIVATION
    }

    public record BuildResult(
            BuildStatus status,
            Optional<ExtractionCertificate> certificate,
            String detail,
            long workUsed) {
        public BuildResult {
            Objects.requireNonNull(status);
            Objects.requireNonNull(certificate);
            Objects.requireNonNull(detail);
            if ((status == BuildStatus.BUILT) != certificate.isPresent() || workUsed < 0)
                throw new IllegalArgumentException("inconsistent build result");
        }
    }

    public long workUsed() {
        return syntax.work;
    }

    /** Shares this producer's work envelope with its bounded integration adapter. */
    public void chargeWork(long units) {
        syntax.work(units);
    }

    public int addTerm(Term term) {
        return syntax.intern(term);
    }

    public int addExpression(Expr expression) {
        return syntax.expression(expression);
    }

    public Term term(int term) {
        syntax.work(1);
        return syntax.terms.get(syntax.requireTerm(term));
    }

    public Expr materialize(int term) {
        syntax.requireTerm(term);
        syntax.work(syntax.expanded.get(term));
        var values = new HashMap<Integer, Expr>();
        var stack = new ArrayDeque<Integer>();
        stack.push(term);
        while (!stack.isEmpty()) {
            syntax.work(1);
            int id = stack.peek();
            if (values.containsKey(id)) {
                stack.pop();
                continue;
            }
            var t = syntax.terms.get(id);
            boolean ready = true;
            for (int c : t.children()) {
                syntax.work(1);
                if (!values.containsKey(c)) {
                    stack.push(c);
                    ready = false;
                }
            }
            if (!ready) continue;
            var children = new ArrayList<Expr>();
            for (int c : t.children()) {
                syntax.work(1);
                children.add(values.get(c));
            }
            syntax.work(t.payload().length());
            Expr e =
                    switch (t.kind()) {
                        case NUMBER -> new NumberExpr(syntax.number(t.payload()));
                        case VARIABLE -> new VariableExpr(t.payload());
                        case BINARY ->
                                new BinaryExpr(
                                        children.get(0),
                                        BinaryOperator.valueOf(t.payload()),
                                        children.get(1));
                        case FUNCTION -> new FunctionExpr(t.payload(), children);
                    };
            values.put(id, e);
            stack.pop();
        }
        return values.get(term);
    }

    public GroundInstance prepareInstance(String ruleId, Map<String, Integer> bindings) {
        syntax.work(1);
        ProofSupport.nonNull(ruleId);
        syntax.work(ruleId.length());
        Integer index = syntax.ruleIds.get(ruleId);
        if (index == null) throw new IllegalArgumentException("unknown rule");
        var r = syntax.rules.get(index);
        syntax.bindings(r, bindings);
        syntax.refs(bindings.size());
        for (String name : bindings.keySet()) syntax.chars(name);
        return new GroundInstance(
                index,
                bindings,
                syntax.instantiate(r.source(), bindings, true),
                syntax.instantiate(r.target(), bindings, true));
    }

    public void recordInstance(GroundInstance instance) {
        syntax.work(1);
        ProofSupport.nonNull(instance);
        if (instance.rule() < 0 || instance.rule() >= syntax.rules.size())
            throw new IllegalArgumentException("unknown rule");
        var r = syntax.rules.get(instance.rule());
        syntax.bindings(r, instance.bindings());
        if (syntax.instantiate(r.source(), instance.bindings(), false) != instance.before()
                || syntax.instantiate(r.target(), instance.bindings(), false) != instance.after())
            throw new IllegalArgumentException("invalid ground instance");
        syntax.size(instances.size() + 1L, syntax.limits.maxProofSteps(), "instances limit");
        syntax.refs(instance.bindings().size() + 2L);
        // Public instances need not have passed through prepareInstance. Charge every retained map.
        for (String name : instance.bindings().keySet()) syntax.chars(name);
        instances.add(instance);
    }

    public BuildResult build(int inputTerm, int outputTerm) {
        syntax.requireTerm(inputTerm);
        syntax.requireTerm(outputTerm);
        try {
            return new Closure().build(inputTerm, outputTerm);
        } catch (LimitExceeded limit) {
            return new BuildResult(
                    BuildStatus.LIMIT_EXCEEDED, Optional.empty(), limit.getMessage(), workUsed());
        }
    }

    private final class Closure {
        final List<Step> steps = new ArrayList<>();
        final int[] parent;
        final List<List<Edge>> forest = new ArrayList<>();

        record Edge(int to, int proof, boolean reverse) {}

        Closure() {
            syntax.work(syntax.terms.size());
            parent = new int[syntax.terms.size()];
            for (int i = 0; i < parent.length; i++) {
                parent[i] = i;
                forest.add(new ArrayList<>());
            }
        }

        int root(int i) {
            while (parent[i] != i) {
                syntax.work(1);
                i = parent[i];
            }
            syntax.work(1);
            return i;
        }

        int emit(Step step, int references) {
            syntax.work(1);
            syntax.size(steps.size() + 1L, syntax.limits.maxProofSteps(), "proof steps");
            syntax.refs(references);
            steps.add(step);
            return steps.size() - 1;
        }

        boolean connect(int a, int b, int proof) {
            int x = root(a), y = root(b);
            if (x == y) return false;
            syntax.refs(2);
            parent[x] = y;
            forest.get(a).add(new Edge(b, proof, false));
            forest.get(b).add(new Edge(a, proof, true));
            return true;
        }

        int explain(int a, int b) {
            if (a == b) return emit(new Reflexivity(a), 1);
            syntax.work(parent.length);
            var previous = new int[parent.length];
            Arrays.fill(previous, -1);
            var via = new Edge[parent.length];
            var queue = new ArrayDeque<Integer>();
            queue.add(a);
            previous[a] = a;
            while (!queue.isEmpty() && previous[b] < 0) {
                syntax.work(1);
                int x = queue.remove();
                for (Edge e : forest.get(x)) {
                    syntax.work(1);
                    if (previous[e.to] < 0) {
                        previous[e.to] = x;
                        via[e.to] = e;
                        queue.add(e.to);
                    }
                }
            }
            if (previous[b] < 0) throw new IllegalArgumentException("missing congruence premise");
            var path = new ArrayList<Edge>();
            for (int x = b; x != a; x = previous[x]) {
                syntax.work(1);
                path.add(via[x]);
            }
            int result = -1;
            for (int i = path.size() - 1; i >= 0; i--) {
                syntax.work(1);
                var e = path.get(i);
                int step = e.reverse ? emit(new Symmetry(e.proof), 1) : e.proof;
                result = result < 0 ? step : emit(new Transitivity(result, step), 2);
            }
            return result;
        }

        private boolean mergeCongruentTerms() {
            boolean changed = false;
            var signatures = new HashMap<Term, Integer>();
            for (int id = 0; id < syntax.terms.size(); id++) {
                syntax.work(1);
                var term = syntax.terms.get(id);
                var children = new ArrayList<Integer>();
                for (int c : term.children()) {
                    syntax.work(1);
                    children.add(root(c));
                }
                syntax.refs(children.size());
                syntax.work(term.payload().length() + children.size());
                var signature = new Term(term.kind(), term.payload(), children);
                Integer other = signatures.putIfAbsent(signature, id);
                if (other != null && root(id) != root(other)) {
                    var proofs = new ArrayList<Integer>();
                    var rhs = syntax.terms.get(other);
                    for (int k = 0; k < term.children().size(); k++) {
                        syntax.work(1);
                        proofs.add(explain(term.children().get(k), rhs.children().get(k)));
                    }
                    int proof = emit(new Congruence(id, other, proofs), proofs.size() + 2);
                    changed |= connect(id, other, proof);
                }
            }
            return changed;
        }

        BuildResult build(int input, int output) {
            for (var instance : instances) {
                syntax.work(1);
                int proof =
                        emit(
                                new PatternInstance(
                                        instance.rule(),
                                        instance.bindings(),
                                        instance.before(),
                                        instance.after()),
                                instance.bindings().size() + 2);
                connect(instance.before(), instance.after(), proof);
            }
            boolean changed;
            do {
                changed = mergeCongruentTerms();
            } while (changed);
            if (root(input) != root(output))
                return new BuildResult(
                        BuildStatus.NO_DERIVATION,
                        Optional.empty(),
                        "ground closure does not entail endpoints",
                        workUsed());
            int conclusion = explain(input, output);
            syntax.work(syntax.terms.size() + syntax.identities.size() + steps.size());
            var certificate =
                    new ExtractionCertificate(
                            ExtractionCertificate.SCHEMA_VERSION,
                            syntax.terms,
                            syntax.identities,
                            steps,
                            input,
                            output,
                            conclusion);
            return new BuildResult(
                    BuildStatus.BUILT, Optional.of(certificate), "built", workUsed());
        }
    }
}
