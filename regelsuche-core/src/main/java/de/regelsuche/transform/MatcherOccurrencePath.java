package de.regelsuche.transform;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.util.List;

/** The existing dotted occurrence format, assembled without intermediate prefix strings. */
final class MatcherOccurrencePath {
    private MatcherOccurrencePath() { }

    static String render(List<Integer> path) {
        RetainedOperation.work(1);
        if (path.isEmpty()) return "root";
        var assembly = new Assembly(path);
        try (var owned = RetainedOperation.retainCompleted(1,assembly)) {
            try {
                int length = path.size() - 1;
                for (int index : path) length = Math.addExact(length,decimalDigits(index));
                assembly.buffer = new char[length];
                RetainedOperation.work(1L + length);
                for (int index : path) assembly.append(index);
                assembly.output = new String(assembly.buffer);
                RetainedOperation.work(1L + length);
                RetainedOperation.checkpoint();
                return assembly.output;
            } catch (RuntimeException | Error failure) {
                try { RetainedOperation.checkpoint(); }
                catch (RuntimeException | Error observation) {
                    if (observation != failure) failure.addSuppressed(observation);
                }
                throw failure;
            }
        }
    }

    private static int decimalDigits(int value) {
        if (value < 0) throw new IllegalArgumentException("negative occurrence index");
        int digits = 1;
        for (; value >= 10; value /= 10) digits++;
        RetainedOperation.work(1L + digits);
        return digits;
    }

    private static final class Assembly implements RetainedGraph.View {
        private final List<Integer> path;
        private char[] buffer;
        private String output;
        private int offset;

        private Assembly(List<Integer> path) { this.path = path; }

        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(path); visitor.reference(buffer); visitor.reference(output);
        }

        private void append(int value) {
            if (offset > 0) {
                buffer[offset++] = '.';
                RetainedOperation.work(1);
            }
            int digits = decimalDigits(value);
            int end = offset + digits;
            for (int cursor = end - 1; cursor >= offset; cursor--) {
                buffer[cursor] = (char) ('0' + value % 10);
                value /= 10;
            }
            offset = end;
            RetainedOperation.work(3L * digits + 1);
        }
    }
}
