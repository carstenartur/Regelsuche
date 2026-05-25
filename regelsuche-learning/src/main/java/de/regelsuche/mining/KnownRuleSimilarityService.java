package de.regelsuche.mining;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Similarity model used to demote trivial rediscoveries of known rules. */
public final class KnownRuleSimilarityService {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*|\\d+(?:\\.\\d+)?|\\S");

    public double similarityToKnownRules(String leftPattern, String rightPattern, KnownRuleRepository repository) {
        return similarityToKnownRules(leftPattern, rightPattern, repository == null ? java.util.List.of() : repository.all());
    }

    public double similarityToKnownRules(String leftPattern, String rightPattern, Iterable<KnownRule> knownRules) {
        double best = 0.0;
        if (knownRules == null) {
            return best;
        }
        for (KnownRule rule : knownRules) {
            best = Math.max(best, similarity(leftPattern, rightPattern, rule.leftPattern(), rule.rightPattern()));
            best = Math.max(best, similarity(leftPattern, rightPattern, rule.rightPattern(), rule.leftPattern()));
        }
        return best;
    }

    public double similarity(String leftA, String rightA, String leftB, String rightB) {
        String canonicalA = canonicalRule(leftA, rightA);
        String canonicalB = canonicalRule(leftB, rightB);
        double edit = normalizedEditSimilarity(canonicalA, canonicalB);
        double operators = operatorFingerprintSimilarity(leftA + " " + rightA, leftB + " " + rightB);
        double placeholders = placeholderAwareSimilarity(leftA, rightA, leftB, rightB);
        return clamp(0.50 * edit + 0.25 * operators + 0.25 * placeholders);
    }

    private static String canonicalRule(String left, String right) {
        return RulePatternCanonicalizer.hash(normalizeFunctions(left), normalizeFunctions(right));
    }

    private static String normalizeFunctions(String expression) {
        return expression == null ? "" : expression.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static double placeholderAwareSimilarity(String leftA, String rightA, String leftB, String rightB) {
        String normalizedA = placeholderShape(leftA) + "->" + placeholderShape(rightA);
        String normalizedB = placeholderShape(leftB) + "->" + placeholderShape(rightB);
        if (normalizedA.equals(normalizedB)) {
            return 1.0;
        }
        return normalizedEditSimilarity(normalizedA, normalizedB);
    }

    private static String placeholderShape(String expression) {
        String normalized = RulePatternCanonicalizer.canonicalize(expression == null ? "" : expression);
        return TOKEN.matcher(normalized)
            .results()
            .map(match -> tokenShape(match.group()))
            .reduce("", (left, right) -> left + right);
    }

    private static String tokenShape(String token) {
        if (token.matches("p\\d+")) {
            return "P";
        }
        if (token.matches("\\d+(?:\\.\\d+)?")) {
            return "N";
        }
        return token;
    }

    private static double operatorFingerprintSimilarity(String left, String right) {
        Map<String, Integer> a = operatorFingerprint(left);
        Map<String, Integer> b = operatorFingerprint(right);
        int intersection = 0;
        int union = 0;
        for (String key : unionKeys(a, b).keySet()) {
            int leftCount = a.getOrDefault(key, 0);
            int rightCount = b.getOrDefault(key, 0);
            intersection += Math.min(leftCount, rightCount);
            union += Math.max(leftCount, rightCount);
        }
        return union == 0 ? 1.0 : intersection / (double) union;
    }

    private static Map<String, Integer> operatorFingerprint(String expression) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        String value = expression == null ? "" : expression.toLowerCase(Locale.ROOT);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ("+-*/^".indexOf(ch) >= 0) {
                counts.merge(String.valueOf(ch), 1, Integer::sum);
            }
        }
        java.util.regex.Matcher functions = Pattern.compile("\\b([a-z][a-z0-9_]*)\\s*\\(").matcher(value);
        while (functions.find()) {
            counts.merge("fn:" + functions.group(1), 1, Integer::sum);
        }
        return counts;
    }

    private static Map<String, Integer> unionKeys(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> keys = new LinkedHashMap<>();
        a.keySet().forEach(key -> keys.put(key, 1));
        b.keySet().forEach(key -> keys.put(key, 1));
        return keys;
    }

    private static double normalizedEditSimilarity(String left, String right) {
        if (left.equals(right)) {
            return 1.0;
        }
        int max = Math.max(left.length(), right.length());
        if (max == 0) {
            return 1.0;
        }
        return clamp(1.0 - levenshtein(left, right) / (double) max);
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                    Math.min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + cost
                );
            }
            int[] tmp = previous;
            previous = current;
            current = tmp;
        }
        return previous[right.length()];
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
