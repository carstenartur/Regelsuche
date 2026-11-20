package de.regelsuche.value;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.scalar.ExactRational;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;

/** Bounded owner-local semantic interning for the exact expression language. */
public final class ExprValueArena implements AutoCloseable {
    public static final String DIGEST_VERSION = "regelsuche.expr-value-digest/v1";

    /** Opaque local identity. The handle does not retain the arena or its other entries. */
    public static final class ValueId {
        private final Object owner;
        private final int index;

        private ValueId(Object owner, int index) {
            this.owner = owner;
            this.index = index;
        }
    }

    private enum Kind { VARIABLE, SCOPED, NUMBER, FUNCTION, ADD, MUL, SUB, DIV, POW }

    // Arrays are privately created and never mutated. A hash match is only a bucket lookup.
    private record Key(Kind kind, Object atom, int[] edges) {
        @Override public int hashCode() {
            return 31 * (31 * kind.hashCode() + atom.hashCode()) + Arrays.hashCode(edges);
        }

        @Override public boolean equals(Object other) {
            return other instanceof Key key && kind == key.kind && atom.equals(key.atom)
                && Arrays.equals(edges, key.edges);
        }

        boolean unordered() { return kind == Kind.ADD || kind == Kind.MUL; }
    }

    /** Exact immutable source occurrence. TreePosition performs selection and local replacement. */
    public static final class Occurrence {
        private final Expr source;
        private final TreePosition position;
        private final Expr syntax;
        private final ValueId value;

        private Occurrence(Expr source, TreePosition position, Expr syntax, ValueId value) {
            this.source = source;
            this.position = position;
            this.syntax = syntax;
            this.value = value;
        }

        public Expr source() { return source; }
        public TreePosition position() { return position; }
        public Expr syntax() { return syntax; }
        public ValueId value() { return value; }

        @Override public boolean equals(Object other) {
            return other instanceof Occurrence occurrence && source == occurrence.source
                && value == occurrence.value && position.path().equals(occurrence.position.path());
        }

        @Override public int hashCode() {
            return 31 * (31 * System.identityHashCode(source) + value.hashCode()) + position.path().hashCode();
        }
    }

    /** One identity-map entry per physical syntax node. Positions are resolved only on request. */
    public final class Projection {
        private Expr source;
        private final IdentityHashMap<Expr, ValueId> values;

        private Projection(Expr source, IdentityHashMap<Expr, ValueId> values) {
            this.source = source;
            this.values = values;
        }

        /** O(1) lookup for a node already obtained by walking this source. */
        public ValueId valueOf(Expr syntax) {
            synchronized (ExprValueArena.this) {
                requireOpen();
                var value = values.get(Objects.requireNonNull(syntax, "syntax"));
                if (value == null) throw new IllegalArgumentException("syntax is outside this projection");
                return value;
            }
        }

        /** Navigate the original AST by child index. The text remains the caller's display/staleness metadata. */
        public Optional<Occurrence> at(TreePosition position) {
            synchronized (ExprValueArena.this) {
                requireOpen();
                return position.subtreeAt(source).map(syntax ->
                    new Occurrence(source, position, syntax, values.get(syntax)));
            }
        }
    }

    /** Suspend one parent while resolving a child, even for a wide shared argument list. */
    private static final class Visit {
        final Expr syntax;
        final List<Expr> children;
        int nextChild;

        Visit(Expr syntax) {
            this.syntax = syntax;
            children = switch (syntax) {
                case BinaryExpr binary -> List.of(binary.left(), binary.right());
                case FunctionExpr function -> function.arguments();
                default -> List.of();
            };
        }
    }

    private record DigestCount(byte[] digest, int count) {}
    private static final int[] NO_EDGES = {};
    private final Object owner = new Object();
    private final int maximumEntries;
    private final List<Key> entries = new ArrayList<>();
    private final HashMap<Key, ValueId> index = new HashMap<>();
    private final List<byte[]> digests = new ArrayList<>();
    // The arena can invalidate live projections on close without retaining abandoned source trees.
    private final Set<Projection> projections = Collections.newSetFromMap(new WeakHashMap<>());
    private boolean closed;

    public ExprValueArena(int maximumEntries) {
        if (maximumEntries < 1) throw new IllegalArgumentException("maximumEntries must be positive");
        this.maximumEntries = maximumEntries;
    }

    /** Each syntax object is visited once per projection, including shared input DAGs. */
    public synchronized ValueId intern(Expr expression) {
        return resolve(expression, Integer.MAX_VALUE).get(expression);
    }

    /**
     * Keep the source identity map built during interning. The caller bounds distinct physical
     * syntax nodes separately from the arena's bound on distinct semantic values. Both limits
     * apply before publishing the projection; a failure rolls back all new arena entries.
     */
    public synchronized Projection project(Expr expression, long maximumSyntaxNodes) {
        if (maximumSyntaxNodes < 1) throw new IllegalArgumentException("maximumSyntaxNodes must be positive");
        var projection = new Projection(expression, resolve(expression, maximumSyntaxNodes));
        projections.add(projection);
        return projection;
    }

    private IdentityHashMap<Expr, ValueId> resolve(Expr expression, long maximumSyntaxNodes) {
        requireOpen();
        Objects.requireNonNull(expression, "expression");
        int startingSize = entries.size();
        try {
            var resolved = new IdentityHashMap<Expr, ValueId>();
            var visits = new ArrayDeque<Visit>();
            visits.push(new Visit(expression));
            while (!visits.isEmpty()) {
                var visit = visits.peek();
                if (visit.nextChild < visit.children.size()) {
                    Expr child = visit.children.get(visit.nextChild++);
                    if (!resolved.containsKey(child)) {
                        if ((long) resolved.size() + visits.size() >= maximumSyntaxNodes)
                            throw new IllegalStateException("syntax projection capacity exceeded");
                        visits.push(new Visit(child));
                    }
                } else {
                    resolved.put(visit.syntax, internNode(visit.syntax, resolved));
                    visits.pop();
                }
            }
            return resolved;
        } catch (RuntimeException failure) {
            while (entries.size() > startingSize) index.remove(entries.removeLast());
            throw failure;
        }
    }

    private ValueId internNode(Expr syntax, IdentityHashMap<Expr, ValueId> resolved) {
        Key key = switch (syntax) {
            case VariableExpr variable -> new Key(variable.symbol().isPresent() ? Kind.SCOPED : Kind.VARIABLE,
                variable.name(), NO_EDGES);
            case NumberExpr number -> new Key(Kind.NUMBER, number.value(), NO_EDGES);
            case FunctionExpr function -> new Key(Kind.FUNCTION, function.name().trim(),
                function.arguments().stream().mapToInt(arg -> resolved.get(arg).index).toArray());
            case BinaryExpr binary -> binaryKey(binary.operator(), resolved.get(binary.left()).index,
                resolved.get(binary.right()).index);
        };
        ValueId existing = index.get(key);
        if (existing != null) return existing;
        if (entries.size() == maximumEntries) throw new IllegalStateException("semantic value arena capacity exceeded");
        var id = new ValueId(owner, entries.size());
        entries.add(key);
        index.put(key, id);
        return id;
    }

    private Key binaryKey(BinaryOperator operator, int left, int right) {
        var a = entries.get(left);
        var b = entries.get(right);
        if (a.atom() instanceof ExactRational x && b.atom() instanceof ExactRational y) {
            var folded = ExprValueFactory.foldOrderedNumbers(operator, x, y);
            if (folded != null) return new Key(Kind.NUMBER, folded, NO_EDGES);
        }
        var kind = switch (operator) {
            case ADD -> Kind.ADD;
            case MUL -> Kind.MUL;
            case SUB -> Kind.SUB;
            case DIV -> Kind.DIV;
            case POW -> Kind.POW;
        };
        if (kind != Kind.ADD && kind != Kind.MUL) return new Key(kind, "", new int[]{left, right});
        var counts = new TreeMap<Integer, Integer>();
        addOperands(counts, kind, left);
        addOperands(counts, kind, right);
        var edges = new int[Math.multiplyExact(counts.size(), 2)];
        int position = 0;
        int total = 0;
        for (var term : counts.entrySet()) {
            edges[position++] = term.getKey();
            edges[position++] = term.getValue();
            total = Math.addExact(total, term.getValue()); // Match the existing AC operand-count bound.
        }
        return new Key(kind, "", edges);
    }

    private void addOperands(TreeMap<Integer, Integer> counts, Kind kind, int child) {
        Key key = entries.get(child);
        if (key.kind() != kind) {
            counts.merge(child, 1, Math::addExact);
        } else {
            for (int i = 0; i < key.edges().length; i += 2)
                counts.merge(key.edges()[i], key.edges()[i + 1], Math::addExact);
        }
    }

    public synchronized int size() {
        requireOpen();
        return entries.size();
    }

    /** Deterministic payload accounting, not an estimate of JVM heap or hash-map overhead. */
    public synchronized long structuralBytes() {
        requireOpen();
        long bytes = 0;
        for (var key : entries) bytes = Math.addExact(bytes, 12L + 2L * atomText(key).length() + 4L * key.edges().length);
        return bytes;
    }

    public synchronized int digestComputations() { requireOpen(); return digests.size(); }

    /** Canonical reachable DAG with allocation-independent local numbering. */
    public synchronized ExprValueSnapshot.Snapshot snapshot(ValueId root) {
        String expected = digest(root); // Validate ownership and populate the bounded Merkle cache.
        var numbering = new HashMap<Integer, Integer>();
        var nodes = new ArrayList<ExprValueSnapshot.Node>();
        var pending = new ArrayDeque<Integer>();
        pending.push(root.index);
        while (!pending.isEmpty()) {
            int current = pending.peek();
            if (numbering.containsKey(current)) { pending.pop(); continue; }
            var key = entries.get(current);
            int[] edges = snapshotEdges(key);
            boolean waiting = false;
            for (int i = edges.length - (key.unordered() ? 2 : 1); i >= 0; i -= key.unordered() ? 2 : 1)
                if (!numbering.containsKey(edges[i])) { pending.push(edges[i]); waiting = true; }
            if (waiting) continue;
            var mapped = new ArrayList<Integer>(edges.length);
            for (int i = 0; i < edges.length; i++)
                mapped.add(key.unordered() && i % 2 == 1 ? edges[i] : numbering.get(edges[i]));
            numbering.put(current, nodes.size());
            nodes.add(new ExprValueSnapshot.Node(key.kind().name(), atomText(key), mapped));
            pending.pop();
        }
        return new ExprValueSnapshot.Snapshot(ExprValueSnapshot.VERSION, numbering.get(root.index), expected, nodes);
    }

    private int[] snapshotEdges(Key key) {
        if (!key.unordered()) return key.edges();
        var pairs = new ArrayList<int[]>();
        for (int i = 0; i < key.edges().length; i += 2) pairs.add(new int[]{key.edges()[i], key.edges()[i + 1]});
        pairs.sort((a, b) -> Arrays.compareUnsigned(digests.get(a[0]), digests.get(b[0])));
        int[] result = new int[key.edges().length];
        for (int i = 0; i < pairs.size(); i++) { result[2 * i] = pairs.get(i)[0]; result[2 * i + 1] = pairs.get(i)[1]; }
        return result;
    }

    private static String atomText(Key key) {
        return key.atom() instanceof ExactRational n ? n.canonicalText() : (String) key.atom();
    }

    /** Re-intern every node; require the digest supplied by the caller's source binding. Atomic on rejection. */
    public synchronized ValueId restore(ExprValueSnapshot.Snapshot snapshot, String expectedDigest, int maximumBytes) {
        requireOpen();
        if (!ExprValueSnapshot.VERSION.equals(snapshot.version()) || !snapshot.digest().equals(expectedDigest)
                || snapshot.nodes().isEmpty() || snapshot.nodes().size() > maximumEntries
                || snapshot.root() != snapshot.nodes().size() - 1 || maximumBytes < 1
                || ExprValueSnapshot.encodedSize(snapshot) > maximumBytes)
            throw new IllegalArgumentException("snapshot binding or limit");
        int start = entries.size(), digestStart = digests.size();
        try {
            var mapped = new ArrayList<ValueId>(snapshot.nodes().size());
            var distinct = new java.util.HashSet<ValueId>();
            for (var node : snapshot.nodes()) {
                ValueId id = restoreNode(node, mapped);
                if (!distinct.add(id)) throw new IllegalArgumentException("duplicate snapshot node");
                mapped.add(id);
            }
            var root = mapped.get(snapshot.root());
            if (!digest(root).equals(expectedDigest) || snapshot(root).nodes().size() != mapped.size())
                throw new IllegalArgumentException("snapshot structure differs from binding");
            return root;
        } catch (RuntimeException rejected) {
            while (entries.size() > start) index.remove(entries.removeLast());
            while (digests.size() > digestStart) digests.removeLast();
            throw rejected;
        }
    }

    private ValueId restoreNode(ExprValueSnapshot.Node node, List<ValueId> mapped) {
        Kind kind = Kind.valueOf(node.kind());
        boolean ac = kind == Kind.ADD || kind == Kind.MUL;
        int[] edges = new int[node.edges().size()];
        for (int i = 0; i < edges.length; i++) {
            int value = node.edges().get(i);
            if (ac && i % 2 == 1) {
                if (value < 1) throw new IllegalArgumentException("nonpositive multiplicity");
                edges[i] = value;
            } else {
                if (value < 0 || value >= mapped.size()) throw new IllegalArgumentException("non-topological snapshot");
                edges[i] = mapped.get(value).index;
            }
        }
        Object atom = validateSnapshotNode(kind, node.atom(), edges);
        if (ac) edges = canonicalAcEdges(kind, edges);
        var key = new Key(kind, atom, edges);
        var existing = index.get(key);
        if (existing != null) return existing;
        if (entries.size() == maximumEntries) throw new IllegalArgumentException("snapshot arena capacity");
        var id = new ValueId(owner, entries.size());
        entries.add(key); index.put(key, id);
        return id;
    }

    private Object validateSnapshotNode(Kind kind, String atom, int[] edges) {
        switch (kind) {
            case NUMBER -> {
                var number = ExactRational.fromCanonicalText(atom);
                if (edges.length != 0 || !number.canonicalText().equals(atom)) throw new IllegalArgumentException("noncanonical number");
                return number;
            }
            case VARIABLE, SCOPED -> {
                var variable = new VariableExpr(atom);
                if (edges.length != 0 || variable.symbol().isPresent() != (kind == Kind.SCOPED))
                    throw new IllegalArgumentException("invalid variable node");
            }
            case FUNCTION -> {
                if (atom.isBlank() || !atom.trim().equals(atom)) throw new IllegalArgumentException("invalid function node");
            }
            default -> {
                if (!atom.isEmpty() || ((kind == Kind.ADD || kind == Kind.MUL)
                        ? edges.length < 2 || edges.length % 2 != 0 : edges.length != 2))
                    throw new IllegalArgumentException("invalid operator node");
            }
        }
        return atom;
    }

    private int[] canonicalAcEdges(Kind kind, int[] edges) {
        var sorted = new TreeMap<Integer, Integer>();
        int total = 0;
        for (int i = 0; i < edges.length; i += 2) {
            if (entries.get(edges[i]).kind() == kind || sorted.put(edges[i], edges[i + 1]) != null)
                throw new IllegalArgumentException("noncanonical AC node");
            total = Math.addExact(total, edges[i + 1]);
        }
        if (total < 2) throw new IllegalArgumentException("AC node arity");
        int at = 0;
        for (var term : sorted.entrySet()) { edges[at++] = term.getKey(); edges[at++] = term.getValue(); }
        return edges;
    }

    /**
     * Stable SHA-256 Merkle identity. Every field is versioned, length-framed and
     * ordered by semantic role. AC child digests sort independently of local IDs.
     * The digest is evidence for cross-process comparison, never an interning key.
     */
    public synchronized String digest(ValueId id) {
        requireOpen();
        if (Objects.requireNonNull(id, "id").owner != owner) throw new IllegalArgumentException("foreign value ID");
        if (digests.size() <= id.index) {
            MessageDigest hash;
            try {
                hash = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
            // Children are older than their parents. Each entry is hashed at most once.
            while (digests.size() <= id.index) digests.add(hashNode(hash, entries.get(digests.size())));
        }
        return DIGEST_VERSION + ":" + HexFormat.of().formatHex(digests.get(id.index));
    }

    private byte[] hashNode(MessageDigest hash, Key key) {
        text(hash, DIGEST_VERSION);
        text(hash, key.kind().name());
        text(hash, key.atom() instanceof ExactRational number ? number.canonicalText() : (String) key.atom());
        integer(hash, key.edges().length);
        if (key.unordered()) {
            var terms = new ArrayList<DigestCount>();
            for (int i = 0; i < key.edges().length; i += 2)
                terms.add(new DigestCount(digests.get(key.edges()[i]), key.edges()[i + 1]));
            terms.sort((a, b) -> {
                int order = Arrays.compareUnsigned(a.digest(), b.digest());
                return order != 0 ? order : Integer.compare(a.count(), b.count());
            });
            for (var term : terms) {
                hash.update(term.digest());
                integer(hash, term.count());
            }
        } else {
            for (int child : key.edges()) hash.update(digests.get(child));
        }
        return hash.digest();
    }

    // Preserve exact Java string identity, including unpaired UTF-16 code units.
    private static void text(MessageDigest hash, String value) {
        integer(hash, value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            hash.update((byte) (c >>> 8));
            hash.update((byte) c);
        }
    }

    private static void integer(MessageDigest hash, int value) {
        for (int shift = 24; shift >= 0; shift -= 8) hash.update((byte) (value >>> shift));
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("semantic value arena is closed");
    }

    @Override public synchronized void close() {
        for (var projection : projections) {
            projection.source = null;
            projection.values.clear();
        }
        projections.clear();
        entries.clear();
        index.clear();
        digests.clear();
        closed = true;
    }
}
