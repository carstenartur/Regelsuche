package de.regelsuche.value;

import de.regelsuche.ast.*;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.value.ExprValueFactory.ValueOperator;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Bounded, thread-confined structural interning. Local IDs never authorize cross-owner equality. */
public final class CompactValueArena implements AutoCloseable {
    public static final String DIGEST_REVISION = "regelsuche.compact-value-digest/v1";
    private enum Kind { VARIABLE, NUMBER, ORDERED, AC }
    public record Limits(int maxValues, int maxSyntaxNodes, int maxChildSlots, long maxPayloadBytes) {
        public static final Limits DEFAULT = new Limits(100_000, 200_000, 1_000_000, 16_000_000);
        public Limits {
            if (maxValues < 1 || maxSyntaxNodes < 1 || maxChildSlots < 0 || maxPayloadBytes < 1)
                throw new IllegalArgumentException("invalid compact value arena limits");
        }
    }
    /** Counts retained integer slots/payload, not object headers, allocations or Java heap bytes. */
    public record Metrics(int values, int syntaxNodes, long childSlots, long payloadBytes,
            long syntaxProjections, long syntaxCacheHits, long internHits,
            long digestComputations, long digestBytes, long legacyExports) { }
    public static final class CapacityExceeded extends IllegalStateException {
        private CapacityExceeded(String pool) { super("compact value arena capacity exceeded: " + pool); }
    }
    public static final class ValueId {
        private final CompactValueArena owner;
        private final int index;
        private ValueId(CompactValueArena owner, int index) { this.owner = owner; this.index = index; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof ValueId id && owner == id.owner && index == id.index;
        }
        @Override public int hashCode() { return 31 * System.identityHashCode(owner) + index; }
        @Override public String toString() { return "owner-local ValueId(" + index + ")"; }
    }
    private static final class Key {
        final Kind kind;
        final Object payload;
        final int[] children;
        final int hash;
        Key(Kind kind, Object payload, int[] children, int mask) {
            this.kind = kind; this.payload = payload; this.children = children;
            hash = (31 * (31 * kind.ordinal() + payload.hashCode()) + Arrays.hashCode(children)) & mask;
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Key key && kind == key.kind
                && payload.equals(key.payload) && Arrays.equals(children, key.children);
        }
    }
    private static final class Node {
        final Key key;
        final ValueId id;
        byte[] digest;
        Node(Key key, ValueId id) { this.key = key; this.id = id; }
    }
    private static final class Frame {
        final Expr syntax;
        final List<Expr> children;
        int next;
        Frame(Expr syntax) { this.syntax = syntax; children = children(syntax); }
    }
    private static final class DigestFrame {
        final Node node;
        int next;
        DigestFrame(Node node) { this.node = node; }
    }
    private record DigestOperand(byte[] digest, int multiplicity) { }

    private final Limits limits;
    private final int hashMask;
    private final Map<Key, Node> interned = new HashMap<>();
    private final List<Node> nodes = new ArrayList<>();
    private final IdentityHashMap<Expr, ValueId> syntaxValues = new IdentityHashMap<>();
    private long childSlots, payloadBytes, syntaxProjections, syntaxCacheHits, internHits;
    private long digestComputations, digestBytes, legacyExports;
    private boolean closed;

    public CompactValueArena() { this(Limits.DEFAULT); }
    public CompactValueArena(Limits limits) { this(limits, 32); }
    /** Package-local hash-width control exercises real structural collision confirmation. */
    CompactValueArena(Limits limits, int hashBits) {
        this.limits = Objects.requireNonNull(limits);
        if (hashBits < 0 || hashBits > 32) throw new IllegalArgumentException("invalid index hash width");
        hashMask = hashBits == 32 ? -1 : (int) ((1L << hashBits) - 1);
    }
    public ValueId variable(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("variable name must not be blank");
        return intern(Kind.VARIABLE, name, new int[0]);
    }
    public ValueId number(ExactRational value) { return intern(Kind.NUMBER, Objects.requireNonNull(value), new int[0]); }

    public ValueId ordered(ValueOperator operator, List<ValueId> operands) {
        ensureOpen(); Objects.requireNonNull(operator); Objects.requireNonNull(operands);
        int[] children = operands.stream().mapToInt(this::requireOwned).toArray();
        if (operator.laws().supportsUnorderedNaryValue()) return ac(operator, children);
        operator.requireArity(children.length);
        ValueId folded = numericFold(operator, children);
        return folded == null ? intern(Kind.ORDERED, operator, children) : folded;
    }
    private ValueId numericFold(ValueOperator operator, int[] children) {
        if (children.length != 2 || (!operator.equals(ValueOperator.SUB) && !operator.equals(ValueOperator.DIV))) return null;
        var a = nodes.get(children[0]).key; var b = nodes.get(children[1]).key;
        if (a.kind != Kind.NUMBER || b.kind != Kind.NUMBER) return null;
        var first = (ExactRational) a.payload; var second = (ExactRational) b.payload;
        if (operator.equals(ValueOperator.SUB)) return number(first.subtract(second));
        return second.isZero() ? null : number(first.divide(second));
    }
    private ValueId ac(ValueOperator operator, int[] operands) {
        if (operands.length == 0) throw new IllegalArgumentException("AC value requires operands");
        var counts = new TreeMap<Integer, Integer>();
        for (int operand : operands) addOperand(counts, operator, operand);
        int total = counts.values().stream().reduce(0, Math::addExact);
        if (total == 1) return nodes.get(counts.firstKey()).id;
        operator.requireArity(total);
        int[] entries = new int[Math.multiplyExact(counts.size(), 2)];
        int offset = 0;
        for (var entry : counts.entrySet()) { entries[offset++] = entry.getKey(); entries[offset++] = entry.getValue(); }
        return intern(Kind.AC, operator, entries);
    }
    private void addOperand(Map<Integer, Integer> counts, ValueOperator operator, int id) {
        var key = nodes.get(id).key;
        if (key.kind == Kind.AC && key.payload.equals(operator)) {
            for (int i = 0; i < key.children.length; i += 2) counts.merge(key.children[i], key.children[i + 1], Math::addExact);
        } else counts.merge(id, 1, Math::addExact);
    }
    private ValueId intern(Kind kind, Object payload, int[] children) {
        ensureOpen();
        var key = new Key(kind, payload, children, hashMask);
        var existing = interned.get(key);
        if (existing != null) { internHits++; return existing.id; }
        long payloadSize = payloadBytes(kind, payload);
        if (nodes.size() >= limits.maxValues()) throw new CapacityExceeded("values");
        if (children.length > limits.maxChildSlots() - childSlots) throw new CapacityExceeded("child slots");
        if (payloadSize > limits.maxPayloadBytes() - payloadBytes) throw new CapacityExceeded("payload bytes");
        var node = new Node(key, new ValueId(this, nodes.size()));
        nodes.add(node); interned.put(key, node);
        childSlots += children.length; payloadBytes += payloadSize;
        return node.id;
    }
    private static long payloadBytes(Kind kind, Object payload) {
        if (kind == Kind.NUMBER) {
            var rational = (ExactRational) payload;
            return 8L + rational.numerator().toByteArray().length + rational.denominator().toByteArray().length;
        }
        String text = kind == Kind.VARIABLE ? (String) payload : ((ValueOperator) payload).identityToken();
        return 4L + 2L * text.length();
    }

    public Projection project(Expr syntax) { return new Projection(Objects.requireNonNull(syntax), value(syntax)); }
    private ValueId value(Expr syntax) {
        ensureOpen();
        ValueId existing = syntaxValues.get(syntax);
        if (existing != null) { syntaxCacheHits++; return existing; }
        var pending = new ArrayDeque<Frame>();
        push(pending, syntax);
        while (!pending.isEmpty()) projectNext(pending);
        return syntaxValues.get(syntax);
    }
    private void projectNext(ArrayDeque<Frame> pending) {
        var frame = pending.peek();
        if (frame.next < frame.children.size()) {
            Expr child = frame.children.get(frame.next++);
            if (syntaxValues.containsKey(child)) syntaxCacheHits++;
            else push(pending, child);
            return;
        }
        ValueId id = projectNode(frame.syntax);
        syntaxValues.put(frame.syntax, id); syntaxProjections++; pending.pop();
    }
    private void push(ArrayDeque<Frame> pending, Expr syntax) {
        if ((long) syntaxValues.size() + pending.size() >= limits.maxSyntaxNodes()) throw new CapacityExceeded("syntax nodes");
        pending.push(new Frame(syntax));
    }
    private ValueId projectNode(Expr syntax) {
        if (syntax instanceof VariableExpr variable) return variable(variable.name());
        if (syntax instanceof NumberExpr number) return number(number.value());
        if (syntax instanceof FunctionExpr function)
            return ordered(ValueOperator.function(function.name(), function.arguments().size()),
                function.arguments().stream().map(syntaxValues::get).toList());
        var binary = (BinaryExpr) syntax;
        ValueId left = syntaxValues.get(binary.left()), right = syntaxValues.get(binary.right());
        return binary(binary.operator(), left, right);
    }
    private ValueId binary(BinaryOperator operator, ValueId left, ValueId right) {
        ValueOperator valueOperator = switch (operator) {
            case ADD -> ValueOperator.ADD; case SUB -> ValueOperator.SUB; case MUL -> ValueOperator.MUL;
            case DIV -> ValueOperator.DIV; case POW -> ValueOperator.POW;
        };
        return ordered(valueOperator, List.of(left, right));
    }
    private static List<Expr> children(Expr expression) {
        if (expression instanceof BinaryExpr binary) return List.of(binary.left(), binary.right());
        if (expression instanceof FunctionExpr function) return function.arguments();
        return List.of();
    }

    public final class Projection {
        private final Expr syntax;
        private final ValueId value;
        private Projection(Expr syntax, ValueId value) { this.syntax = syntax; this.value = value; }
        public Expr syntax() { ensureOpen(); return syntax; }
        public ValueId value() { ensureOpen(); return value; }
        public Occurrence occurrence(List<Integer> path) {
            ensureOpen();
            var selected = new TreePosition(path, "owner-bound-occurrence").subtreeAt(syntax)
                .orElseThrow(() -> new IllegalArgumentException("occurrence does not exist in this source"));
            return new Occurrence(this, List.copyOf(path), selected, syntaxValues.get(selected));
        }
        public Projection replace(Occurrence occurrence, Expr replacement) {
            requireOccurrence(occurrence);
            var replaced = new TreePosition(occurrence.path, "owner-bound-occurrence").replaceAt(syntax, replacement);
            return project(replaced.rewrittenRoot().orElseThrow());
        }
        public void requireOccurrence(Occurrence occurrence) {
            ensureOpen();
            if (occurrence == null || occurrence.source != this) throw new IllegalArgumentException("foreign or stale source occurrence");
        }
        /** Explicit legacy serialization boundary; retains the existing recursive value-key contract. */
        public ExprValueFactory.ValueKey legacyKey() {
            ensureOpen(); legacyExports++;
            try (var legacy = new ExprValueFactory(limits.maxValues())) { return legacy.fromExpr(syntax).key(); }
        }
    }
    public static final class Occurrence {
        private final Projection source;
        private final List<Integer> path;
        private final Expr syntax;
        private final ValueId value;
        private Occurrence(Projection source, List<Integer> path, Expr syntax, ValueId value) {
            this.source = source; this.path = path; this.syntax = syntax; this.value = value;
        }
        public List<Integer> path() { source.syntax(); return path; }
        public Expr syntax() { source.syntax(); return syntax; }
        public ValueId value() { source.syntax(); return value; }
    }

    public String stableDigest(ValueId id) {
        Node root = nodes.get(requireOwned(id));
        var pending = new ArrayDeque<DigestFrame>();
        if (root.digest == null) pending.push(new DigestFrame(root));
        while (!pending.isEmpty()) digestNext(pending);
        return DIGEST_REVISION + ":sha256:" + HexFormat.of().formatHex(root.digest);
    }
    private void digestNext(ArrayDeque<DigestFrame> pending) {
        var frame = pending.peek();
        var key = frame.node.key;
        if (frame.next < key.children.length) {
            var child = nodes.get(key.children[frame.next]);
            frame.next += key.kind == Kind.AC ? 2 : 1;
            if (child.digest == null) pending.push(new DigestFrame(child));
            return;
        }
        frame.node.digest = digest(key); pending.pop();
    }
    private byte[] digest(Key key) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            writeBytes(out, DIGEST_REVISION.getBytes(StandardCharsets.UTF_8)); out.writeByte(key.kind.ordinal());
            writePayload(out, key);
            var operands = digestOperands(key);
            out.writeInt(operands.size());
            for (var operand : operands) { writeBytes(out, operand.digest()); out.writeInt(operand.multiplicity()); }
            byte[] encoded = bytes.toByteArray();
            digestComputations++; digestBytes += encoded.length;
            return MessageDigest.getInstance("SHA-256").digest(encoded);
        } catch (IOException | NoSuchAlgorithmException exception) { throw new IllegalStateException("unable to encode compact value digest", exception); }
    }
    private List<DigestOperand> digestOperands(Key key) {
        var operands = new ArrayList<DigestOperand>();
        int stride = key.kind == Kind.AC ? 2 : 1;
        for (int i = 0; i < key.children.length; i += stride)
            operands.add(new DigestOperand(nodes.get(key.children[i]).digest, stride == 2 ? key.children[i + 1] : 1));
        if (key.kind == Kind.AC) operands.sort((a, b) -> {
            int comparison = Arrays.compareUnsigned(a.digest(), b.digest());
            return comparison == 0 ? Integer.compare(a.multiplicity(), b.multiplicity()) : comparison;
        });
        return operands;
    }
    private static void writePayload(DataOutputStream out, Key key) throws IOException {
        if (key.kind == Kind.NUMBER) {
            var number = (ExactRational) key.payload;
            writeBytes(out, number.numerator().toByteArray()); writeBytes(out, number.denominator().toByteArray());
        } else {
            String text = key.kind == Kind.VARIABLE ? (String) key.payload : ((ValueOperator) key.payload).identityToken();
            // Java names can contain unpaired surrogates: encode exact code units, never UTF-8 replacement characters.
            out.writeInt(text.length());
            for (int i = 0; i < text.length(); i++) out.writeChar(text.charAt(i));
        }
    }
    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException { out.writeInt(bytes.length); out.write(bytes); }
    private int requireOwned(ValueId id) {
        ensureOpen();
        if (id == null || id.owner != this) throw new IllegalArgumentException("foreign value ID");
        return id.index;
    }
    public Metrics metrics() {
        return new Metrics(nodes.size(), syntaxValues.size(), childSlots, payloadBytes, syntaxProjections,
            syntaxCacheHits, internHits, digestComputations, digestBytes, legacyExports);
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("compact value arena is closed"); }
    @Override public void close() {
        closed = true; interned.clear(); nodes.clear(); syntaxValues.clear(); childSlots = 0; payloadBytes = 0;
    }
}
