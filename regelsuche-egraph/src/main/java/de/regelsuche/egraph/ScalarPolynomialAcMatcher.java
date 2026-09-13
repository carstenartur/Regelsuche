package de.regelsuche.egraph;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.transform.PatternExpr;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Native e-class matching; no representative extraction or syntax-variant enumeration. */
public final class ScalarPolynomialAcMatcher {
    public static final String CONTRACT = "regelsuche.scalar-polynomial-ac-ematching/v1";
    public static final String FRAGMENT = "UNCONDITIONAL_COMMUTATIVE_Q_SCALARS_ATOMIC_ECLASS_BINDINGS_AC_ROOT_SUBMULTISETS";
    public enum Outcome { COMPLETE, BUDGET_INCONCLUSIVE, CYCLIC_INCONCLUSIVE, UNSUPPORTED_GRAPH, TECHNICAL_FAILURE }
    public record Limits(int maxAcOperands, int maxResults, long maxWorkUnits) {
        public Limits {
            if (maxAcOperands < 2 || maxAcOperands > 32 || maxResults < 1 || maxResults > 4096
                    || maxWorkUnits < 0 || maxWorkUnits > 10_000_000) throw new IllegalArgumentException("invalid AC limits");
        }
        public static Limits defaults() { return new Limits(8, 256, 200_000); }
    }

    private sealed interface Instruction permits Variable, Leaf, Ordered, Ac { }
    private record Variable(String name) implements Instruction { }
    private record Leaf(String symbol) implements Instruction { }
    private record Ordered(String symbol, List<Instruction> children) implements Instruction { }
    private record Ac(BinaryOperator operator, List<Instruction> atoms) implements Instruction { }

    /** A privately compiled immutable program, not a caller-supplied plan/hash pair. */
    public static final class Plan {
        private final PatternExpr pattern;
        private final Instruction program;
        private final int compilationNodeVisits;
        private final String canonicalJson, contentHash;
        private Plan(PatternExpr pattern, Instruction program, int visits) {
            this.pattern = pattern; this.program = program; this.compilationNodeVisits = visits;
            canonicalJson = exactJson(new JsonWriter().beginObject().property("schema", CONTRACT).property("fragment", FRAGMENT)
                .object("pattern", object -> writePattern(object, pattern)).property("compilationNodeVisits", visits).endObject().toString());
            contentHash = hash(canonicalJson);
        }
        public PatternExpr pattern() { return pattern; }
        public int compilationNodeVisits() { return compilationNodeVisits; }
        public String canonicalJson() { return canonicalJson; }
        public String contentHash() { return contentHash; }
    }

    public static Plan compile(PatternExpr pattern) {
        int[] visits = {0};
        return new Plan(Objects.requireNonNull(pattern), compile(pattern, visits), visits[0]);
    }

    /** Bounds encoding even for excluded syntax, before an existing recursive rule fingerprint sees it. */
    static void admitPatternEncoding(PatternExpr pattern) {
        var pending = new java.util.ArrayDeque<PatternExpr>();
        pending.add(Objects.requireNonNull(pattern));
        int visits = 0;
        while (!pending.isEmpty()) {
            if (++visits > 64) throw new IllegalArgumentException("pattern node bound exceeded");
            var next = pending.removeFirst();
            if (next instanceof PatternExpr.Placeholder variable) requireName(variable.name());
            else if (next instanceof PatternExpr.LiteralVariable variable) requireName(variable.name());
            else if (next instanceof PatternExpr.LiteralNumber number) requireNumber(number.value());
            else if (next instanceof PatternExpr.Operation operation) {
                pending.add(operation.left()); pending.add(operation.right());
            } else if (next instanceof PatternExpr.Function function) {
                requireName(function.name());
                if (function.arguments().size() > 64 - visits - pending.size())
                    throw new IllegalArgumentException("pattern arity bound exceeded");
                pending.addAll(function.arguments());
            }
        }
    }

    private static Instruction compile(PatternExpr pattern, int[] visits) {
        if (++visits[0] > 64) throw new IllegalArgumentException("pattern node bound exceeded");
        if (pattern instanceof PatternExpr.Placeholder variable) { requireName(variable.name()); return new Variable(variable.name()); }
        if (pattern instanceof PatternExpr.LiteralVariable variable) { requireName(variable.name()); return new Leaf("var:" + variable.name()); }
        if (pattern instanceof PatternExpr.LiteralNumber number) {
            requireNumber(number.value()); return new Leaf("num:" + number.value().canonicalText());
        }
        if (!(pattern instanceof PatternExpr.Operation operation) || operation.operator() == BinaryOperator.DIV)
            throw new IllegalArgumentException("outside scalar polynomial pattern fragment");
        if (operation.operator() == BinaryOperator.POW && (!(operation.right() instanceof PatternExpr.LiteralNumber exponent)
                || !positiveExponent(exponent.value()))) throw new IllegalArgumentException("positive literal exponent through eight required");
        var left = compile(operation.left(), visits); var right = compile(operation.right(), visits);
        if (isAc(operation.operator())) {
            var atoms = new ArrayList<Instruction>();
            for (var child : List.of(left, right)) {
                if (child instanceof Ac ac && ac.operator() == operation.operator()) atoms.addAll(ac.atoms());
                else atoms.add(child);
            }
            return new Ac(operation.operator(), List.copyOf(atoms));
        }
        return new Ordered("op:" + operation.operator().name(), List.of(left, right));
    }

    public record Match(EClassId root, Map<String, EClassId> bindings, BinaryOperator contextOperator, List<EClassId> remainder) {
        public Match { bindings = Map.copyOf(bindings); remainder = List.copyOf(remainder); }
        void write(JsonWriter json) {
            json.property("root", root.value()).property("contextOperator", contextOperator == null ? null : contextOperator.name())
                .object("bindings", values -> new TreeMap<>(bindings).forEach((key, value) -> values.property(key, value.value())))
                .array("remainder", values -> remainder.forEach(id -> values.value(id.value())));
        }
    }
    public record Work(Map<String, Long> counters, long chargedUnits, long limit) {
        public Work { counters = Map.copyOf(counters); }
        void write(JsonWriter json) {
            json.property("chargedUnits", chargedUnits).property("limit", limit)
                .object("counters", object -> new TreeMap<>(counters).forEach(object::property));
        }
    }
    public record Result(String planHash, long graphVersion, Outcome outcome, String detailCode, List<Match> matches, Work work) {
        public Result { matches = List.copyOf(matches); }
        public boolean complete() { return outcome == Outcome.COMPLETE; }
        public String canonicalJson() {
            var json = new JsonWriter().beginObject(); write(json); return exactJson(json.endObject().toString());
        }
        void write(JsonWriter json) {
            json.property("schema", CONTRACT).property("planHash", planHash)
                .property("graphVersion", graphVersion).property("outcome", outcome.name()).property("detailCode", detailCode)
                .array("matches", array -> matches.forEach(match -> array.objectValue(match::write))).object("work", work::write);
        }
    }

    private final EGraph graph;
    public ScalarPolynomialAcMatcher(EGraph graph) { this.graph = Objects.requireNonNull(graph); }
    public Result match(Plan plan, Collection<EClassId> roots, Limits limits) {
        return match(plan, roots, limits, new Authority(limits.maxWorkUnits()));
    }
    Result match(Plan plan, Collection<EClassId> roots, Limits limits, Authority authority) {
        return new Execution(Objects.requireNonNull(plan), Objects.requireNonNull(limits), authority).run(roots);
    }

    /** Package-owned shared run budget; refusing an event retains the consumed prefix. */
    static final class Authority {
        private final Map<String, Long> counters = new TreeMap<>();
        private final long limit;
        private long total;
        Authority(long limit) { this.limit = limit; }
        void charge(String stage, long units) {
            if (units < 0) throw new IllegalArgumentException("negative work");
            if (units > limit - total) throw new Stop(Outcome.BUDGET_INCONCLUSIVE, "SHARED_LOGICAL_WORK_LIMIT");
            counters.merge(stage, units, Math::addExact); total += units;
        }
        Work snapshot() { return new Work(counters, total, limit); }
    }
    static final class Stop extends RuntimeException {
        final Outcome outcome; final String detail;
        Stop(Outcome outcome, String detail) { super(detail); this.outcome = outcome; this.detail = detail; }
    }
    private record FlattenKey(EClassId root, BinaryOperator operator) { }

    private final class Execution {
        private final Plan plan; private final Limits limits; private final Authority work;
        private final Map<FlattenKey, List<List<EClassId>>> flatCache = new HashMap<>();
        private final Set<FlattenKey> active = new HashSet<>();
        private final Map<Match, Match> retained = new LinkedHashMap<>();
        Execution(Plan plan, Limits limits, Authority work) { this.plan = plan; this.limits = limits; this.work = work; }

        Result run(Collection<EClassId> roots) {
            long version = graph.version(); Outcome outcome = Outcome.COMPLETE; String detail = "FINITE_ATOMIC_BINDING_QUERY_COMPLETE";
            try {
                work.charge("queryDispatches", 1);
                if (graph.nodeCount() > 4096 || roots.size() > 4096)
                    throw new Stop(Outcome.BUDGET_INCONCLUSIVE, "GRAPH_OR_ROOT_ADMISSION_LIMIT");
                List<EClassId> candidates = roots.stream().map(graph::find).distinct().sorted().toList();
                validateGraph(candidates);
                for (var root : candidates) {
                    work.charge("candidateRoots", 1);
                    if (plan.program instanceof Ac ac) {
                        for (var atoms : flatten(root, ac.operator())) {
                            if (atoms.size() < ac.atoms().size()) continue;
                            join(ac.atoms(), 0, multiplicities(atoms), Map.of(), (bindings, remainder) -> retain(new Match(root, bindings, ac.operator(), remainder)));
                        }
                    } else for (var bindings : against(plan.program, root, Map.of())) retain(new Match(root, bindings, null, List.of()));
                }
            } catch (Stop stopped) { outcome = stopped.outcome; detail = stopped.detail; }
            catch (RuntimeException failure) { outcome = Outcome.TECHNICAL_FAILURE; detail = failure.getClass().getName(); }
            if (version != graph.version()) throw new IllegalStateException("graph mutated during native matching");
            return new Result(plan.contentHash(), version, outcome, detail, List.copyOf(retained.values()), work.snapshot());
        }

        private void validateGraph(Collection<EClassId> roots) {
            var visited = new HashSet<EClassId>(); var pending = new java.util.ArrayDeque<>(roots);
            while (!pending.isEmpty()) {
                EClassId root = graph.find(pending.removeFirst());
                if (!visited.add(root)) continue;
                work.charge("classContextsChecked", 1);
                if (!graph.assumptionsFor(root).fingerprint().isEmpty())
                    throw new Stop(Outcome.UNSUPPORTED_GRAPH, "ASSUMPTION_BOUND_GRAPH_NOT_ADMITTED");
                for (ENode node : nodes(root)) {
                    work.charge("fragmentNodesChecked", 1);
                    String symbol = node.symbol();
                    if (node.isLeaf() && symbol.startsWith("var:")) continue;
                    if (node.isLeaf() && symbol.startsWith("num:")) {
                        try { requireNumber(ExactRational.fromCanonicalText(symbol.substring(4))); }
                        catch (IllegalArgumentException invalid) { unsupported(); }
                        continue;
                    }
                    if (!Set.of("op:ADD", "op:MUL", "op:SUB", "op:POW").contains(symbol) || node.children().size() != 2) unsupported();
                    if (symbol.equals("op:POW")) for (ENode exponent : nodes(node.children().get(1))) {
                        work.charge("exponentNodesChecked", 1);
                        try {
                            if (!exponent.isLeaf() || !exponent.symbol().startsWith("num:")
                                    || !positiveExponent(ExactRational.fromCanonicalText(exponent.symbol().substring(4)))) unsupported();
                        } catch (IllegalArgumentException invalid) { unsupported(); }
                    }
                    pending.addAll(node.children());
                }
            }
        }

        private List<Map<String, EClassId>> against(Instruction instruction, EClassId raw, Map<String, EClassId> bindings) {
            work.charge("bindingJoins", 1); EClassId root = graph.find(raw);
            if (instruction instanceof Variable variable) {
                EClassId previous = bindings.get(variable.name());
                if (previous != null) return graph.find(previous).equals(root) ? List.of(bindings) : List.of();
                var next = new TreeMap<>(bindings); next.put(variable.name(), root); return List.of(Map.copyOf(next));
            }
            var result = new ArrayList<Map<String, EClassId>>();
            if (instruction instanceof Ac ac) {
                for (var atoms : flatten(root, ac.operator())) if (atoms.size() == ac.atoms().size())
                    join(ac.atoms(), 0, multiplicities(atoms), bindings, (match, ignored) -> addBinding(result, match));
            } else for (var node : nodes(root)) {
                work.charge("nodesScanned", 1);
                if (instruction instanceof Leaf leaf) {
                    if (node.isLeaf() && node.symbol().equals(leaf.symbol())) addBinding(result, bindings);
                } else {
                    var ordered = (Ordered) instruction;
                    if (!node.symbol().equals(ordered.symbol()) || node.children().size() != ordered.children().size()) continue;
                    List<Map<String, EClassId>> partial = List.of(bindings);
                    for (int index = 0; index < ordered.children().size(); index++) {
                        var next = new ArrayList<Map<String, EClassId>>();
                        for (var row : partial) for (var joined : against(ordered.children().get(index), node.children().get(index), row)) addBinding(next, joined);
                        partial = next;
                    }
                    for (var row : partial) addBinding(result, row);
                }
            }
            return List.copyOf(result);
        }

        private void join(List<Instruction> atoms, int index, TreeMap<EClassId, Integer> remaining,
                Map<String, EClassId> bindings, java.util.function.BiConsumer<Map<String, EClassId>, List<EClassId>> accept) {
            if (index == atoms.size()) { accept.accept(bindings, expand(remaining)); return; }
            for (var id : new ArrayList<>(remaining.keySet())) {
                work.charge("multiplicitySelections", 1);
                for (var match : against(atoms.get(index), id, bindings)) {
                    int count = remaining.get(id);
                    if (count == 1) remaining.remove(id); else remaining.put(id, count - 1);
                    join(atoms, index + 1, remaining, match, accept);
                    remaining.put(id, count);
                }
            }
        }

        private List<List<EClassId>> flatten(EClassId raw, BinaryOperator operator) {
            var key = new FlattenKey(graph.find(raw), operator);
            work.charge("flattenCacheLookups", 1);
            var hit = flatCache.get(key);
            if (hit != null) { work.charge("flattenCacheHits", 1); return hit; }
            if (active.size() >= 64) throw new Stop(Outcome.BUDGET_INCONCLUSIVE, "AC_DEPTH_LIMIT");
            if (!active.add(key)) throw new Stop(Outcome.CYCLIC_INCONCLUSIVE, "CYCLIC_AC_ALTERNATIVE_NOT_ENUMERATED");
            var alternatives = new LinkedHashMap<String, List<EClassId>>();
            try {
                for (var node : nodes(key.root())) {
                    work.charge("flattenNodesScanned", 1);
                    if (node.symbol().equals("op:" + operator.name())) {
                        for (var left : flatten(node.children().get(0), operator)) for (var right : flatten(node.children().get(1), operator)) {
                            work.charge("flattenTupleJoins", 1);
                            if (left.size() + right.size() > limits.maxAcOperands()) throw new Stop(Outcome.BUDGET_INCONCLUSIVE, "AC_OPERAND_LIMIT");
                            var merged = new ArrayList<>(left); merged.addAll(right); merged.sort(Comparator.naturalOrder()); addAlternative(alternatives, merged);
                        }
                    } else addAlternative(alternatives, List.of(key.root()));
                }
            } finally { active.remove(key); }
            var result = List.copyOf(alternatives.values()); flatCache.put(key, result); return result;
        }

        private void addAlternative(Map<String, List<EClassId>> alternatives, List<EClassId> atoms) {
            work.charge("flattenedOperands", atoms.size());
            String key = atoms.toString();
            if (alternatives.containsKey(key)) { work.charge("duplicateFlattenings", 1); return; }
            if (alternatives.size() >= limits.maxResults()) throw new Stop(Outcome.BUDGET_INCONCLUSIVE, "FLATTENING_LIMIT");
            alternatives.put(key, List.copyOf(atoms));
        }
        private void addBinding(List<Map<String, EClassId>> rows, Map<String, EClassId> row) {
            work.charge("bindingMaterializations", 1);
            if (rows.contains(row)) { work.charge("duplicateBindings", 1); return; }
            if (rows.size() >= limits.maxResults()) throw new Stop(Outcome.BUDGET_INCONCLUSIVE, "INTERMEDIATE_RESULT_LIMIT");
            rows.add(Map.copyOf(row));
        }
        private void retain(Match match) {
            work.charge("resultIdentityChecks", 1);
            if (retained.containsKey(match)) { work.charge("duplicateMatches", 1); return; }
            if (retained.size() >= limits.maxResults()) throw new Stop(Outcome.BUDGET_INCONCLUSIVE, "RESULT_LIMIT");
            work.charge("matchesRetained", 1); retained.put(match, match);
        }
        private List<ENode> nodes(EClassId root) {
            var snapshot = new ArrayList<ENode>();
            for (var node : graph.classOrThrow(root).nodes()) {
                work.charge("nodeOrderingInputs", 1);
                if (node.symbol().length() > 768 || node.children().size() > 2) unsupported();
                snapshot.add(node);
            }
            snapshot.sort(Comparator.comparing(ENode::symbol).thenComparing(node -> node.children().toString()));
            return List.copyOf(snapshot);
        }
        private void unsupported() { throw new Stop(Outcome.UNSUPPORTED_GRAPH, "OUTSIDE_DECLARED_SCALAR_POLYNOMIAL_GRAPH"); }
    }

    private static TreeMap<EClassId, Integer> multiplicities(List<EClassId> atoms) {
        var result = new TreeMap<EClassId, Integer>(); atoms.forEach(id -> result.merge(id, 1, Math::addExact)); return result;
    }
    private static List<EClassId> expand(Map<EClassId, Integer> counts) {
        var result = new ArrayList<EClassId>(); counts.forEach((id, count) -> { for (int i = 0; i < count; i++) result.add(id); }); return List.copyOf(result);
    }
    static boolean isAc(BinaryOperator operator) { return operator == BinaryOperator.ADD || operator == BinaryOperator.MUL; }
    static boolean positiveExponent(ExactRational value) { return value.isInteger() && value.signum() > 0 && value.numerator().compareTo(java.math.BigInteger.valueOf(8)) <= 0; }
    static void requireNumber(ExactRational value) {
        if (value.numerator().bitLength() > 1024 || value.denominator().bitLength() > 1024) throw new IllegalArgumentException("scalar coefficient bound exceeded");
    }
    private static void requireName(String name) {
        if (name.length() > 128) throw new IllegalArgumentException("pattern symbol bound exceeded");
    }
    private static void writePattern(JsonWriter json, PatternExpr pattern) {
        if (pattern instanceof PatternExpr.Placeholder variable) json.property("kind", "placeholder").property("name", variable.name());
        else if (pattern instanceof PatternExpr.LiteralVariable variable) json.property("kind", "literalVariable").property("name", variable.name());
        else if (pattern instanceof PatternExpr.LiteralNumber number) json.property("kind", "literalNumber").property("value", number.value().canonicalText());
        else {
            var operation = (PatternExpr.Operation) pattern;
            json.property("kind", "operation").property("operator", operation.operator().name())
                .object("left", child -> writePattern(child, operation.left())).object("right", child -> writePattern(child, operation.right()));
        }
    }
    static String hash(String value) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Preserve every Java code unit in these local JSON/UTF-8 identities; ordinary JSON bytes are unchanged. */
    static String exactJson(String value) {
        StringBuilder escaped = null;
        int copied = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) { index++; continue; }
            if (Character.isSurrogate(current)) {
                if (escaped == null) escaped = new StringBuilder(value.length());
                escaped.append(value, copied, index).append("\\u").append(HexFormat.of().toHexDigits(current));
                copied = index + 1;
            }
        }
        return escaped == null ? value : escaped.append(value, copied, value.length()).toString();
    }
}
