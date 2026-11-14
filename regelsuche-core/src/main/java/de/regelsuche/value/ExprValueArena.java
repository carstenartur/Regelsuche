package de.regelsuche.value;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.scalar.ExactRational;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

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

    private record Visit(Expr syntax, boolean expanded) {}
    private record DigestCount(byte[] digest, int count) {}
    private static final int[] NO_EDGES = {};
    private final Object owner = new Object();
    private final int maximumEntries;
    private final List<Key> entries = new ArrayList<>();
    private final HashMap<Key, ValueId> index = new HashMap<>();
    private final List<byte[]> digests = new ArrayList<>();
    private boolean closed;

    public ExprValueArena(int maximumEntries) {
        if (maximumEntries < 1) throw new IllegalArgumentException("maximumEntries must be positive");
        this.maximumEntries = maximumEntries;
    }

    /** Each syntax object is visited once per projection, including shared input DAGs. */
    public synchronized ValueId intern(Expr expression) {
        requireOpen();
        Objects.requireNonNull(expression, "expression");
        int startingSize = entries.size();
        try {
            var resolved = new IdentityHashMap<Expr, ValueId>();
            var visits = new ArrayDeque<Visit>();
            visits.push(new Visit(expression, false));
            while (!visits.isEmpty()) {
                var visit = visits.pop();
                Expr syntax = visit.syntax();
                if (resolved.containsKey(syntax)) continue;
                List<Expr> children = switch (syntax) {
                    case BinaryExpr binary -> List.of(binary.left(), binary.right());
                    case FunctionExpr function -> function.arguments();
                    default -> List.of();
                };
                if (visit.expanded() || children.isEmpty()) {
                    resolved.put(syntax, internNode(syntax, resolved));
                } else {
                    visits.push(new Visit(syntax, true));
                    for (var child : children.reversed()) {
                        if (!resolved.containsKey(child)) visits.push(new Visit(child, false));
                    }
                }
            }
            return resolved.get(expression);
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
        entries.clear();
        index.clear();
        digests.clear();
        closed = true;
    }
}
