package de.regelsuche.ast;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/** One iterative value-identity traversal, shared by AST callers and the bounded search index.
 * Java value hashes remain compatible; index fingerprints remain a separate historical format.
 * Work and scratch ownership are charged only through the supplied or active account. */
public final class ExpressionIdentity implements RetainedGraph.View {
    public interface Work extends RetainedGraph.View {
        void chargeIdentity(long units);
        void checkIdentityScratch(long references, long characters);
    }
    private enum ActiveWork implements Work {
        INSTANCE;
        @Override public void chargeIdentity(long units) { RetainedOperation.work(units); }
        @Override public void checkIdentityScratch(long references, long characters) {
            // The active ownership account enforces its complete live-graph limits
            // at the traversal's bounded checkpoints; no global expression cache.
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {}
    }
    private final Work store;
    private final boolean valueHash;
    private final Expr left, right;
    // Monotone ownership between bounded checkpoints preserves intermediate peaks
    // without rescanning the whole search at each edge. Local limits also apply
    // when there is no enclosing search observer.
    private static final int CHECKPOINT_GROWTH = 4096;
    private final ArrayList<HashFrame> hashing = new ArrayList<>();
    private final IdentityHashMap<Expr, Integer> hashes = new IdentityHashMap<>();
    private final ArrayList<Pair> comparing = new ArrayList<>();
    private final IdentityHashMap<Expr, IdentityHashMap<Expr, Boolean>> pairs = new IdentityHashMap<>();
    private final IdentityHashMap<BigInteger, EncodedInteger> encodings = new IdentityHashMap<>();
    private HashFrame currentHash;
    private Pair current;
    private EncodedInteger pendingEncoding;
    private long pairEntries, encodingBytes;
    private int growthSinceCheckpoint;

    private ExpressionIdentity(Work store, Expr left, Expr right, boolean valueHash) {
        this.store = store; this.left = left; this.right = right; this.valueHash = valueHash;
    }

    public static int valueHash(Expr expression) {
        return hash(ActiveWork.INSTANCE, expression, true);
    }
    public static boolean same(Expr expression, Object other) {
        if (!(other instanceof Expr right)) { RetainedOperation.work(1); return false; }
        return same(ActiveWork.INSTANCE, expression, right);
    }
    public static int hash(Work store, Expr expression) {
        return hash(store, expression, false);
    }
    private static int hash(Work store, Expr expression, boolean valueHash) {
        var identity = new ExpressionIdentity(store, expression, null, valueHash);
        RetainedOperation.Frame retained = null;
        Throwable failure = null;
        try {
            retained = RetainedOperation.retain(identity);
            store.chargeIdentity(6); // published observer and five scratch containers
            return identity.hash();
        } catch (RuntimeException | Error thrown) { failure = thrown; throw thrown; }
        finally { identity.finish(retained, failure); }
    }

    public static boolean same(Work store, Expr left, Expr right) {
        store.chargeIdentity(1);
        if (left == right) return true;
        var identity = new ExpressionIdentity(store, left, right, false);
        RetainedOperation.Frame retained = null;
        Throwable failure = null;
        try {
            retained = RetainedOperation.retain(identity);
            store.chargeIdentity(6); // published observer and five scratch containers
            return identity.same();
        } catch (RuntimeException | Error thrown) { failure = thrown; throw thrown; }
        finally { identity.finish(retained, failure); }
    }

    @Override public void retainedReferences(RetainedGraph.Visitor v) {
        v.reference(store); v.reference(left); v.reference(right); v.reference(hashing); v.reference(hashes);
        v.reference(comparing); v.reference(pairs); v.reference(encodings); v.reference(currentHash);
        v.reference(current); v.reference(pendingEncoding);
    }

    private int hash() {
        currentHash = new HashFrame(left, null); hashing.add(currentHash); store.chargeIdentity(3);
        grew();
        while (currentHash != null) {
            var frame = currentHash; store.chargeIdentity(1);
            if (!frame.initialized) {
                frame.hash = valueHash ? valueLabelHash(frame.expression) : labelHash(frame.expression); frame.initialized = true; store.chargeIdentity(1);
            }
            if (frame.nextChild < children(frame.expression)) {
                Expr child = child(frame.expression, frame.nextChild);
                Integer known = hashes.get(child); store.chargeIdentity(2);
                if (known == null) {
                    currentHash = new HashFrame(child, frame); hashing.add(currentHash); store.chargeIdentity(3);
                    grew();
                }
                else {
                    if (valueHash && frame.expression instanceof BinaryExpr binary && frame.nextChild == 0)
                        frame.hash = mix(known, binary.operator().hashCode());
                    else frame.hash = mix(frame.hash, known);
                    frame.nextChild++; store.chargeIdentity(2);
                }
            } else {
                int completedHash = valueHash && frame.expression instanceof FunctionExpr function
                    ? mix(textHash(function.name()), frame.hash) : frame.hash;
                hashes.put(frame.expression, completedHash); currentHash = frame.parent; store.chargeIdentity(2);
                grew();
            }
        }
        RetainedOperation.checkpoint();
        store.chargeIdentity(1); return hashes.get(left);
    }

    private int valueLabelHash(Expr expression) {
        store.chargeIdentity(1);
        return switch (expression) {
            case VariableExpr variable -> {
                var symbol = variable.identitySymbol();
                if (symbol == null) yield textHash(variable.name());
                store.chargeIdentity(3); // UUID and ordinal are fixed-size value fields
                yield symbol.hashCode();
            }
            case NumberExpr number -> mix(javaIntegerHash(number.value().numerator()), javaIntegerHash(number.value().denominator()));
            case BinaryExpr ignored -> 0;
            case FunctionExpr ignored -> 1; // List.hashCode starts at one
        };
    }
    private int javaIntegerHash(BigInteger value) {
        if (value.getClass() != BigInteger.class) {
            // Preserve legacy Java value semantics; native validation rejects opaque scalar subclasses.
            store.chargeIdentity(1); return value.hashCode();
        }
        var encoded = encoding(value);
        if (!encoded.hashed) {
            store.chargeIdentity(1L + (encoded.bytes.length + 3L) / 4L);
            encoded.hash = value.hashCode(); encoded.hashed = true;
        }
        return encoded.hash;
    }

    private int labelHash(Expr expression) {
        store.chargeIdentity(1);
        return switch (expression) {
            case VariableExpr variable -> mix(2, textHash(variable.name()));
            case NumberExpr number -> mix(mix(1, integerHash(number.value().numerator())), integerHash(number.value().denominator()));
            case BinaryExpr binary -> mix(3, binary.operator().ordinal());
            case FunctionExpr function -> mix(mix(4, textHash(function.name())), function.arguments().size());
        };
    }

    private boolean same() {
        comparing.add(new Pair(left, right)); store.chargeIdentity(2);
        grew();
        for (int position = 0; position < comparing.size(); position++) {
            current = comparing.get(position); store.chargeIdentity(2);
            Expr a = current.left(), b = current.right();
            if (a == b) continue;
            var row = pairs.get(a); store.chargeIdentity(1);
            if (row == null) { row = new IdentityHashMap<>(); pairs.put(a, row); store.chargeIdentity(2); grew(); }
            store.chargeIdentity(1);
            if (row.put(b, Boolean.TRUE) != null) continue;
            pairEntries++; store.chargeIdentity(1);
            grew();
            // Every new identity pair checks its label and schedules all children.
            if (!sameLabel(a, b)) { RetainedOperation.checkpoint(); return false; }
            for (int i = 0; i < children(a); i++) {
                comparing.add(new Pair(child(a, i), child(b, i))); store.chargeIdentity(4);
                grew();
            }
        }
        RetainedOperation.checkpoint();
        return true;
    }

    private boolean sameLabel(Expr a, Expr b) {
        store.chargeIdentity(1);
        return switch (a) {
            // Scoped names are the lossless SymbolId identifier, never a display name.
            // VariableExpr's constructors reserve/validate that identifier namespace.
            case VariableExpr x -> b instanceof VariableExpr y && sameText(x.name(), y.name());
            case NumberExpr x -> b instanceof NumberExpr y
                && sameInteger(x.value().numerator(), y.value().numerator())
                && sameInteger(x.value().denominator(), y.value().denominator());
            case BinaryExpr x -> b instanceof BinaryExpr y && x.operator() == y.operator();
            case FunctionExpr x -> b instanceof FunctionExpr y && x.arguments().size() == y.arguments().size()
                && sameText(x.name(), y.name());
        };
    }

    private int textHash(String text) {
        int hash = 0;
        for (int i = 0; i < text.length(); i++) { hash = mix(hash, text.charAt(i)); store.chargeIdentity(1); }
        return hash;
    }
    private boolean sameText(String a, String b) {
        store.chargeIdentity(1);
        if (a == b) return true;
        if (a.length() != b.length()) return false;
        for (int i = 0; i < a.length(); i++) { store.chargeIdentity(1); if (a.charAt(i) != b.charAt(i)) return false; }
        return true;
    }
    private int integerHash(BigInteger value) {
        var encoded = encoding(value); store.chargeIdentity(1);
        if (!encoded.hashed) {
            for (byte valueByte : encoded.bytes) { encoded.hash = mix(encoded.hash, valueByte); store.chargeIdentity(1); }
            encoded.hashed = true; store.chargeIdentity(1);
        }
        return encoded.hash;
    }
    private boolean sameInteger(BigInteger a, BigInteger b) {
        store.chargeIdentity(1);
        if (a == b) return true;
        if (a.getClass() != BigInteger.class || b.getClass() != BigInteger.class) {
            store.chargeIdentity(1); return a.equals(b);
        }
        var leftEncoding = encoding(a); var rightEncoding = encoding(b);
        boolean equal = leftEncoding.bytes.length == rightEncoding.bytes.length;
        for (int i = 0; equal && i < leftEncoding.bytes.length; i++) {
            store.chargeIdentity(1); equal = leftEncoding.bytes[i] == rightEncoding.bytes[i];
        }
        return equal;
    }
    private EncodedInteger encoding(BigInteger value) {
        requireScalar(value);
        var known = encodings.get(value); store.chargeIdentity(1);
        if (known != null) return known;
        pendingEncoding = new EncodedInteger(value.toByteArray());
        store.chargeIdentity(2L + pendingEncoding.bytes.length);
        encodings.put(value, pendingEncoding); store.chargeIdentity(1);
        encodingBytes = Math.addExact(encodingBytes, pendingEncoding.bytes.length);
        grew();
        return pendingEncoding;
    }
    private void grew() {
        // Lower bound from actual scratch slots; the enclosing observer still
        // measures the complete ownership graph, including its own scan storage.
        long references = 3L * hashing.size() + 2L * hashes.size() + 3L * comparing.size()
            + 3L * pairs.size() + 2L * pairEntries + 3L * encodings.size();
        store.checkIdentityScratch(references, encodingBytes);
        if (++growthSinceCheckpoint >= CHECKPOINT_GROWTH) {
            growthSinceCheckpoint = 0;
            RetainedOperation.checkpoint();
        }
    }
    private void requireScalar(BigInteger value) {
        store.chargeIdentity(1);
        if (value.getClass() != BigInteger.class) throw new IllegalArgumentException("unsupported scalar retention subclass");
    }
    private static int mix(int a, int b) { return 31 * a + b; }
    private static int children(Expr expression) {
        return switch (expression) { case BinaryExpr ignored -> 2; case FunctionExpr f -> f.arguments().size(); default -> 0; };
    }
    private static Expr child(Expr expression, int index) {
        return switch (expression) {
            case BinaryExpr b -> index == 0 ? b.left() : b.right();
            case FunctionExpr f -> f.arguments().get(index);
            default -> throw new IllegalArgumentException("leaf has no children");
        };
    }
    private void release() {
        try {
            store.chargeIdentity(Math.addExact(3L + hashing.size() + comparing.size() + 2L * encodings.size(),
                Math.addExact(2L * hashes.size(), Math.addExact(2L * pairs.size(), 2L * pairEntries))));
        } finally {
            hashing.clear(); hashes.clear(); comparing.clear(); pairs.clear(); encodings.clear();
            currentHash = null; current = null; pendingEncoding = null;
        }
    }
    private void finish(RetainedOperation.Frame retained, Throwable failure) {
        if (failure != null && retained != null) {
            // An exceptional exit may precede the next batch/terminal observation.
            try { RetainedOperation.checkpoint(); }
            catch (RuntimeException | Error observation) { if (observation != failure) failure.addSuppressed(observation); }
        }
        try { release(); }
        catch (RuntimeException | Error cleanup) {
            if (failure == null) { failure = cleanup; throw cleanup; }
            if (cleanup != failure) failure.addSuppressed(cleanup);
        } finally { close(retained, failure); }
    }
    private static void close(RetainedOperation.Frame retained, Throwable failure) {
        if (retained == null) return;
        try { retained.close(); }
        catch (RuntimeException | Error cleanup) {
            if (failure == null) throw cleanup;
            if (cleanup != failure) failure.addSuppressed(cleanup);
        }
    }
    private static final class HashFrame implements RetainedGraph.View {
        final Expr expression;
        final HashFrame parent;
        int nextChild, hash;
        boolean initialized;
        HashFrame(Expr expression, HashFrame parent) { this.expression = expression; this.parent = parent; }
        @Override public void retainedReferences(RetainedGraph.Visitor v) { v.reference(expression); v.reference(parent); }
    }
    private record Pair(Expr left, Expr right) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v) { v.reference(left); v.reference(right); }
    }
    private static final class EncodedInteger implements RetainedGraph.View {
        final byte[] bytes;
        int hash;
        boolean hashed;
        EncodedInteger(byte[] bytes) { this.bytes = bytes; }
        @Override public void retainedReferences(RetainedGraph.Visitor v) { v.reference(bytes); }
    }
}
