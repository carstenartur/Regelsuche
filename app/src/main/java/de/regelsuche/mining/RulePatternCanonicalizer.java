package de.regelsuche.mining;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RulePatternCanonicalizer {
    private static final Pattern VARIABLE = Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9_]*\\b");

    private RulePatternCanonicalizer() {
    }

    public static String hash(String leftPattern, String rightPattern) {
        return canonicalize(leftPattern) + "->" + canonicalize(rightPattern);
    }

    public static String canonicalize(String pattern) {
        String compact = pattern.replaceAll("\\s+", "")
            .replace("**", "^")
            .replace(")(", ")*(");
        Matcher matcher = VARIABLE.matcher(compact);
        Map<String, String> names = new LinkedHashMap<>();
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = names.computeIfAbsent(matcher.group(), key -> "p" + (names.size() + 1));
            matcher.appendReplacement(buffer, replacement);
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
