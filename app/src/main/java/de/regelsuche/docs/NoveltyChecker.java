package de.regelsuche.docs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies discovery candidates against already seen input/target pairs, alpha-equivalent
 * variants and explicitly known rules. The checker is deliberately syntax-based: it is a
 * conservative gate for reports, not a mathematical oracle.
 */
final class NoveltyChecker {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> BUILTIN_FUNCTIONS = Set.of(
        "sin", "cos", "tan", "sec", "csc", "cot", "sqrt", "log", "exp", "abs"
    );

    private final Set<String> knownRuleIds;
    private final List<Candidate> knownCandidates;

    NoveltyChecker() {
        this(Set.of(), List.of());
    }

    NoveltyChecker(Set<String> knownRuleIds, List<Candidate> knownCandidates) {
        this.knownRuleIds = normalizeSet(knownRuleIds);
        this.knownCandidates = knownCandidates == null ? List.of() : List.copyOf(knownCandidates);
    }

    List<NoveltyResult> classifyAll(List<Candidate> candidates) {
        List<Candidate> seen = new ArrayList<>(knownCandidates);
        List<NoveltyResult> results = new ArrayList<>();
        for (Candidate candidate : candidates == null ? List.<Candidate>of() : candidates) {
            NoveltyResult result = classify(candidate, seen);
            results.add(result);
            if (candidate != null && result.status() != NoveltyStatus.UNKNOWN) {
                seen.add(candidate);
            }
        }
        return List.copyOf(results);
    }

    NoveltyResult classify(Candidate candidate, List<Candidate> previousCandidates) {
        if (candidate == null || candidate.inputExpression().isBlank() || candidate.targetExpression().isBlank()) {
            return new NoveltyResult(NoveltyStatus.UNKNOWN, "", "missing input or target expression");
        }
        if (usesKnownRule(candidate)) {
            return new NoveltyResult(NoveltyStatus.KNOWN_RULE, "", "candidate path already uses a known rule id");
        }

        List<Candidate> previous = previousCandidates == null ? List.of() : previousCandidates;
        for (Candidate previousCandidate : previous) {
            if (previousCandidate == null) {
                continue;
            }
            if (exactPairKey(candidate).equals(exactPairKey(previousCandidate))) {
                return new NoveltyResult(
                    NoveltyStatus.DUPLICATE,
                    previousCandidate.id(),
                    "same normalized input/target pair"
                );
            }
        }
        for (Candidate previousCandidate : previous) {
            if (previousCandidate == null) {
                continue;
            }
            if (alphaPairKey(candidate).equals(alphaPairKey(previousCandidate))) {
                return new NoveltyResult(
                    NoveltyStatus.ALPHA_EQUIVALENT,
                    previousCandidate.id(),
                    "same alpha-equivalent input/target pattern"
                );
            }
        }
        for (Candidate previousCandidate : previous) {
            if (previousCandidate == null) {
                continue;
            }
            if (sameFamilyAndOperator(candidate, previousCandidate)) {
                return new NoveltyResult(
                    NoveltyStatus.VARIANT,
                    previousCandidate.id(),
                    "same family/operator with a different structural pattern"
                );
            }
        }
        return new NoveltyResult(NoveltyStatus.NEW, "", "new family/operator pattern");
    }

    private boolean usesKnownRule(Candidate candidate) {
        if (knownRuleIds.isEmpty()) {
            return false;
        }
        for (String ruleId : candidate.rulePath()) {
            if (knownRuleIds.contains(normalizeToken(ruleId))) {
                return true;
            }
        }
        return false;
    }

    private boolean sameFamilyAndOperator(Candidate left, Candidate right) {
        return !left.family().isBlank()
            && left.family().equals(right.family())
            && !left.operatorId().isBlank()
            && left.operatorId().equals(right.operatorId());
    }

    private static String exactPairKey(Candidate candidate) {
        return normalizeExpression(candidate.inputExpression())
            + "->"
            + normalizeExpression(candidate.targetExpression());
    }

    private static String alphaPairKey(Candidate candidate) {
        Map<String, String> variableMap = new LinkedHashMap<>();
        return alphaNormalize(candidate.inputExpression(), variableMap)
            + "->"
            + alphaNormalize(candidate.targetExpression(), variableMap);
    }

    private static String normalizeExpression(String expression) {
        return expression == null ? "" : expression.replaceAll("\\s+", "");
    }

    private static String alphaNormalize(String expression, Map<String, String> variableMap) {
        String normalized = normalizeExpression(expression);
        Matcher matcher = IDENTIFIER.matcher(normalized);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String identifier = matcher.group();
            String replacement = isBuiltinFunction(normalized, matcher.end(), identifier)
                ? identifier.toLowerCase(Locale.ROOT)
                : variableMap.computeIfAbsent(identifier, ignored -> "v" + variableMap.size());
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean isBuiltinFunction(String expression, int endIndex, String identifier) {
        if (!BUILTIN_FUNCTIONS.contains(identifier.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return endIndex < expression.length() && expression.charAt(endIndex) == '(';
    }

    private static Set<String> normalizeSet(Set<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values == null ? Set.<String>of() : values) {
            String token = normalizeToken(value);
            if (!token.isBlank()) {
                normalized.add(token);
            }
        }
        return Set.copyOf(normalized);
    }

    private static String normalizeToken(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    record Candidate(
        String id,
        String family,
        String inputExpression,
        String targetExpression,
        String operatorId,
        List<String> rulePath
    ) {
        Candidate {
            id = id == null ? "" : id;
            family = family == null ? "" : family;
            inputExpression = inputExpression == null ? "" : inputExpression;
            targetExpression = targetExpression == null ? "" : targetExpression;
            operatorId = operatorId == null ? "" : operatorId;
            rulePath = rulePath == null ? List.of() : List.copyOf(rulePath);
        }
    }

    record NoveltyResult(NoveltyStatus status, String matchedCandidateId, String reason) {
        NoveltyResult {
            status = status == null ? NoveltyStatus.UNKNOWN : status;
            matchedCandidateId = matchedCandidateId == null ? "" : matchedCandidateId;
            reason = reason == null ? "" : reason;
        }
    }
}
