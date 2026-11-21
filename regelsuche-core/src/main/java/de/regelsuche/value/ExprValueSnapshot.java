package de.regelsuche.value;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded structural transport. Node numbers refer only to this snapshot, never an arena owner. */
public final class ExprValueSnapshot {
    public static final String VERSION = "regelsuche.expr-value-snapshot/v1";
    private static final int MAGIC = 0x52535631;
    private ExprValueSnapshot() {}

    public record Node(String kind, String atom, List<Integer> edges) {
        public Node {
            Objects.requireNonNull(kind); Objects.requireNonNull(atom);
            edges = List.copyOf(edges);
        }
    }
    public record Snapshot(String version, int root, String digest, List<Node> nodes) {
        public Snapshot {
            Objects.requireNonNull(version); Objects.requireNonNull(digest);
            nodes = List.copyOf(nodes);
        }
    }

    /** Exact transport byte count, excluding JVM objects, maps and cached digests. */
    public static long encodedSize(Snapshot snapshot) {
        long size = 12L + stringBytes(snapshot.version()) + stringBytes(snapshot.digest());
        for (var node : snapshot.nodes()) size = Math.addExact(size,
            stringBytes(node.kind()) + stringBytes(node.atom()) + 4L + 4L * node.edges().size());
        return size;
    }

    private static long stringBytes(String value) { return 4L + 2L * value.length(); }

    public static byte[] encode(Snapshot snapshot, int maximumBytes) {
        long size = encodedSize(snapshot);
        if (maximumBytes < 1 || size > maximumBytes) throw new IllegalArgumentException("snapshot byte limit");
        try {
            var buffer = new ByteArrayOutputStream((int) size);
            var out = new DataOutputStream(buffer);
            out.writeInt(MAGIC); text(out, snapshot.version()); out.writeInt(snapshot.root());
            text(out, snapshot.digest()); out.writeInt(snapshot.nodes().size());
            for (var node : snapshot.nodes()) {
                text(out, node.kind()); text(out, node.atom()); out.writeInt(node.edges().size());
                for (int edge : node.edges()) out.writeInt(edge);
            }
            out.flush();
            return buffer.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public static Snapshot decode(byte[] bytes, int maximumNodes, int maximumBytes) {
        Objects.requireNonNull(bytes);
        if (maximumBytes < 1 || bytes.length > maximumBytes || maximumNodes < 1)
            throw new IllegalArgumentException("snapshot limit");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC) throw new IllegalArgumentException("snapshot magic");
            String version = text(in);
            if (!VERSION.equals(version)) throw new IllegalArgumentException("snapshot version");
            int root = in.readInt();
            String digest = text(in);
            int count = in.readInt();
            if (count < 1 || count > maximumNodes || count > in.available() / 12)
                throw new IllegalArgumentException("snapshot node count");
            var nodes = new ArrayList<Node>(count);
            for (int i = 0; i < count; i++) nodes.add(readNode(in));
            if (in.available() != 0) throw new IllegalArgumentException("trailing snapshot bytes");
            return new Snapshot(version, root, digest, nodes);
        } catch (IOException malformed) { throw new IllegalArgumentException("truncated snapshot", malformed); }
    }

    private static Node readNode(DataInputStream in) throws IOException {
        String kind = text(in), atom = text(in);
        int count = in.readInt();
        if (count < 0 || count > in.available() / 4) throw new IllegalArgumentException("snapshot edge count");
        var edges = new ArrayList<Integer>(count);
        for (int i = 0; i < count; i++) edges.add(in.readInt());
        return new Node(kind, atom, edges);
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        out.writeInt(value.length());
        for (int i = 0; i < value.length(); i++) out.writeChar(value.charAt(i));
    }
    private static String text(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available() / 2) throw new IllegalArgumentException("snapshot text length");
        var value = new StringBuilder(length);
        for (int i = 0; i < length; i++) value.append(in.readChar());
        return value.toString();
    }
}
