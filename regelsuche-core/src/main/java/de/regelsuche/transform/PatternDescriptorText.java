package de.regelsuche.transform;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;

/** The historical PatternExpr record text, written without recursive intermediate Strings. */
final class PatternDescriptorText implements RetainedGraph.View {
    private final PatternExpr source;
    private char[] buffer = new char[64];
    private char[] growing;
    private String fragment, output;
    private int used;

    private PatternDescriptorText(PatternExpr source) { this.source = source; }

    static String render(PatternExpr source) {
        var text = new PatternDescriptorText(source);
        try (var owned = RetainedOperation.retainCompleted(2L + text.buffer.length,text)) {
            try {
                text.append(source);
                text.output = new String(text.buffer,0,text.used);
                RetainedOperation.work(1L + text.used);
                RetainedOperation.checkpoint();
                return text.output;
            } catch (RuntimeException | Error failure) {
                try { RetainedOperation.checkpoint(); }
                catch (RuntimeException | Error observation) {
                    if (observation != failure) failure.addSuppressed(observation);
                }
                throw failure;
            }
        }
    }

    @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
        visitor.reference(source); visitor.reference(buffer); visitor.reference(growing);
        visitor.reference(fragment); visitor.reference(output);
    }

    private void append(PatternExpr pattern) {
        RetainedOperation.work(1);
        switch (pattern) {
            case PatternExpr.Placeholder value -> {
                append("Placeholder[name="); append(value.name()); append("]");
            }
            case PatternExpr.LiteralNumber value -> {
                append("LiteralNumber[value=");
                // The completed numeric text is owned and paid here; internal
                // BigInteger conversion remains a separate inventory boundary.
                append(value.value().canonicalText(),true); append("]");
            }
            case PatternExpr.LiteralVariable value -> {
                append("LiteralVariable[name="); append(value.name()); append("]");
            }
            case PatternExpr.Operation value -> {
                append("Operation[operator="); append(value.operator().name());
                append(", left="); append(value.left());
                append(", right="); append(value.right()); append("]");
            }
            case PatternExpr.Function value -> {
                append("Function[name="); append(value.name()); append(", arguments=[");
                for (int index = 0; index < value.arguments().size(); index++) {
                    RetainedOperation.work(1);
                    if (index > 0) append(", ");
                    append(value.arguments().get(index));
                }
                append("]]");
            }
        }
    }

    private void append(String text) { append(text,false); }

    private void append(String text,boolean produced) {
        fragment = text;
        RetainedOperation.work(1L + (produced ? 1L + text.length() : 0));
        int required = Math.addExact(used,text.length());
        capacity(required);
        text.getChars(0,text.length(),buffer,used);
        used = required;
        RetainedOperation.work(1L + text.length());
        RetainedOperation.checkpoint();
        fragment = null;
        RetainedOperation.work(1);
    }

    private void capacity(int required) {
        RetainedOperation.work(1);
        if (required <= buffer.length) return;
        int next = (int) Math.min(Integer.MAX_VALUE,Math.max(required,buffer.length + buffer.length / 2L));
        growing = new char[next];
        RetainedOperation.work(1L + next);
        RetainedOperation.checkpoint();
        System.arraycopy(buffer,0,growing,0,used);
        RetainedOperation.work(1L + used);
        buffer = growing; growing = null;
        RetainedOperation.work(2);
    }
}
