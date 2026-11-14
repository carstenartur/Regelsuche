package de.regelsuche.egraph.proof;

import static de.regelsuche.egraph.proof.ExtractionCertificate.*;

import de.regelsuche.ast.*;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.transform.*;

import java.math.BigInteger;
import java.util.*;

/** Bounded syntax operations shared by producer and verifier, with no equality search. */
final class ProofSupport {
    final ProofLimits limits;
    long work, references, characters, patternNodes;
    boolean exhausted;
    final List<Term> terms = new ArrayList<>();
    final Map<Term, Integer> indexes = new HashMap<>();
    final List<Long> expanded = new ArrayList<>();
    final List<Integer> depths = new ArrayList<>();
    final List<RuleIdentity> identities = new ArrayList<>();
    final List<RuleData> rules = new ArrayList<>();
    final Map<String, Integer> ruleIds = new HashMap<>();
    final List<PNode> patterns = new ArrayList<>();
    final Map<PNode, Integer> patternIds = new HashMap<>();

    record PNode(String tag, String label, List<Integer> children) {}

    record RuleData(int source, int target, Set<String> placeholders) {}

    record Parts(String tag, String label, List<?> children) {}

    record Frame(Object node, boolean exit, int depth) {}

    private final boolean literalVariablePatterns;

    ProofSupport(ProofLimits limits) { this(limits, false); }

    ProofSupport(ProofLimits limits, boolean literalVariablePatterns) {
        this.limits = nonNull(limits);
        this.literalVariablePatterns = literalVariablePatterns;
    }

    static <T> T nonNull(T value) {
        if (value == null) throw new IllegalArgumentException("null proof input");
        return value;
    }

    void work(long n) {
        if (n < 0) throw new IllegalArgumentException("negative work");
        if (exhausted || n > limits.maxWork() - work) fail("work limit");
        work += n;
    }

    void fail(String reason) {
        exhausted = true;
        throw new ExtractionProofBuilder.LimitExceeded(reason);
    }

    void refs(long n) {
        work(n);
        if (n > limits.maxReferences() - references) fail("reference limit");
        references += n;
    }

    void chars(String s) {
        nonNull(s);
        work(s.length());
        if (s.length() > limits.maxCharacters() - characters) fail("character limit");
        characters += s.length();
    }

    void size(long n, long max, String label) {
        if (n > max) fail(label);
    }

    String rational(ExactRational r) {
        if (r.numerator().getClass() != BigInteger.class
                || r.denominator().getClass() != BigInteger.class)
            throw new IllegalArgumentException("opaque rational scalar");
        work(1);
        size(r.numerator().bitLength(), limits.maxRationalBits(), "rational bits");
        size(r.denominator().bitLength(), limits.maxRationalBits(), "rational bits");
        // A decimal rendering of b binary bits requires at most b/3+2 characters.
        long estimate = (long) r.numerator().bitLength() / 3 + r.denominator().bitLength() / 3 + 5;
        size(estimate, 4096, "rational text envelope");
        size(estimate, limits.maxCharacters(), "rational text limit");
        work(estimate);
        String text = r.toString();
        size(text.length(), 4096, "rational text envelope");
        return text;
    }

    ExactRational number(String text) {
        size(text.length(), 4096, "rational text envelope");
        work(text.length());
        if (!text.matches("-?(0|[1-9][0-9]*)(/[1-9][0-9]*)?"))
            throw new IllegalArgumentException("noncanonical rational grammar");
        int slash = text.indexOf('/');
        int numeratorDigits = (slash < 0 ? text.length() : slash) - (text.startsWith("-") ? 1 : 0);
        int denominatorDigits = slash < 0 ? 1 : text.length() - slash - 1;
        // 30103/100000 strictly exceeds log10(2). Bound digit allocation before parsing.
        long maxDigits = ((long) limits.maxRationalBits() * 30103) / 100000 + 1;
        size(numeratorDigits, maxDigits, "rational bits");
        size(denominatorDigits, maxDigits, "rational bits");
        var value = ExactRational.fromCanonicalText(text);
        size(value.numerator().bitLength(), limits.maxRationalBits(), "rational bits");
        size(value.denominator().bitLength(), limits.maxRationalBits(), "rational bits");
        return value;
    }

    void registry(Collection<? extends RewriteRule> supplied) {
        size(supplied.size(), limits.maxTerms(), "rules limit");
        for (RewriteRule rule : supplied) {
            work(1);
            if (rule == null || rule.getClass() != PatternRewriteRule.class)
                throw new ExtractionProofBuilder.UnsupportedRule(
                        "only base pattern rules supported");
            PatternRewriteRule p = (PatternRewriteRule) rule;
            if (!p.recognitionProfile().equals(RecognitionProfile.exact())
                    || !p.isEquivalencePreservingByConstruction())
                throw new ExtractionProofBuilder.UnsupportedRule("rule is not exact preserving");
            chars(p.id());
            if (ruleIds.containsKey(p.id()))
                throw new ExtractionProofBuilder.UnsupportedRule("duplicate rule ID");
            int source = pattern(p.source()), target = pattern(p.target());
            Set<String> names = placeholders(source);
            if (!names.containsAll(placeholders(target)))
                throw new ExtractionProofBuilder.UnsupportedRule("unbound target placeholder");
            ruleIds.put(p.id(), rules.size());
            rules.add(new RuleData(source, target, Set.copyOf(names)));
            identities.add(new RuleIdentity(p.id(), p.source(), p.target()));
        }
    }

    Set<String> placeholders(int root) {
        Set<String> result = new HashSet<>();
        Set<Integer> seen = new HashSet<>();
        var pending = new ArrayDeque<Integer>();
        pending.push(root);
        while (!pending.isEmpty()) {
            work(1);
            int i = pending.pop();
            if (!seen.add(i)) continue;
            var p = patterns.get(i);
            if (p.tag.equals("P")) result.add(p.label);
            for (int c : p.children) {
                work(1);
                pending.push(c);
            }
        }
        return result;
    }

    Parts parts(Object o) {
        return switch (o) {
            case NumberExpr n -> new Parts("NUMBER", rational(n.value()), List.of());
            case VariableExpr v -> new Parts("VARIABLE", v.name(), List.of());
            case BinaryExpr b ->
                    new Parts("BINARY", b.operator().name(), List.of(b.left(), b.right()));
            case FunctionExpr f -> new Parts("FUNCTION", f.name(), f.arguments());
            case PatternExpr.Placeholder p -> new Parts("P", p.name(), List.of());
            case PatternExpr.LiteralVariable v when literalVariablePatterns -> new Parts("VARIABLE", v.name(), List.of());
            case PatternExpr.LiteralNumber n -> new Parts("NUMBER", rational(n.value()), List.of());
            case PatternExpr.Operation b ->
                    new Parts("BINARY", b.operator().name(), List.of(b.left(), b.right()));
            case PatternExpr.Function f -> new Parts("FUNCTION", f.name(), f.arguments());
            default -> throw new ExtractionProofBuilder.UnsupportedRule("unsupported syntax");
        };
    }

    int pattern(PatternExpr p) {
        return inspect(p, true);
    }

    int expression(Expr e) {
        return inspect(e, false);
    }

    int inspect(Object root, boolean pattern) {
        nonNull(root);
        var ids = new IdentityHashMap<Object, Integer>();
        var facts = new IdentityHashMap<Object, long[]>();
        var descriptions = new IdentityHashMap<Object, Parts>();
        var stack = new ArrayDeque<Frame>();
        stack.push(new Frame(root, false, 1));
        while (!stack.isEmpty()) {
            work(1);
            var frame = stack.pop();
            if (ids.containsKey(frame.node)) continue;
            if (!frame.exit) {
                scheduleSyntax(frame, descriptions, ids, stack);
            } else {
                completeSyntax(frame.node, pattern, descriptions, ids, facts);
            }
        }
        return ids.get(root);
    }

    private void scheduleSyntax(
            Frame frame,
            IdentityHashMap<Object, Parts> descriptions,
            IdentityHashMap<Object, Integer> ids,
            ArrayDeque<Frame> stack) {
        size(frame.depth, limits.maxDepth(), "syntax depth");
        var p = parts(frame.node);
        size(descriptions.size() + 1L, limits.maxTerms(), "syntax nodes");
        descriptions.put(frame.node, p);
        size(
                stack.size() + p.children.size() + 1L,
                limits.maxReferences(),
                "syntax pending references");
        size(p.children.size(), limits.maxReferences(), "syntax arity");
        stack.push(new Frame(frame.node, true, frame.depth));
        for (int i = p.children.size() - 1; i >= 0; i--) {
            work(1);
            if (!ids.containsKey(p.children.get(i)))
                stack.push(new Frame(p.children.get(i), false, frame.depth + 1));
        }
    }

    private void completeSyntax(
            Object node,
            boolean pattern,
            IdentityHashMap<Object, Parts> descriptions,
            IdentityHashMap<Object, Integer> ids,
            IdentityHashMap<Object, long[]> facts) {

        var p = descriptions.get(node);
        long count = 1, depth = 1;
        var children = new ArrayList<Integer>();
        for (Object child : p.children) {
            work(1);
            long[] f = facts.get(child);
            count += f[0];
            depth = Math.max(depth, 1 + f[1]);
            size(count, limits.maxExpandedNodes(), "expanded syntax");
            children.add(ids.get(child));
        }
        size(count, limits.maxExpandedNodes(), "expanded syntax");
        size(depth, limits.maxDepth(), "syntax depth");
        int id;
        if (pattern) {
            size(++patternNodes, limits.maxTerms(), "pattern nodes");
            chars(p.label);
            refs(children.size());
            var key = new PNode(p.tag, p.label, List.copyOf(children));
            Integer old = patternIds.get(key);
            if (old == null) {
                id = patterns.size();
                patterns.add(key);
                patternIds.put(key, id);
            } else id = old;
        } else id = intern(new Term(Kind.valueOf(p.tag), p.label, children));
        ids.put(node, id);
        facts.put(node, new long[] {count, depth});
    }

    int requireTerm(int i) {
        if (i < 0 || i >= terms.size())
            throw new IllegalArgumentException("invalid term reference");
        return i;
    }

    int intern(Term t) {
        work(1);
        nonNull(t);
        nonNull(t.kind());
        nonNull(t.payload());
        size(t.children().size(), limits.maxReferences(), "term arity");
        long count = 1;
        int depth = 1;
        for (int child : t.children()) {
            work(1);
            requireTerm(child);
            count += expanded.get(child);
            depth = Math.max(depth, depths.get(child) + 1);
            size(count, limits.maxExpandedNodes(), "expanded term");
        }
        size(count, limits.maxExpandedNodes(), "expanded term");
        size(depth, limits.maxDepth(), "term depth");
        if ((t.kind() == Kind.NUMBER || t.kind() == Kind.VARIABLE) && !t.children().isEmpty()
                || t.kind() == Kind.BINARY && t.children().size() != 2)
            throw new IllegalArgumentException("term arity");
        // Debit text before any parser, hash or equality touches it.
        size(t.payload().length(), limits.maxCharacters(), "label length");
        work(t.payload().length());
        if (t.kind() == Kind.NUMBER) {
            size(t.payload().length(), 4096, "rational text envelope");
            var r = number(t.payload());
            if (!rational(r).equals(t.payload()))
                throw new IllegalArgumentException("noncanonical rational");
        }
        if (t.kind() == Kind.BINARY) BinaryOperator.valueOf(t.payload());
        if (t.kind() == Kind.VARIABLE) new VariableExpr(t.payload());
        if (t.kind() == Kind.FUNCTION && t.payload().isBlank())
            throw new IllegalArgumentException("blank function");
        Integer old = indexes.get(t);
        if (old != null) return old;
        size(terms.size() + 1L, limits.maxTerms(), "terms limit");
        chars(t.payload());
        refs(t.children().size());
        int id = terms.size();
        terms.add(t);
        indexes.put(t, id);
        expanded.add(count);
        depths.add(depth);
        return id;
    }

    void bindings(RuleData r, Map<String, Integer> bindings) {
        nonNull(bindings);
        size(bindings.size(), limits.maxReferences(), "bindings limit");
        if (bindings.size() != r.placeholders.size())
            throw new IllegalArgumentException("binding names");
        for (var e : bindings.entrySet()) {
            work(1);
            String name = nonNull(e.getKey());
            int term = nonNull(e.getValue());
            size(name.length(), limits.maxCharacters(), "binding label");
            work(name.length());
            if (!r.placeholders.contains(name)) throw new IllegalArgumentException("binding names");
            requireTerm(term);
        }
    }

    int instantiate(int root, Map<String, Integer> bindings, boolean create) {
        var done = new HashMap<Integer, Integer>();
        var stack = new ArrayDeque<Integer>();
        stack.push(root);
        while (!stack.isEmpty()) {
            work(1);
            int id = stack.peek();
            var p = patterns.get(id);
            if (done.containsKey(id)) {
                stack.pop();
                continue;
            }
            boolean ready = true;
            for (int child : p.children) {
                work(1);
                if (!done.containsKey(child)) {
                    stack.push(child);
                    ready = false;
                }
            }
            if (!ready) continue;
            int value;
            if (p.tag.equals("P")) value = bindings.get(p.label);
            else {
                var children = new ArrayList<Integer>();
                for (int child : p.children) {
                    work(1);
                    children.add(done.get(child));
                }
                var term = new Term(Kind.valueOf(p.tag), p.label, children);
                if (create) value = intern(term);
                else {
                    work(p.label.length() + children.size());
                    Integer found = indexes.get(term);
                    if (found == null) throw new IllegalArgumentException("instance term absent");
                    value = found;
                }
            }
            done.put(id, value);
            stack.pop();
        }
        return done.get(root);
    }
}
