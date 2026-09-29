package de.regelsuche.transform;

import de.regelsuche.retention.RetainedOperation;

/** Publishes the actual immutable text before callers attach it to a trace. */
final class MatcherTrace {
    private MatcherTrace() { }

    static String text(String prefix,String suffix) {
        String result = prefix.concat(suffix);
        long production = result == prefix || result == suffix ? 0 : 1L + result.length();
        try (var owned = RetainedOperation.retainCompleted(1 + production,prefix,suffix,result)) {
            return result;
        }
    }

    static String ordinal(String prefix,int index) {
        String digits = Integer.toString(index);
        try (var owned = RetainedOperation.retainCompleted(1L + digits.length(),prefix,digits)) {
            return text(prefix,digits);
        }
    }
}
