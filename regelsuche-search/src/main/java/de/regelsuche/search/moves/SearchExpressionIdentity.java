package de.regelsuche.search.moves;

import de.regelsuche.ast.*;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/** Paid, session-local index operations; hashes are never equality or proof authority. */
final class SearchExpressionIdentity implements RetainedGraph.View {
    private final SearchExpressionStore store;
    private final Expr left, right;
    // Append-only ownership makes the final observation dominate every scratch
    // state in this atomic operation. This avoids rescanning the entire search
    // graph at each child edge; the extra retained frames/buffers are real and paid.
    private final ArrayList<HashFrame> hashing = new ArrayList<>();
    private final IdentityHashMap<Expr, Integer> hashes = new IdentityHashMap<>();
    private final ArrayList<Pair> comparing = new ArrayList<>();
    private final IdentityHashMap<Expr, IdentityHashMap<Expr, Boolean>> pairs = new IdentityHashMap<>();
    private final ArrayList<byte[]> encodings = new ArrayList<>();
    private HashFrame currentHash;
    private Pair current;
    private byte[] leftBytes, rightBytes;
    private long pairEntries;

    private SearchExpressionIdentity(SearchExpressionStore store, Expr left, Expr right) {
        this.store = store; this.left = left; this.right = right;
        store.pay(6); // observer and five scratch containers
    }

    static int hash(SearchExpressionStore store, Expr expression) {
        var identity = new SearchExpressionIdentity(store, expression, null);
        RetainedOperation.Frame retained = null;
        Throwable failure = null;
        try {
            retained = RetainedOperation.retain(identity);
            return identity.hash();
        } catch (RuntimeException | Error thrown) { failure = thrown; throw thrown; }
        finally { identity.release(); close(retained, failure); }
    }

    static boolean same(SearchExpressionStore store, Expr left, Expr right) {
        store.pay(1);
        if (left == right) return true;
        var identity = new SearchExpressionIdentity(store, left, right);
        RetainedOperation.Frame retained = null;
        Throwable failure = null;
        try {
            retained = RetainedOperation.retain(identity);
            return identity.same();
        } catch (RuntimeException | Error thrown) { failure = thrown; throw thrown; }
        finally { identity.release(); close(retained, failure); }
    }

    @Override public void retainedReferences(RetainedGraph.Visitor v) {
        v.reference(store); v.reference(left); v.reference(right); v.reference(hashing); v.reference(hashes);
        v.reference(comparing); v.reference(pairs); v.reference(encodings); v.reference(currentHash);
        v.reference(current); v.reference(leftBytes); v.reference(rightBytes);
    }

    private int hash() {
        currentHash = new HashFrame(left, null); hashing.add(currentHash); store.pay(3);
        while (currentHash != null) {
            var frame = currentHash; store.pay(1);
            if (!frame.initialized) {
                frame.hash = labelHash(frame.expression); frame.initialized = true; store.pay(1);
            }
            if (frame.nextChild < children(frame.expression)) {
                Expr child = child(frame.expression, frame.nextChild);
                Integer known = hashes.get(child); store.pay(2);
                if (known == null) {
                    currentHash = new HashFrame(child, frame); hashing.add(currentHash); store.pay(3);
                }
                else { frame.hash = mix(frame.hash, known); frame.nextChild++; store.pay(2); }
            } else {
                hashes.put(frame.expression, frame.hash); currentHash = frame.parent; store.pay(2);
            }
        }
        RetainedOperation.checkpoint();
        store.pay(1); return hashes.get(left);
    }

    private int labelHash(Expr expression) {
        store.pay(1);
        return switch (expression) {
            case VariableExpr variable -> mix(2, textHash(variable.name()));
            case NumberExpr number -> mix(mix(1, integerHash(number.value().numerator())), integerHash(number.value().denominator()));
            case BinaryExpr binary -> mix(3, binary.operator().ordinal());
            case FunctionExpr function -> mix(mix(4, textHash(function.name())), function.arguments().size());
        };
    }

    private boolean same() {
        comparing.add(new Pair(left, right)); store.pay(2);
        for (int position = 0; position < comparing.size(); position++) {
            current = comparing.get(position); store.pay(2);
            Expr a = current.left(), b = current.right();
            if (a == b) continue;
            var row = pairs.get(a); store.pay(1);
            if (row == null) { row = new IdentityHashMap<>(); pairs.put(a, row); store.pay(2); }
            store.pay(1);
            if (row.put(b, Boolean.TRUE) != null) continue;
            pairEntries++; store.pay(1);
            // Every new identity pair checks its label and schedules all children.
            if (!sameLabel(a, b)) { RetainedOperation.checkpoint(); return false; }
            for (int i = 0; i < children(a); i++) {
                comparing.add(new Pair(child(a, i), child(b, i))); store.pay(4);
            }
        }
        RetainedOperation.checkpoint();
        return true;
    }

    private boolean sameLabel(Expr a, Expr b) {
        store.pay(1);
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
        for (int i = 0; i < text.length(); i++) { hash = mix(hash, text.charAt(i)); store.pay(1); }
        return hash;
    }
    private boolean sameText(String a, String b) {
        store.pay(1);
        if (a == b) return true;
        if (a.length() != b.length()) return false;
        for (int i = 0; i < a.length(); i++) { store.pay(1); if (a.charAt(i) != b.charAt(i)) return false; }
        return true;
    }
    private int integerHash(BigInteger value) {
        requireScalar(value);
        leftBytes = value.toByteArray(); encodings.add(leftBytes); store.pay(2L + leftBytes.length);
        int hash = 0;
        for (byte valueByte : leftBytes) { hash = mix(hash, valueByte); store.pay(1); }
        leftBytes = null; store.pay(1);
        return hash;
    }
    private boolean sameInteger(BigInteger a, BigInteger b) {
        store.pay(1);
        if (a == b) return true;
        requireScalar(a); requireScalar(b);
        leftBytes = a.toByteArray(); encodings.add(leftBytes); store.pay(2L + leftBytes.length);
        rightBytes = b.toByteArray(); encodings.add(rightBytes); store.pay(2L + rightBytes.length);
        boolean equal = leftBytes.length == rightBytes.length;
        for (int i = 0; equal && i < leftBytes.length; i++) { store.pay(1); equal = leftBytes[i] == rightBytes[i]; }
        leftBytes = null; rightBytes = null; store.pay(2);
        return equal;
    }
    private void requireScalar(BigInteger value) {
        store.pay(1);
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
        store.pay(Math.addExact(4L + hashing.size() + comparing.size() + encodings.size(),
            Math.addExact(2L * hashes.size(), Math.addExact(2L * pairs.size(), 2L * pairEntries))));
        hashing.clear(); hashes.clear(); comparing.clear(); pairs.clear(); encodings.clear();
        currentHash = null; current = null; leftBytes = null; rightBytes = null;
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
}
