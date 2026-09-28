package de.regelsuche.transform;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The existing length-prefixed UTF-16 framing with one actual assembly buffer. */
final class MatcherDescriptor {
    private MatcherDescriptor() { }

    /** The existing immutable definition, rather than a captured rendering callback. */
    sealed interface Source extends RetainedGraph.View permits ExprMatcher,ExprMatcher.Constraint {
        String canonicalDescriptor();
    }

    static String render(Source source) {
        Kind kind = kind(source);
        var assembly = new Assembly(source,kind.type,kind.fields);
        try (var owned = RetainedOperation.retainCompleted(3L + kind.fields,assembly)) {
            try {
                fields(source,assembly);
                return assembly.finish();
            } catch (RuntimeException | Error failure) {
                observeFailure(failure); throw failure;
            }
        }
    }

    private enum Kind {
        ANY("any",0), NUMBER("literal-number",1), VARIABLE("literal-variable",1), PROPERTY("number-property",1),
        PATTERN("pattern",2), BIND("bind",3), ALL("all-of",1), ALTERNATIVES("any-of",1), NOT("not",1),
        OPERATION("operation",3), FUNCTION("function",2), CONTAINS("contains",1), EQUIVALENT("equivalent",2),
        WHERE("where",2), BINDING_MATCHES("binding-matches",2), SAME_AS("same-as",3);
        private final String type;
        private final int fields;
        Kind(String type,int fields) { this.type = type; this.fields = fields; }
    }

    private static Kind kind(Source source) {
        return switch (source) {
            case ExprMatcher.Any ignored -> Kind.ANY;
            case ExprMatcher.LiteralNumber ignored -> Kind.NUMBER;
            case ExprMatcher.LiteralVariable ignored -> Kind.VARIABLE;
            case ExprMatcher.NumberProperty ignored -> Kind.PROPERTY;
            case ExprMatcher.Pattern ignored -> Kind.PATTERN;
            case ExprMatcher.Bind ignored -> Kind.BIND;
            case ExprMatcher.AllOf ignored -> Kind.ALL;
            case ExprMatcher.AnyOf ignored -> Kind.ALTERNATIVES;
            case ExprMatcher.Not ignored -> Kind.NOT;
            case ExprMatcher.Operation ignored -> Kind.OPERATION;
            case ExprMatcher.Function ignored -> Kind.FUNCTION;
            case ExprMatcher.Contains ignored -> Kind.CONTAINS;
            case ExprMatcher.Equivalent ignored -> Kind.EQUIVALENT;
            case ExprMatcher.Where ignored -> Kind.WHERE;
            case ExprMatcher.BindingMatches ignored -> Kind.BINDING_MATCHES;
            case ExprMatcher.SameAs ignored -> Kind.SAME_AS;
        };
    }

    private static void fields(Source source,Assembly assembly) {
        switch (source) {
            case ExprMatcher.Any ignored -> { }
            case ExprMatcher.LiteralNumber value -> assembly.putText(0,value.value().canonicalText());
            case ExprMatcher.LiteralVariable value -> assembly.put(0,value.name());
            case ExprMatcher.NumberProperty value -> assembly.put(0,value.kind().name());
            case ExprMatcher.Pattern value -> {
                assembly.put(0,pattern(value.pattern()));
                assembly.put(1,profile(value.recognitionProfile()));
            }
            case ExprMatcher.Bind value -> {
                assembly.put(0,value.name()); assembly.put(1,value.matcher().canonicalDescriptor());
                assembly.put(2,profile(value.equalityProfile()));
            }
            case ExprMatcher.AllOf value -> assembly.put(0,matchers(value.matchers()));
            case ExprMatcher.AnyOf value -> assembly.put(0,matchers(value.matchers()));
            case ExprMatcher.Not value -> assembly.put(0,value.matcher().canonicalDescriptor());
            case ExprMatcher.Operation value -> {
                assembly.put(0,value.operator().name()); assembly.put(1,value.left().canonicalDescriptor());
                assembly.put(2,value.right().canonicalDescriptor());
            }
            case ExprMatcher.Function value -> {
                assembly.put(0,value.name()); assembly.put(1,matchers(value.arguments()));
            }
            case ExprMatcher.Contains value -> assembly.put(0,value.matcher().canonicalDescriptor());
            case ExprMatcher.Equivalent value -> {
                assembly.put(0,profile(value.recognitionProfile())); assembly.put(1,value.matcher().canonicalDescriptor());
            }
            case ExprMatcher.Where value -> {
                assembly.put(0,value.matcher().canonicalDescriptor()); assembly.put(1,value.constraint().canonicalDescriptor());
            }
            case ExprMatcher.BindingMatches value -> {
                assembly.put(0,value.bindingName()); assembly.put(1,value.matcher().canonicalDescriptor());
            }
            case ExprMatcher.SameAs value -> {
                assembly.put(0,value.leftBinding()); assembly.put(1,value.rightBinding());
                assembly.put(2,profile(value.recognitionProfile()));
            }
        }
    }

    /** Historical record text, sharing the descriptor assembly's actual buffer owner. */
    private static String pattern(PatternExpr source) {
        var assembly = new Assembly(source,"pattern",0);
        try (var owned = RetainedOperation.retainCompleted(2,assembly)) {
            try {
                assembly.buffer = new char[64];
                RetainedOperation.work(65);
                assembly.pattern(source);
                return assembly.output();
            } catch (RuntimeException | Error failure) {
                observeFailure(failure); throw failure;
            }
        }
    }

    private static String matchers(List<ExprMatcher> matchers) {
        var assembly = new Assembly(matchers,"matcher-list",matchers.size());
        try (var owned = RetainedOperation.retainCompleted(2L + matchers.size(),assembly)) {
            try {
                for (int index = 0; index < matchers.size(); index++) {
                    assembly.put(index,matchers.get(index).canonicalDescriptor());
                }
                return assembly.finish();
            } catch (RuntimeException | Error failure) {
                observeFailure(failure); throw failure;
            }
        }
    }

    private static String profile(RecognitionProfile profile) {
        var assembly = new Assembly(profile,"recognition-profile",5);
        try (var owned = RetainedOperation.retainCompleted(7,assembly)) {
            try {
                assembly.put(0,names("associative",profile.associativeOperators()));
                assembly.put(1,names("commutative",profile.commutativeOperators()));
                assembly.put(2,Boolean.toString(profile.inferAlgebraicBindings()));
                assembly.put(3,names("recognition-rules",profile.recognitionRuleIds()));
                assembly.putText(4,Integer.toString(profile.maxEquivalenceDepth()));
                return assembly.finish();
            } catch (RuntimeException | Error failure) {
                observeFailure(failure); throw failure;
            }
        }
    }

    private static String names(String type,Set<?> values) {
        var assembly = new Assembly(values,type,values.size());
        try (var owned = RetainedOperation.retainCompleted(2L + values.size(),assembly)) {
            try {
                int index = 0;
                for (Object value : values) assembly.put(index++,value instanceof Enum<?> item ? item.name() : (String) value);
                assembly.sort();
                return assembly.finish();
            } catch (RuntimeException | Error failure) {
                observeFailure(failure); throw failure;
            }
        }
    }

    private static void observeFailure(Throwable failure) {
        try { RetainedOperation.checkpoint(); }
        catch (RuntimeException | Error observation) {
            if (observation != failure) failure.addSuppressed(observation);
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
        private final Object source;
        private final String type;
        private final String[] fields;
        private char[] buffer, growing;
        private String fragment, output;
        private int offset;

        private Assembly(Object source,String type,int count) {
            this.source = source; this.type = type; this.fields = new String[count];
        }

        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(source); visitor.reference(type); visitor.reference(fields);
            visitor.reference(buffer); visitor.reference(growing);
            visitor.reference(fragment); visitor.reference(output);
        }

        private void put(int index,String field) {
            fields[index] = Objects.requireNonNull(field,"field");
            RetainedOperation.work(1);
        }

        private void putText(int index,String field) {
            fields[index] = Objects.requireNonNull(field,"field");
            RetainedOperation.work(2L + field.length());
        }

        private String finish() {
            int length = encodedLength(type);
            for (String field : fields) length = Math.addExact(length,encodedLength(Objects.requireNonNull(field,"field")));
            buffer = new char[length];
            RetainedOperation.work(1L + length);
            append(type);
            for (String field : fields) append(field);
            return output();
        }

        private String output() {
            output = new String(buffer,0,offset);
            RetainedOperation.work(1L + offset);
            RetainedOperation.checkpoint();
            return output;
        }

        private void pattern(PatternExpr pattern) {
            RetainedOperation.work(1);
            switch (pattern) {
                case PatternExpr.Placeholder value -> {
                    pattern("Placeholder[name="); pattern(value.name()); pattern("]");
                }
                case PatternExpr.LiteralNumber value -> {
                    pattern("LiteralNumber[value=");
                    // The completed numeric text is owned and paid here; internal
                    // BigInteger conversion remains a separate inventory boundary.
                    pattern(value.value().canonicalText(),true); pattern("]");
                }
                case PatternExpr.LiteralVariable value -> {
                    pattern("LiteralVariable[name="); pattern(value.name()); pattern("]");
                }
                case PatternExpr.Operation value -> {
                    pattern("Operation[operator="); pattern(value.operator().name());
                    pattern(", left="); pattern(value.left());
                    pattern(", right="); pattern(value.right()); pattern("]");
                }
                case PatternExpr.Function value -> {
                    pattern("Function[name="); pattern(value.name()); pattern(", arguments=[");
                    for (int index = 0; index < value.arguments().size(); index++) {
                        RetainedOperation.work(1);
                        if (index > 0) pattern(", ");
                        pattern(value.arguments().get(index));
                    }
                    pattern("]]");
                }
            }
        }

        private void pattern(String text) { pattern(text,false); }

        private void pattern(String text,boolean produced) {
            fragment = text;
            RetainedOperation.work(1L + (produced ? 1L + text.length() : 0));
            int required = Math.addExact(offset,text.length());
            capacity(required);
            text.getChars(0,text.length(),buffer,offset);
            offset = required;
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
            System.arraycopy(buffer,0,growing,0,offset);
            RetainedOperation.work(1L + offset);
            buffer = growing; growing = null;
            RetainedOperation.work(2);
        }

        /** In-place heapsort: bounded scalar scratch, no hidden merge buffer. */
        private void sort() {
            for (int root = fields.length / 2 - 1; root >= 0; root--) sift(root,fields.length);
            for (int end = fields.length - 1; end > 0; end--) {
                swap(0,end); sift(0,end);
            }
        }

        private void sift(int root,int length) {
            while (root < length / 2) {
                int child = root * 2 + 1;
                RetainedOperation.work(1);
                if (child + 1 < length && compare(fields[child],fields[child + 1]) < 0) child++;
                if (compare(fields[root],fields[child]) >= 0) return;
                swap(root,child); root = child;
                RetainedOperation.work(1);
            }
        }

        private void swap(int first,int second) {
            String value = fields[first]; fields[first] = fields[second]; fields[second] = value;
            RetainedOperation.work(3);
        }

        private static int compare(String first,String second) {
            int length = Math.min(first.length(),second.length());
            for (int index = 0; index < length; index++) {
                int order = first.charAt(index) - second.charAt(index);
                RetainedOperation.work(3);
                if (order != 0) return order;
            }
            RetainedOperation.work(1);
            return first.length() - second.length();
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
