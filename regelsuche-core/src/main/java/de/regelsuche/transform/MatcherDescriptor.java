package de.regelsuche.transform;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.util.Objects;

/** The existing length-prefixed UTF-16 framing with one actual assembly buffer. */
final class MatcherDescriptor {
    private MatcherDescriptor() { }

    /** The existing immutable definition, rather than a captured rendering callback. */
    sealed interface Source extends RetainedGraph.View permits ExprMatcher,ExprMatcher.Constraint {
        String canonicalDescriptor();
    }

    /** Callers delegate their freshly assembled field array; field strings keep their own production work. */
    static String render(String type,String[] fields) {
        var assembly = new Assembly(type,fields);
        try (var owned = RetainedOperation.retainCompleted(2L + fields.length,assembly)) {
            try {
                int length = encodedLength(type);
                for (String field : fields) {
                    length = Math.addExact(length,encodedLength(Objects.requireNonNull(field,"field")));
                }
                assembly.buffer = new char[length];
                RetainedOperation.work(1L + length);
                assembly.append(type);
                for (String field : fields) assembly.append(field);
                assembly.output = new String(assembly.buffer);
                RetainedOperation.work(1L + length);
                // Assembly only adds owners: this observes the input array,
                // completed buffer and copied output together, including their peaks.
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

    private static int encodedLength(String field) {
        int digits = decimalDigits(field.length());
        RetainedOperation.work(1L + digits);
        return Math.addExact(field.length(),digits + 1);
    }

    private static int decimalDigits(int value) {
        int digits = 1;
        for (; value >= 10; value /= 10) digits++;
        return digits;
    }

    private static final class Assembly implements RetainedGraph.View {
        private final String type;
        private final String[] fields;
        private char[] buffer;
        private String output;
        private int offset;

        private Assembly(String type,String[] fields) {
            this.type = type; this.fields = fields;
        }

        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(type); visitor.reference(fields);
            visitor.reference(buffer); visitor.reference(output);
        }

        private void append(String field) {
            int length = field.length();
            int digits = decimalDigits(length);
            int colon = offset + digits;
            int remaining = length;
            for (int index = colon - 1; index >= offset; index--) {
                buffer[index] = (char) ('0' + remaining % 10);
                remaining /= 10;
            }
            buffer[colon] = ':';
            RetainedOperation.work(3L * digits + 1);
            field.getChars(0,length,buffer,colon + 1);
            offset = colon + 1 + length;
            RetainedOperation.work(1L + length);
        }
    }
}
